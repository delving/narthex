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

import java.io.File

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._
import scala.util.control.NonFatal

import org.joda.time.DateTime
import play.api.Logger
import play.api.libs.json.Json

import dataset.DatasetActor.Scheduled
import dataset.DsInfo.Hub3BulkApiException
import dataset.PipelinePlan
import dataset.ProcessedRepo.GraphChunk
import services.{ActivityLogger, IndexServiceApi, IndexJobRequest, IndexJobStatus, IndexServiceRejected, IndexServiceUnreachable, ProgressReporter}
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
    if (ctx.datasetContext.dsInfo.getBooleanProp(triplestore.GraphProperties.externalIndex)) return runIndexService(ctx)
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
        // A pending record absent from the FULL processed output no longer
        // exists as a publishable record — typically valid->invalid between
        // runs. The registry kept it 'seen', inflating the expected count
        // forever, and failing here blocked the very reconcile that could
        // clean it up (stadsarchief-breda: 2 ghosts, hourly heal loop).
        // Tombstone them so the drop flow below removes them from the index.
        // A large gap still fails: that smells like truncated output, and
        // mass-tombstoning on it would sweep the whole index.
        if (missingPending.size <= 1000) {
          val stamped = registryRunIdOpt.map(runId =>
            registry.stampTombstones(spec, missingPending.toSeq, runId)).getOrElse(Seq.empty)
          logger.warn(s"Registry save for $spec: ${missingPending.size} pending record(s) missing from full processed output — tombstoned (${stamped.size}) as no longer publishable")
        } else return StageFailed(
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
      } else if (registryEnabled) {
        // Registry-owned mode has no clear_orphans, so nothing would report
        // indexing outcomes back. Ask Hub3 to verify the spec's total count
        // against the registry's distinct record count and notify the
        // webhook (per-record failures included) — the detection half of
        // the self-healing loop.
        registry.countIfExists(spec, "seen").filter(_ > 0).foreach { expected =>
          scala.util.Try(Await.result(dsInfo.requestIndexVerify(expected), 2.minutes))
            .recover { case ex: Throwable =>
              logger.warn(s"index_verify request failed for $spec: ${ex.getMessage}")
            }
          logger.info(s"Registry: index_verify requested for $spec (expected=$expected)")
        }
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

  /**
   * Save for an externalIndex dataset: the index service owns the index, so the stage
   * hands it one job naming the processed files and waits for it. No hub3
   * bulk, revision sweep, reconcile or registry Hub3-state calls; only the
   * run bookkeeping stays so narthex's UI keeps working.
   */
  private[pipeline] def runIndexService(ctx: StageContext,
                                  sleep: FiniteDuration => Unit = d => Thread.sleep(d.toMillis),
                                  now: () => Long = () => System.currentTimeMillis()): StageResult = {
    val orgContext = ctx.orgContext
    val datasetContext = ctx.datasetContext
    val dsInfo = datasetContext.dsInfo
    val spec = ctx.spec
    val org = orgContext.narthexConfig.orgId
    val registry = orgContext.recordRegistry

    if (orgContext.indexServiceApi.isEmpty)
      return StageFailed(s"externalIndex is set but indexService.url / indexService.token are not configured for org $org")
    val api = orgContext.indexServiceApi.get
    SaveStage.ensurePublished(ctx, sleep).foreach(f => return f)

    val notProcessed = scheduledOpt.map(_.file).filterNot(f => f.getName.endsWith(".xml") || f.getName.endsWith(".xml.zst"))
    if (notProcessed.isDefined)
      return StageFailed(s"Save received non-processed file ${notProcessed.get.getName}; expected .xml or .xml.zst")

    val incremental = saveModeOpt.map(_ == PipelinePlan.IncrementalFileSend)
      .getOrElse(scheduledOpt.exists(_.modifiedAfter.isDefined))
    val files =
      if (incremental) scheduledOpt.map(_.file).toList
      else datasetContext.processedRepo.listXmlFiles
    if (files.isEmpty) return StageFailed("no processed files")
    val deletedIdsFile = new File(datasetContext.sourceDir, "deleted.ids")

    val paths = files.map(SaveStage.relativeToHome(orgContext.root, org, _))
    val deletedIdsPath =
      if (deletedIdsFile.exists()) Some(SaveStage.relativeToHome(orgContext.root, org, deletedIdsFile)) else None
    val outside = (paths ++ deletedIdsPath).collectFirst { case Left(err) => err }
    if (outside.isDefined) return StageFailed(s"index service save: ${outside.get}")

    val request = SaveStage.jobRequest(dsInfo, if (incremental) "incremental" else "full",
      paths.collect { case Right(p) => p }, deletedIdsPath.flatMap(_.toOption))

    registryRunId.foreach { runId =>
      registry.stageStarted(spec, runId, "save", Some(Json.stringify(Json.obj(
        "indexService" -> true, "mode" -> request.mode, "files" -> request.files.size))))
    }

    val (jobIdOpt, result) = SaveStage.postAndPoll(api, org, spec, request,
      orgContext.narthexConfig.indexServicePollSeconds.seconds, ctx.progressReporter, sleep, now,
      orgContext.narthexConfig.indexServiceStuckMinutes.minutes)
    result match {
      case GraphsSaved(n) =>
        registryRunId.foreach { runId =>
          registry.stageCompleted(spec, runId, "save", Some(Json.stringify(Json.obj("indexJob" -> jobIdOpt, "sent" -> n))))
          registry.stageStarted(spec, runId, "reconcile", None)
          registry.stageCompleted(spec, runId, "reconcile", Some(Json.stringify(Json.obj("indexService" -> true))))
          scala.util.Try(registry.completeRun(spec, runId, n))
            .recover { case ex: Throwable => logger.warn(s"Registry: completeRun failed for run $runId: ${ex.getMessage}") }
        }
        logger.info(s"index service job ${jobIdOpt.getOrElse("?")} done for $spec ($n records)")
      case _ =>
    }
    result
  }
}

