package services

import organization.OrgContext
import play.api.Logging

import scala.concurrent.{ExecutionContext, Future}

/**
 * Registry <-> index reconciliation. index_verify reports COUNTS only; when
 * the numbers disagree, only a full id comparison can say WHICH records are
 * missing. Hub3 streams every indexed hubID for the spec
 * (/api/admin/index-ids), we diff against the registry's live records, and
 * reset the sent-state of the missing ones so the next save re-sends exactly
 * those (observed: 3 records of enb-342-beeldmateriaal silently never landed
 * while the registry said "all sent" — unrecoverable without this diff).
 */
class IndexReconcileService(orgContext: OrgContext)(implicit ec: ExecutionContext) extends Logging {

  case class ReconcileResult(spec: String, indexed: Int, expected: Int,
                             missingReset: Int, orphansInIndex: Int, missingSample: Seq[String])

  /** Fetch indexed ids, diff, reset sent-state for missing. Does NOT save —
    * the caller decides (manual action returns the result; the auto path
    * triggers a save when something was reset). */
  def reconcile(spec: String): Future[ReconcileResult] = {
    val orgId = orgContext.appConfig.orgId
    val url = s"${orgContext.appConfig.naveApiUrl}/api/admin/index-ids/$orgId/$spec"
    orgContext.wsApi.url(url)
      .withRequestTimeout(scala.concurrent.duration.DurationInt(10).minutes)
      .get()
      .map { response =>
        if (response.status != 200)
          throw new RuntimeException(s"index-ids fetch failed (${response.status}) for $spec")
        val prefix = s"${orgId}_${spec}_"
        val indexedLocalIds: Set[String] = response.body.linesIterator
          .filter(_.nonEmpty)
          .map(id => if (id.startsWith(prefix)) id.substring(prefix.length) else id)
          .toSet
        val registryIds = orgContext.recordRegistry.listSeenLocalIds(spec)
        val registrySet = registryIds.toSet
        val missing = registryIds.filterNot(indexedLocalIds.contains)
        // Orphans: indexed docs without a live registry record — deleted or
        // depublished records whose drop never reached (or never left for)
        // the index. Both directions of the same defect class.
        val orphanIds = (indexedLocalIds -- registrySet).toSeq
        val reset = orgContext.recordRegistry.resetSentStateForIds(spec, missing)
        if (missing.nonEmpty)
          logger.warn(s"Index reconcile $spec: ${missing.size} record(s) in registry but not indexed — sent-state reset ($reset). Sample: ${missing.take(3).mkString(", ")}")
        (spec, registryIds.size, indexedLocalIds.size, missing, reset, orphanIds)
      }
      .flatMap { case (spec, expected, indexed, missing, reset, orphanIds) =>
        val dropF =
          if (orphanIds.isEmpty) Future.successful(())
          else if (expected == 0) {
            // An empty registry against a populated index is depublication
            // territory (or a registry wiped behind our back) — dropping the
            // whole index from a reconcile would be destruction by accident.
            logger.warn(s"Index reconcile $spec: ${orphanIds.size} indexed doc(s) but registry holds NO live records — refusing orphan drop; use depublication or registry backfill")
            Future.successful(())
          }
          else {
            logger.warn(s"Index reconcile $spec: dropping ${orphanIds.size} orphan doc(s) from the index. Sample: ${orphanIds.take(3).mkString(", ")}")
            dataset.DsInfo.getDsInfo(spec, orgContext).dropRecordsByIds(orphanIds)
          }
        dropF.map(_ => ReconcileResult(spec, indexed, expected, reset, orphanIds.size, missing.take(10).toSeq))
      }
  }
}
