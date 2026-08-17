package services

import dataset.DsInfo
import organization.OrgContext
import play.api.Logging
import triplestore.GraphProperties._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

/**
 * Daily acquisition-boundary check: asks each harvest source for its total
 * record count and stores it next to our own acquired counts, so a drift
 * between what the endpoint holds and what we harvested is visible instead
 * of waiting for a user to notice "the numbers don't match".
 *
 * Cheap by design: one request per dataset per day (OAI-PMH ListIdentifiers
 * first page -> completeListSize; AdLib search=all&limit=1 -> hits). Sources
 * that don't report a total (JSON without totalPath, downloads, uploads)
 * are skipped. completeListSize is OPTIONAL in the OAI spec and some servers
 * estimate it or count deleted headers differently — so the check SURFACES
 * drift; repairing (a scheduled full harvest) is opt-in via config.
 */
class SourceCountService(orgContext: OrgContext)(implicit ec: ExecutionContext) extends Logging {

  private def tagToInt(nodeSeq: scala.xml.NodeSeq, tag: String, default: Int): Int =
    scala.util.Try((nodeSeq \ tag).text.trim.toInt).getOrElse(default)

  /** Remote total for one dataset, or None when the source doesn't say. */
  def fetchRemoteTotal(dsInfo: DsInfo): Future[Option[Int]] = {
    def prop(p: triplestore.GraphProperties.NXProp): String = dsInfo.getLiteralProp(p).getOrElse("")
    val credentials: Option[(String, String)] = {
      val username = prop(harvestUsername)
      val encrypted = prop(harvestPassword)
      if (username.nonEmpty && encrypted.nonEmpty)
        Some((username, CredentialEncryption.decrypt(encrypted, orgContext.appConfig.appSecret)))
      else None
    }
    if (prop(harvestURL).trim.isEmpty) return Future.successful(None)
    prop(harvestType) match {
      case "pmh" =>
        val base = prop(harvestURL).stripSuffix("?")
        val setParam = Option(prop(harvestDataset)).filter(_.nonEmpty).map(s => s"&set=$s").getOrElse("")
        val url = s"$base?verb=ListIdentifiers&metadataPrefix=${prop(harvestPrefix)}$setParam"
        val request = credentials.foldLeft(orgContext.wsApi.url(url).withFollowRedirects(true).withRequestTimeout(scala.concurrent.duration.DurationInt(60).seconds)) {
          case (req, (u, p)) => req.withAuth(u, p, play.api.libs.ws.WSAuthScheme.BASIC)
        }
        request.get().map { response =>
          val xml = response.xml
          val token = xml \ "ListIdentifiers" \ "resumptionToken"
          val complete = tagToInt(token, "@completeListSize", -1)
          if (complete >= 0) Some(complete)
          else {
            // No resumptionToken (or no size attribute): a single-page list is
            // complete, so the header count IS the total. Otherwise unknown.
            val headers = (xml \ "ListIdentifiers" \ "header").size
            if (token.isEmpty && headers > 0) Some(headers) else None
          }
        }
      case "adlib" =>
        val base = prop(harvestURL).stripSuffix("?")
        val search = Option(prop(harvestSearch)).filter(_.nonEmpty).getOrElse("all")
        val url = s"$base?database=${prop(harvestDataset)}&search=$search&xmltype=grouped&limit=1"
        val request = credentials.foldLeft(orgContext.wsApi.url(url).withFollowRedirects(true).withRequestTimeout(scala.concurrent.duration.DurationInt(60).seconds)) {
          case (req, (u, p)) => req.withAuth(u, p, play.api.libs.ws.WSAuthScheme.BASIC)
        }
        request.get().map { response =>
          val hits = tagToInt(response.xml \ "diagnostic", "hits", -1)
          if (hits >= 0) Some(hits) else None
        }
      case _ => Future.successful(None) // json/download/upload: no cheap authoritative total
    }
  }

