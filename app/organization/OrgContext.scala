//===========================================================================
//    Copyright 2014 Delving B.V.
//
//    Licensed under the Apache License, Version 2.0 (the "License");
//    you may not use this file except in compliance with the License.
//    You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
//    Unless required by applicable law or agreed to in writing, software
//    distributed under the License is distributed on an "AS IS" BASIS,
//    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//    See the License for the specific language governing permissions and
//    limitations under the License.
//===========================================================================

package organization

import java.io.File
import java.time.{LocalTime, ZoneId, ZonedDateTime}
import javax.inject._

import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.actor.Props
import dataset.DsInfo.withDsInfo
import dataset.SipRepo.{AvailableSip, SIP_EXTENSION}
import dataset._
import init.NarthexConfig
import mapping._
import organization.OrgActor.DatasetsCountCategories
import organization.WorkflowPersistenceActor
import play.api.cache.SyncCacheApi
import play.api.libs.ws.WSClient
import play.api.Logging
import services.{IndexStatsService, MailService, RecordRegistry, TrendTrackingService}
import triplestore.GraphProperties._
import triplestore.TripleStore

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.language.postfixOps

/**
  * Best way to describe this class is that it has functioned as some means of passing around globals.
  * It is obvious that we need to remove this class and only DI the specific values that a component requires,
  * allowing this class to be deleted
  */
 @Singleton