object SaveStage {

  private val logger = Logger(getClass)

  /**
   * A dataset depublished by delete / noRecordsMatch only comes back through a publish; the index service's
   * is a no-op when already published. A DISABLED dataset stays hidden. Failure fails the stage.
   */
  private[pipeline] def ensurePublished(ctx: StageContext, sleep: FiniteDuration => Unit): Option[StageFailed] = {
    val dsInfo = ctx.datasetContext.dsInfo
    if (dsInfo.isDisabled) None
    else services.IndexVisibility.set(ctx.orgContext.indexServiceApi.get, ctx.orgContext.narthexConfig.orgId, ctx.spec,
      s"narthex@$hostname", published = true, ms => sleep(ms.millis)) match {
      case Right(_) => None
      case Left(err) => Some(StageFailed(services.IndexVisibility.describe(err, published = true)))
    }
  }

  /** A failed job's stage message; mass_delete names its remedy. */
  private[pipeline] def failedMessage(jobId: String, org: String, spec: String, error: Option[String]): String =
    error match {
      case Some("mass_delete") =>
        s"index service job $jobId failed: mass_delete: this full delivery would drop more than 10% of $spec's live records. " +
          s"If the dataset really shrank, staff re-post the same job to the index service: " +
          "POST …/jobs with \"force\": true"
      case other => s"index service job $jobId failed: ${other.getOrElse("(no error given)")}"
    }

  /** One Await per index service call; IndexServiceClient retries internally for a few seconds. */
  private[pipeline] val indexServicePatience = 2.minutes
  private[pipeline] val unreachableLimit = 10.minutes

  private[dataset] lazy val hostname: String =
    scala.util.Try(java.net.InetAddress.getLocalHost.getHostName).getOrElse("unknown")

  private[pipeline] def rejectedMessage(rej: IndexServiceRejected, org: String): String = {
    val hint = rej.code match {
      case "forbidden" | "unauthorized" => s" — check indexService.token for org $org"
      case _ => ""
    }
    s"index service rejected the request (HTTP ${rej.status} ${rej.code}): ${rej.message}$hint"
  }

  /** A path relative to narthex home, which the index service resolves under its
    * configured root; it only accepts paths under <home>/<org>/. */
  private[pipeline] def relativeToHome(homeDir: File, org: String, f: File): Either[String, String] = {
    val home = homeDir.toPath.toAbsolutePath.normalize
    val rel = home.relativize(f.toPath.toAbsolutePath.normalize)
    if (rel.startsWith(org) && rel.getNameCount > 1) Right(rel.toString)
    else Left(s"${f.getPath} is not under ${home.resolve(org)}")
  }

  private[pipeline] def jobRequest(dsInfo: dataset.DsInfo, mode: String, files: Seq[String], deletedIds: Option[String]): IndexJobRequest =
    IndexJobRequest(
      mode = mode,
      files = files,
      deletedIds = deletedIds,
      docType = dsInfo.getLiteralProp(triplestore.GraphProperties.datasetType).getOrElse("narthex_record"),
      docTags = dsInfo.getLiteralProp(triplestore.GraphProperties.datasetTags).getOrElse(""),
      agent = s"narthex@$hostname",
      force = false)