  /** Sweep every harvest-configured, non-disabled dataset. */
  def runSweep(): Unit = {
    DsInfo.listDsInfoLight(orgContext).foreach { list =>
      val candidates = list.filter(ds =>
        ds.harvestType.exists(t => t == "pmh" || t == "adlib") && ds.stateDisabled.isEmpty)
      logger.info(s"SourceCount sweep: checking ${candidates.size} datasets")
      // Sequential fold: one outstanding request at a time — this is a
      // background hygiene job, not a load test of customer endpoints.
      candidates.foldLeft(Future.successful(())) { (acc, ds) =>
        acc.flatMap { _ =>
          val dsInfo = DsInfo.getDsInfo(ds.spec, orgContext)
          // Future.unit.flatMap: a synchronous throw from URL building (e.g.
          // empty harvestURL) must land in the recover below, not abort the
          // whole sweep fold.
          Future.unit.flatMap(_ => fetchRemoteTotal(dsInfo)).map { totalOpt =>
            totalOpt.foreach { total =>
              dsInfo.setSingularLiteralProps(
                sourceRemoteTotal -> total.toString,
                sourceRemoteCheckTime -> Temporal.timeToString(new org.joda.time.DateTime())
              )
              // Known source duplicates are counted by the endpoint but
              // deduped by us — they are expected, permanent "drift" and must
              // not put a set in a daily repair loop.
              val dupExtras = duplicateExtras(ds.spec)
              val local = ds.sourceRecordCount.getOrElse(0) + ds.deletedRecordCount.getOrElse(0) + dupExtras
              val drift = total - local
              if (drift != 0) {
                logger.warn(s"SourceCount drift for ${ds.spec}: remote=$total local=$local (drift=$drift, dupExtras=$dupExtras)")
                maybeRepair(dsInfo, drift, total)
              }
            }
          }.recover { case e =>
            logger.warn(s"SourceCount check failed for ${ds.spec}: ${e.getMessage}")
          }
        }
      }.onComplete {
        case Success(_) => logger.info("SourceCount sweep completed")
        case Failure(e) => logger.warn(s"SourceCount sweep aborted: ${e.getMessage}")
      }
    }
  }

  /** Extra occurrences of duplicated source ids (occurrences beyond the first,
    * which we keep) — read from the duplicates.txt defect file. */
  private def duplicateExtras(spec: String): Int = {
    val f = new java.io.File(new java.io.File(new java.io.File(orgContext.datasetsDir, spec), "source"), "duplicates.txt")
    if (!f.exists()) 0
    else scala.util.Try {
      scala.io.Source.fromFile(f, "UTF-8").getLines().map { line =>
        line.split('\t') match {
          case Array(_, n) => math.max(0, n.trim.toInt - 1)
          case _ => 0
        }
      }.sum
    }.getOrElse(0)
  }

  private val REPAIR_COOLDOWN_HOURS = 48

  private def maybeRepair(dsInfo: DsInfo, drift: Int, remoteTotal: Int): Unit = {
    val cfg = orgContext.narthexConfig
    if (!cfg.sourceCheckAutoRepair) return
    if (math.abs(drift) < cfg.sourceCheckRepairThreshold) return
    // A remote total of 0 is depublication territory — never auto-full-harvest
    // on that signal; the normal harvest cycle's completeListSize=0 attestation
    // path owns depublication.
    if (remoteTotal == 0) return
    // Cooldown: a set whose drift survives a repair (endpoint counts
    // differently, stale completeListSize, defect we don't model yet) must
    // not be full-harvested every day.
    val lastRepair = dsInfo.getLiteralProp(sourceCheckLastRepairTime).map(Temporal.stringToTime)
    if (lastRepair.exists(_.isAfter(new org.joda.time.DateTime().minusHours(REPAIR_COOLDOWN_HOURS)))) {
      logger.warn(s"SourceCount auto-repair for ${dsInfo.spec} skipped: last repair within ${REPAIR_COOLDOWN_HOURS}h and drift persists ($drift) — needs a look")
      return
    }
    dsInfo.setSingularLiteralProps(sourceCheckLastRepairTime -> Temporal.timeToString(new org.joda.time.DateTime()))
    logger.warn(s"SourceCount auto-repair: queueing full harvest for ${dsInfo.spec} (drift=$drift)")
    import dataset.DatasetActor.{FromScratchIncremental, StartHarvest}
    import organization.OrgActor.EnqueueOperation
    orgContext.orgActor ! EnqueueOperation(dsInfo.spec, StartHarvest(FromScratchIncremental, trigger = "source-check"), "periodic")
  }
}