class OrgContext @Inject() (
  val narthexConfig: NarthexConfig,
  val cacheApi: SyncCacheApi,
  val wsApi: WSClient,
  val mailService: MailService,
  val actorSystem: ActorSystem,
  indexStatsService: IndexStatsService,
  val recordRegistry: RecordRegistry
) (ec: ExecutionContext, implicit val ts: TripleStore) extends Logging {

  val root = narthexConfig.narthexDataDir
  val orgRoot = new File(root, narthexConfig.orgId)
  val factoryDir = new File(orgRoot, "factory")
  val categoriesDir = new File(orgRoot, "categories")
  val datasetsDir = new File(orgRoot, "datasets")
  val rawDir = new File(orgRoot, "raw")
  val sipsDir = new File(orgRoot, "sips")
  val trendsSummaryFile = new File(orgRoot, "trends_summary.json")
  // Persistent job queue (queue.db): queued operations survive restarts
  lazy val jobQueue = new services.JobQueue(new File(orgRoot, "queue.db"))
  val crunchWhiteSpace = narthexConfig.crunchWhiteSpace
  val semaphore = new Semaphore(narthexConfig.concurrencyLimit)
  val saveSemaphore = new Semaphore(narthexConfig.concurrencyLimit)

  lazy val categoriesRepo = new CategoriesRepo(categoriesDir, narthexConfig.orgId)
  lazy val sipFactory = new SipFactory(factoryDir, orgRoot, narthexConfig.rdfBaseUrl, wsApi, narthexConfig.orgId)

  orgRoot.mkdirs()
  factoryDir.mkdirs()
  datasetsDir.mkdir()
  rawDir.mkdirs()
  sipsDir.mkdirs()

  // Schedule daily trend snapshot if enabled
  if (narthexConfig.enableTrendTracking) {
    scheduleDailyTrendSnapshot()
  }

  /**
   * Schedule daily trend snapshot at configured hour.
   * Captures record counts from all datasets and Hub3 index.
   */
  private def scheduleDailyTrendSnapshot(): Unit = {
    val minute = narthexConfig.trendAggregationMinute

    val zone = ZoneId.of("UTC")
    val now = ZonedDateTime.now(zone)
    val targetTime = now.toLocalDate.atTime(LocalTime.of(0, minute)).atZone(zone)
    val nextRun = if (now.isAfter(targetTime)) targetTime.plusDays(1) else targetTime
    val initialDelayMillis = java.time.Duration.between(now, nextRun).toMillis

    logger.info(s"Scheduling daily trend aggregation at 00:$minute UTC. First run in ${initialDelayMillis / 3600000} hours.")

    actorSystem.scheduler.scheduleWithFixedDelay(
      initialDelayMillis.millis,
      24.hours
    )(new Runnable {
      override def run(): Unit = runDailyTrendAggregation()
    })(actorSystem.dispatcher)

    // Bootstrap: on fresh installs (or when trend tracking has been off for a
    // while) trends_summary.json is missing or empty, so the UI falls back to
    // per-dataset logs that only have a handful of snapshots and shows 0 deltas
    // for every dataset. Run one aggregation asynchronously at startup so the
    // UI has usable data immediately instead of waiting until 00:30 tomorrow.
    if (!trendsSummaryFile.exists() || trendsSummaryFile.length() == 0) {
      logger.info("No trends summary found — scheduling bootstrap aggregation")
      actorSystem.scheduler.scheduleOnce(10.seconds) {
        runBootstrapTrendAggregation()
      }(actorSystem.dispatcher)
    }
  }

  private def runBootstrapTrendAggregation(): Unit = {
    val today = org.joda.time.LocalDate.now(org.joda.time.DateTimeZone.UTC).toString("yyyy-MM-dd")
    logger.info(s"Running bootstrap trend aggregation for $today (UTC)")
    runTrendAggregation(today)
  }

  private def runDailyTrendAggregation(): Unit = {
    val yesterday = org.joda.time.LocalDate.now(org.joda.time.DateTimeZone.UTC).minusDays(1).toString("yyyy-MM-dd")
    logger.info(s"Running daily trend aggregation for $yesterday (UTC)...")
    runTrendAggregation(yesterday)
  }

  private def runTrendAggregation(date: String): Unit = {
    val result = for {
      datasets <- DsInfo.listDsInfo(this)
      hub3 <- indexStatsService.fetchHub3IndexCounts()
    } yield {
      if (!hub3.reachable) {
        logger.warn(s"Skipping trend aggregation for $date: Hub3 unreachable. Indexed counts would be corrupted.")
      } else {
        var aggregated = 0
        val specs = scala.collection.mutable.ListBuffer[String]()

        // Stamp reconciliation snapshots into the day being reconciled, not
        // "now": the cron reconciles YESTERDAY at 00:30 today, and a "now"
        // timestamp made indexed deltas lag source/valid by exactly one day.
        val endOfTarget = org.joda.time.LocalDate.parse(date)
          .toDateTime(new org.joda.time.LocalTime(23, 59, 59, 999), org.joda.time.DateTimeZone.UTC)
        val reconciliationTs =
          if (endOfTarget.isAfterNow) org.joda.time.DateTime.now(org.joda.time.DateTimeZone.UTC)
          else endOfTarget

        datasets.foreach { dsInfo =>
          try {
            val ctx = datasetContext(dsInfo.spec)
            val trendsLog = ctx.trendsLog
            val dailyLog = ctx.trendsDailyLog

            // None = spec missing from a possibly-truncated facet: skip
            // reconciliation rather than record a fake full de-index.
            val hub3CountOpt = hub3.countFor(dsInfo.spec)
            if (hub3CountOpt.isEmpty) {
              logger.warn(s"Hub3 facet has no count for ${dsInfo.spec} and may be truncated — skipping indexed reconciliation")
            }

            val lastSnapshot = TrendTrackingService.getLastSnapshot(trendsLog)
            for {
              hub3Count <- hub3CountOpt
              last <- lastSnapshot
              // Record a "daily" reconciliation snapshot whenever our stored indexed
              // count disagrees with what Hub3 reports. A Hub3 count of 0 is meaningful
              // when reachable=true (dataset fully de-indexed) so it is not guarded away.
              if last.indexedRecords != hub3Count
            } {
              TrendTrackingService.captureEventSnapshot(
                trendsLog, "daily",
                sourceRecords = last.sourceRecords,
                acquiredRecords = last.acquiredRecords,
                deletedRecords = last.deletedRecords,
                validRecords = last.validRecords,
                invalidRecords = last.invalidRecords,
                indexedRecords = hub3Count,
                timestampOverride = Some(reconciliationTs)
              )
            }

            // Backfill any dates missed by downtime/skipped crons, then the target.
            TrendTrackingService.aggregateThrough(trendsLog, dailyLog, date)

            // Registry health check: one greppable line per dataset per night.
            // seen == hub3 (with nothing pending) means the registry mirror is
            // accurate; a persistent diff means it is missing or over-dropping
            // records and the revision sweep is still doing the real work.
            if (narthexConfig.registryEnabled) {
              for {
                hub3Count <- hub3CountOpt
                seen <- recordRegistry.seenCountIfExists(dsInfo.spec)
              } {
                val p = recordRegistry.pendingCounts(dsInfo.spec)
                val msg = s"Registry health ${dsInfo.spec}: seen=$seen hub3=$hub3Count diff=${seen - hub3Count} pendingIndex=${p.pendingIndex} pendingDrops=${p.pendingDrops}"
                if (seen != hub3Count && p.pendingIndex == 0 && p.pendingDrops == 0) logger.warn(s"$msg — mismatch with nothing pending")
                else logger.info(msg)
              }
            }

            specs += dsInfo.spec
            aggregated += 1
          } catch {
            case e: Exception =>
              logger.warn(s"Failed to aggregate trends for ${dsInfo.spec}: ${e.getMessage}")
          }
        }

        try {
          TrendTrackingService.generateTrendsSummaryFromDaily(trendsSummaryFile, datasetsDir, specs.toList)
        } catch {
          case e: Exception =>
            logger.warn(s"Failed to generate trends summary: ${e.getMessage}")
        }

        logger.info(s"Trend aggregation for $date complete: $aggregated/${datasets.size} datasets")
      }
    }

    val _ = result.recover {
      case e: Exception =>
        logger.error(s"Trend aggregation for $date failed: ${e.getMessage}", e)
    }
  }

  def createDsInfo(spec: String, characterString: String, prefix: String) = {
    val character = DsInfo.getCharacter(characterString).get
    DsInfo.createDsInfo(spec, character, prefix, this)
  }

  def datasetContext(spec: String): DatasetContext = withDsInfo(spec, this)(dsInfo => new DatasetContext(this, dsInfo))

  def vocabMappingStore(specA: String, specB: String): VocabMappingStore = {
    val futureStore = for {
      infoA <- VocabInfo.freshVocabInfo(specA, this)
      infoB <- VocabInfo.freshVocabInfo(specB, this)
    } yield (infoA, infoB) match {
        case (Some(a), Some(b)) => new VocabMappingStore(a, b, this)
        case _ => throw new RuntimeException(s"No vocabulary mapping found for $specA, $specB")
      }
    Await.result(futureStore, 15.seconds)
  }

  def termMappingStore(spec: String): TermMappingStore = {
    withDsInfo(spec, this)(dsInfo => new TermMappingStore(dsInfo, this, this.wsApi))
  }

  // Group by dataset and keep newest per group so the sip-app listing never shows
  // two SIPs for the same dataset (timestamped Narthex-generated form vs bare
  // direct-drop / legacy SIP-Creator upload form).
  def availableSips: Seq[AvailableSip] = sipsDir.listFiles.toSeq
    .filter(_.getName.endsWith(SIP_EXTENSION))
    .map(AvailableSip)
    .groupBy(_.datasetName)
    .values
    .map(_.maxBy(_.dateTime.getMillis))
    .toSeq
    .sortBy(_.dateTime.getMillis)
    .reverse

  def uploadedSips: Future[Seq[Sip]] = {
    DsInfo.listDsInfo(this).map { list =>
      list.flatMap { dsi =>
        val datasetContext = new DatasetContext(this, dsi)
        datasetContext.sipRepo.latestSipOpt
      }
    }
  }

  lazy val orgActorInst = new OrgActor(this, actorSystem)

  lazy val orgActorRef: ActorRef = actorSystem.actorOf(Props(orgActorInst), narthexConfig.orgId)

  def orgActor: ActorRef = orgActorRef

  // Workflow persistence actor
  lazy val workflowActorInst = new WorkflowPersistenceActor()

  lazy val workflowActorRef: ActorRef = actorSystem.actorOf(
    Props(workflowActorInst),
    s"${narthexConfig.orgId}-workflow"
  )

  def workflowActor: ActorRef = workflowActorRef

  def startCategoryCounts() = {
    val catDatasets = DsInfo.listDsInfo(this).map(_.filter(_.getBooleanProp(categoriesInclude)))
    catDatasets.map { dsList =>
      orgActorRef ! DatasetsCountCategories(dsList.map(_.spec))
    }
  }

  def appConfig: NarthexConfig = narthexConfig

}