  /** Post a job and poll it to the end: (jobId once posted, result). An interrupt fails it. */
  private[pipeline] def postAndPoll(api: IndexServiceApi, org: String, spec: String, request: IndexJobRequest,
                                    interval: FiniteDuration, reporter: ProgressReporter,
                                    sleep: FiniteDuration => Unit, now: () => Long,
                                    stuckLimit: FiniteDuration): (Option[String], StageResult) =
    try {
      reporter.checkInterrupt()
      scala.util.Try(Await.result(api.postJob(org, spec, request), indexServicePatience)) match {
        case scala.util.Success(Right(jobId)) =>
          logger.info(s"index service job $jobId posted for $spec (${request.mode}, ${request.files.size} files, deletedIds=${request.deletedIds.isDefined})")
          (Some(jobId), pollIndexJob(api, org, spec, jobId, interval, reporter, sleep, now, stuckLimit))
        case scala.util.Success(Left(rej: IndexServiceRejected)) => (None, StageFailed(rejectedMessage(rej, org)))
        case scala.util.Success(Left(IndexServiceUnreachable(msg))) => (None, StageFailed(s"index service unreachable posting the job for $spec: $msg"))
        case scala.util.Failure(NonFatal(e)) => (None, StageFailed(s"index service unreachable posting the job for $spec: ${e.getMessage}"))
        case scala.util.Failure(e) => throw e
      }
    } catch {
      case _: InterruptedException => (None, StageFailed("Save operation interrupted by user"))
    }

  /**
   * Deletes-only harvest of an externalIndex dataset: post an incremental job
   * with no files and source/deleted.ids, unless deleted.ids is unchanged
   * (size + mtime, in source/deleted.ids.indexed, written after a finished
   * job) since the last post. None = nothing posted; the harvest run is
   * discarded as a no-op, as the registry path does on a quiet tick.
   */
  def indexServiceDeletesOnly(ctx: StageContext, harvestRunIdOpt: Option[Long],
                        sleep: FiniteDuration => Unit = d => Thread.sleep(d.toMillis),
                        now: () => Long = () => System.currentTimeMillis()): Option[StageResult] = {
    val orgContext = ctx.orgContext
    val spec = ctx.spec
    val org = orgContext.narthexConfig.orgId
    val registry = orgContext.recordRegistry
    val deletedIds = new File(ctx.datasetContext.sourceDir, "deleted.ids")
    val marker = new File(ctx.datasetContext.sourceDir, "deleted.ids.indexed")
    val stamp = if (deletedIds.isFile) Some(s"${deletedIds.length}:${deletedIds.lastModified}") else None
    val previous = scala.util.Try(java.nio.file.Files.readString(marker.toPath).trim).toOption
    def discard(): Unit = harvestRunIdOpt.foreach(id => scala.util.Try(registry.discardRun(spec, id)))

    if (stamp.isEmpty || stamp == previous) { discard(); return None }
    if (orgContext.indexServiceApi.isEmpty) {
      val msg = s"externalIndex is set but indexService.url / indexService.token are not configured for org $org"
      harvestRunIdOpt.foreach(id => scala.util.Try(registry.failRun(spec, id, msg)))
      return Some(StageFailed(msg))
    }
    val path = relativeToHome(orgContext.root, org, deletedIds) match {
      case Right(p) => p
      case Left(err) => discard(); return Some(StageFailed(s"index service deletes: $err"))
    }

    ensurePublished(ctx, sleep).foreach { f =>
      harvestRunIdOpt.foreach(id => scala.util.Try(registry.failRun(spec, id, f.message.take(500))))
      return Some(f)
    }

    val runId = harvestRunIdOpt.getOrElse(registry.beginRun(spec, services.RecordRegistry.KIND_INCREMENT))
    harvestRunIdOpt.foreach(id => registry.updateRunPlan(spec, id, PipelinePlan.harvestDeletesOnly.toJson))
    registry.stageStarted(spec, runId, "reconcile", Some(Json.stringify(Json.obj("deletesOnly" -> true, "indexService" -> true))))

    val (jobIdOpt, result) = postAndPoll(orgContext.indexServiceApi.get, org, spec,
      jobRequest(ctx.datasetContext.dsInfo, "incremental", Nil, Some(path)),
      orgContext.narthexConfig.indexServicePollSeconds.seconds, ctx.progressReporter, sleep, now,
      orgContext.narthexConfig.indexServiceStuckMinutes.minutes)
    result match {
      case GraphsSaved(_) =>
        java.nio.file.Files.writeString(marker.toPath, stamp.get)
        registry.stageCompleted(spec, runId, "reconcile", Some(Json.stringify(Json.obj("indexJob" -> jobIdOpt))))
        registry.completeRun(spec, runId)
        logger.info(s"index service deletes-only job ${jobIdOpt.getOrElse("?")} done for $spec")
      case StageFailed(msg) =>
        // This run only: a full save of the same spec may be running too.
        scala.util.Try(registry.failRun(spec, runId, msg.take(500)))
      case _ =>
    }
    Some(result)
  }

