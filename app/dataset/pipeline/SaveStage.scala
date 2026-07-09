//===========================================================================
//    Copyright 2026 Delving B.V.
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

package dataset.pipeline

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

import org.joda.time.DateTime
import play.api.Logger
import play.api.libs.json.Json

import dataset.DatasetActor.Scheduled
import dataset.DsInfo.Hub3BulkApiException
import dataset.PipelinePlan
import dataset.ProcessedRepo.GraphChunk
import services.ActivityLogger
import services.ProgressReporter.InterruptedException
import services.ProgressReporter.ProgressState
import services.Temporal._

/**
 * Save + reconcile as one synchronous PipelineStage (Phase A3c-3: the last
 * stage extracted from an actor — GraphSaver's Future/self-message chunk
 * choreography collapses into a plain while loop with Awaits, the shape
 * that transliterates to Go).
 *
 * Save phase: optional revision increment, then read the processed output
 * in chunks and POST each to the Hub3 bulk API, confirming indexed ids in
 * the registry per acked chunk.
 *
 * Reconcile phase (strictly after every chunk is acked — a truncated save
 * must never commit destructive tombstones): full-run missing sweep,
 * registry-driven drop_records batches, optional clear_orphans revision
 * sweep, run completion.
 *
 * The two phases stay in one stage for now (their registry stage rows
 * 'save'/'reconcile' keep bracketing them separately); splitting into two
 * Stage values later is mechanical.
 */