  /**
   * Poll an index service job until it finishes. Checks for an interrupt before each
   * poll (the InterruptedException propagates; the index service job runs on).
   * Unreachable polls are retried until they have lasted `unreachableLimit`
   * in a row, measured with `now`. While the job is indexing, a target that
   * has been failed or dirty for `stuckLimit` (indexService.stuckMinutes)
   * fails the stage; the index service keeps retrying the target on its own.
   */
  def pollIndexJob(api: IndexServiceApi, org: String, spec: String, jobId: String, interval: FiniteDuration,
                    reporter: ProgressReporter, sleep: FiniteDuration => Unit, now: () => Long,
                    stuckLimit: FiniteDuration): StageResult = {
    var unreachableSince: Option[Long] = None
    var stuckSince: Option[Long] = None
    var lastTargets = Map.empty[String, String]
    var unknownLogged = false
    while (true) {
      reporter.checkInterrupt()
      val outcome: Either[String, IndexJobStatus] =
        try Await.result(api.jobStatus(org, spec, jobId), indexServicePatience) match {
          case Right(st) => Right(st)
          case Left(rej: IndexServiceRejected) => return StageFailed(rejectedMessage(rej, org))
          case Left(IndexServiceUnreachable(msg)) => Left(msg)
        } catch {
          case e: InterruptedException => throw e
          case NonFatal(e) => Left(Option(e.getMessage).getOrElse(e.getClass.getSimpleName))
        }
      outcome match {
        case Right(st) =>
          unreachableSince = None
          st.state match {
            case "done" | "unchanged" =>
              val saved = st.counts.getOrElse("received", 0L) - st.counts.getOrElse("refused", 0L)
              return GraphsSaved(math.min(saved, Int.MaxValue.toLong).toInt)
            case "failed" =>
              return StageFailed(failedMessage(jobId, org, spec, st.error))
            case "indexing" =>
              // No text channel on the reporter: targets ok out of all
              // targets as the value, and each target's state in the log.
              if (st.targets.nonEmpty) {
                reporter.setMaximum(st.targets.size)
                reporter.sendValue(Some(st.targets.values.count(_.state == "ok")))
              }
              val targets = st.targets.map { case (t, ts) => t -> (ts.state + ts.error.map(e => s" ($e)").getOrElse("")) }
              if (targets != lastTargets) {
                val line = targets.toSeq.sorted.map { case (t, s) => s"$t=$s" }.mkString(", ")
                if (targets.values.exists(_.startsWith("failed"))) logger.warn(s"index service job $jobId for $spec indexing: $line")
                else logger.info(s"index service job $jobId for $spec indexing: $line")
                lastTargets = targets
              }
              val bad = st.targets.toSeq.sortBy(_._1).find { case (_, ts) => ts.state == "failed" || ts.state == "dirty" }
              if (bad.isEmpty) stuckSince = None
              else {
                if (stuckSince.isEmpty) stuckSince = Some(now())
                if (now() - stuckSince.get >= stuckLimit.toMillis) {
                  val (t, ts) = bad.get
                  return StageFailed(s"index service job $jobId for $spec: index target $t has been ${ts.state} for " +
                    s"${stuckLimit.toMinutes} minutes: ${ts.error.getOrElse("(no error given)")}; the index service job carries on " +
                    s"and retries the target (GET /datasets/$org/$spec/index)")
                }
              }
            case "queued" | "ingesting" =>
            case other =>
              if (!unknownLogged) {
                logger.warn(s"index service job $jobId for $spec: unknown state '$other', still polling")
                unknownLogged = true
              }
          }
        case Left(msg) =>
          if (unreachableSince.isEmpty) {
            logger.warn(s"index service unreachable polling job $jobId for $spec, retrying for up to ${unreachableLimit.toMinutes} minutes: $msg")
            unreachableSince = Some(now())
          }
          if (now() - unreachableSince.get >= unreachableLimit.toMillis)
            return StageFailed(s"index service unreachable for ${unreachableLimit.toMinutes} minutes while polling job $jobId for $spec: $msg")
      }
      sleep(interval)
    }
    throw new IllegalStateException("unreachable")
  }
}