case class SaveStage(
  scheduledOpt: Option[Scheduled],
  registryRunId: Option[Long],
  saveModeOpt: Option[PipelinePlan.SaveMode]
) extends PipelineStage {

  private val logger = Logger(getClass)

  val id: String = PipelinePlan.STAGE_SAVE
  val progressState: ProgressState = ProgressState.SAVING

  private val chunkPatience = 15.minutes  // bulkApiUpdate retries internally (6x backoff)
  private val dropBatchSize = 10000

  def run(ctx: StageContext): StageResult = {
    val datasetContext = ctx.datasetContext
    val orgContext = ctx.orgContext
    val dsInfo = datasetContext.dsInfo
    val spec = ctx.spec
    val registry = orgContext.recordRegistry
    val registryEnabled = orgContext.narthexConfig.registryEnabled
    val keepRevisionSweep = orgContext.narthexConfig.registryKeepRevisionSweep
    implicit val ec: scala.concurrent.ExecutionContext = scala.concurrent.ExecutionContext.global

    val saveTime = new DateTime()
    val startSave = timeToString(saveTime)
    val isScheduled = scheduledOpt.isDefined
    val isExplicitFileSave = scheduledOpt.isDefined
    val isIncremental = isScheduled && scheduledOpt.head.modifiedAfter.isDefined

    scheduledOpt.map(_.file).filterNot { file =>
      file.getName.endsWith(".xml") || file.getName.endsWith(".xml.zst")
    }.foreach { file =>
      return StageFailed(s"Save received non-processed file ${file.getName}; expected .xml or .xml.zst")
    }

    // First-class save runs (A3c-3 follow-up): the dispatcher always
    // provides the run — the chain's run for chained saves, a fresh
    // saveOnly run for manual saves. No adoption.
    val registryRunIdOpt = registryRunId

    // The save mode is decided ONCE — by the planner for chained saves, by
    // the same pure function here for legacy callers — and makes the sweep
    // and delta filtering mutually exclusive by construction: the revision
    // sweep (increment_revision + clear_orphans) deletes everything in Hub3
    // not re-indexed at the new revision, so filtering a swept save would
    // mass-delete every unchanged record.
    val saveMode = saveModeOpt.getOrElse(
      PipelinePlan.saveModeFor(isIncremental, registryEnabled, keepRevisionSweep))
    val willRevisionSweep = saveMode == PipelinePlan.FullSendWithSweep

    val expectedIndexIds: Set[String] =
      if (registryEnabled && !willRevisionSweep) registry.pendingIndexBatch(spec, Int.MaxValue).map(_._1).toSet
      else Set.empty[String]
    val includeLocalIdsOpt =
      if (registryEnabled && !willRevisionSweep && (registryRunIdOpt.isDefined || expectedIndexIds.nonEmpty)) Some(expectedIndexIds)
      else None
    val registryIndexFiltering = includeLocalIdsOpt.isDefined
    if (registryEnabled) {
      if (willRevisionSweep)
        logger.info(s"Registry: delta filtering disabled for $spec — revision sweep (clear_orphans) requires a full send")
      else if (registryIndexFiltering)
        logger.info(s"Registry: ${expectedIndexIds.size} pending index record(s) will be sent for $spec")
      else
        logger.warn(s"Registry: no run/pending rows for $spec; falling back to full processed-file send")
    }

    registryRunIdOpt.foreach { runId =>
      registry.stageStarted(spec, runId, "save", Some(Json.stringify(Json.obj(
        "saveMode" -> saveMode.name,
        "incremental" -> isIncremental,
        "deltaFiltering" -> registryIndexFiltering,
        "pending" -> expectedIndexIds.size,
        "file" -> scheduledOpt.map(_.file.getName)
      ))))
    }

    def localIdsInChunk(chunk: GraphChunk): Seq[String] =
      chunk.dataset.listNames().asScala.toList.flatMap { graphUri =>
        scala.util.Try(chunk.dsInfo.extractSpecIdFromGraphName(graphUri)).toOption.map(_._2)
      }

    def processedInvalidCount: Int =
      dsInfo.getLiteralProp(triplestore.GraphProperties.processedInvalid)
        .flatMap(v => scala.util.Try(v.toInt).toOption).getOrElse(0)

    val reader = datasetContext.processedRepo.createGraphReaderXML(
      scheduledOpt.map(_.file), saveTime, ctx.progressReporter, includeLocalIdsOpt)

    var chunksSaved = 0
    var recordsSent = 0
    var sentIds = Set.empty[String]

    try {
      if (willRevisionSweep) {
        // Wait for increment_revision BEFORE sending records — otherwise the
        // first batch may be indexed with the OLD revision and then deleted
        // as an orphan when clear_orphans runs.
        logger.info(s"Incrementing dataset revision for $spec (keepRevisionSweep=$keepRevisionSweep)")
        Await.result(dsInfo.updateDatasetRevision(), 2.minutes)
      }

      // --- Save phase: sequential chunk loop (was the self-message pump) ---
      var chunkOpt = { ctx.progressReporter.checkInterrupt(); reader.readChunkOpt }
      while (chunkOpt.isDefined) {
        val chunk = chunkOpt.get
        ctx.progressReporter.checkInterrupt()
        ctx.progressReporter.sendValue()
        Await.result(dsInfo.bulkApiUpdate(chunk.bulkAPIQ(orgContext.appConfig.orgId)), chunkPatience)
        val ids = if (registryEnabled) localIdsInChunk(chunk) else Seq.empty
        if (registryEnabled && ids.nonEmpty) {
          registryRunIdOpt.foreach { runId =>
            scala.util.Try(registry.confirmIndexedByIds(spec, ids, runId))
              .recover { case ex: Throwable =>
                logger.warn(s"Registry: confirmIndexedByIds failed for ${ids.size} ids: ${ex.getMessage}")
              }
          }
        }
        chunksSaved += 1
        recordsSent += ids.size
        sentIds ++= ids
        ctx.progressReporter.checkInterrupt()
        chunkOpt = reader.readChunkOpt
      }

      // Compare the DISTINCT ids actually sent against the pending set: a
      // raw count can double-count an id present in two processed files and
      // mask a genuinely missing record.
      val missingPending: Set[String] =
        if (registryIndexFiltering) expectedIndexIds -- sentIds else Set.empty

      if (isExplicitFileSave && chunksSaved == 0 &&
          (!registryEnabled || (registryIndexFiltering && expectedIndexIds.nonEmpty && !isIncremental))) {
        return StageFailed("Explicit processed-file save completed with zero graph chunks")
      }
      if (registryIndexFiltering && !isIncremental && missingPending.nonEmpty) {
        return StageFailed(
          s"Registry save for $spec sent only ${sentIds.size} of ${expectedIndexIds.size} pending index records — ${missingPending.size} missing from processed output")
      }
      // A file-scoped incremental save can legitimately lack pending
      // leftovers from earlier runs (failed save, record turned invalid).
      // Not fatal: they stay pending and flush on the next full save.
      if (registryIndexFiltering && isIncremental && missingPending.nonEmpty)
        logger.warn(s"Registry: ${missingPending.size} pending record(s) not in this incremental file for $spec; they remain pending for the next full save")

      registryRunIdOpt.foreach { runId =>
        registry.stageCompleted(spec, runId, "save", Some(Json.stringify(
          Json.obj("chunks" -> chunksSaved, "sent" -> sentIds.size))))
        registry.stageStarted(spec, runId, "reconcile", None)
      }

      // --- Reconcile phase: strictly after every chunk was acked by Hub3 —
      // a failed or truncated save must never commit destructive tombstones,
      // or the next innocuous incremental save would mass-drop live records.
      // Incremental saves skip the sweep — they see only the delta and
      // cannot reason about the absent set.
      if (registryEnabled && !isIncremental && processedInvalidCount == 0) {
        // The sweep must run against the run that PRODUCED the processed
        // output (the run whose process stage stamped records seen). For a
        // chained save that is this run; for a manual saveOnly run it is
        // the latest completed process-staged full run. Sweeping against a
        // run that stamped nothing marks every record missing (observed:
        // 2808 live records tombstoned).
        val sweepRunIdOpt = registryRunIdOpt
          .filter(id => registry.runStages(spec, id).exists { case (st, status) => st == "process" && status == "completed" })
          .orElse(registry.latestCompletedFullRunId(spec))
        sweepRunIdOpt match {
          case Some(sweepRunId) =>
            val swept = registry.markMissingForFullRun(spec, sweepRunId)
            if (swept > 0) logger.info(s"Registry: marked $swept records missing for producing run $sweepRunId ($spec)")
          case None =>
            logger.info(s"Registry: no producing run for $spec — skipping missing-record sweep")
        }
      } else if (registryEnabled && !isIncremental) {
        logger.warn(s"Registry: skipping missing-record sweep for $spec because processedInvalid=$processedInvalidCount")
      }

      // Registry-driven drops: explicit drop_records for every record the
      // registry knows is deleted (OAI tombstones + full-run sweep misses).
      // Non-fatal: all chunks are already in Hub3, and unconfirmed drops
      // stay pending and are retried on the next save.
      if (registryEnabled) {
        try {
          var drops = registry.pendingDropBatch(spec, dropBatchSize)
          while (drops.nonEmpty) {
            logger.info(s"Registry: emitting drop_records for ${drops.size} ids ($spec)")
            Await.result(dsInfo.dropRecordsByIds(drops), chunkPatience)
            registry.confirmDropped(spec, drops)
            drops = registry.pendingDropBatch(spec, dropBatchSize)
          }
        } catch {
          case ex: InterruptedException => throw ex
          case ex: Exception =>
            logger.warn(s"Registry: drop_records failed, will retry next save: ${ex.getMessage}")
        }
      }

      if (willRevisionSweep) {
        // clear_orphans failure is non-fatal (was fire-and-forget in the
        // actor); stale orphans are re-swept on the next full save.
        scala.util.Try(Await.result(dsInfo.removeNaveOrphans(startSave), 2.minutes))
          .recover { case ex: Throwable =>
            logger.warn(s"Revision sweep clear_orphans failed for $spec: ${ex.getMessage}")
          }
        logger.info(s"Revision sweep: clear_orphans emitted (keepRevisionSweep=$keepRevisionSweep)")
      }

      // Close the run. The registry computes the real per-run diff
      // (added/changed/deleted) internally; recordsSent answers "how much did
      // this save actually push to Hub3". Run bookkeeping is pipeline
      // infrastructure and runs regardless of registryEnabled.
      registryRunIdOpt.foreach { runId =>
        registry.stageCompleted(spec, runId, "reconcile", Some(Json.stringify(
          Json.obj("revisionSweep" -> willRevisionSweep))))
        scala.util.Try(registry.completeRun(spec, runId, recordsSent))
          .recover { case ex: Throwable =>
            logger.warn(s"Registry: completeRun failed for run $runId: ${ex.getMessage}")
          }
      }

      logger.info(s"All graphs saved for $spec ($recordsSent records, $chunksSaved chunks)")
      GraphsSaved(recordsSent)
    } catch {
      case _: InterruptedException =>
        StageFailed("Save operation interrupted by user")
      case hub3Ex: Hub3BulkApiException =>
        logger.error(s"Hub3 rejection for $spec: ${hub3Ex.message}")
        logger.error(s"  Hub3 error details: ${hub3Ex.hub3ErrorMessage.getOrElse("(no parsed message)")}")
        logger.error(s"  Affected records: ${hub3Ex.affectedRecordIds.mkString(", ")}")
        ActivityLogger.logBulkApiError(
          activityLog = datasetContext.activityLog,
          operation = "SAVE",
          statusCode = hub3Ex.statusCode,
          errorMessage = hub3Ex.message,
          fullResponse = hub3Ex.hub3FullResponse,
          affectedRecordIds = hub3Ex.affectedRecordIds
        )
        StageFailed(hub3Ex.message)
    } finally {
      reader.close()
    }
  }
}
