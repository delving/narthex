//===========================================================================
//    Copyright 2015 Delving B.V.
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

package triplestore

import scala.jdk.CollectionConverters._
import scala.util.{Failure, Success}
import akka.actor.{Actor, ActorLogging, Props}
import org.joda.time.DateTime

import dataset.DatasetActor.{Scheduled, WorkFailure}
import dataset.DatasetContext
import dataset.DsInfo.Hub3BulkApiException
import dataset.ProcessedRepo.{GraphChunk, GraphReader}
import organization.OrgContext
import nxutil.Utils._
import services.{ActivityLogger, ProgressReporter, RecordRegistry}
import services.ProgressReporter.InterruptedException
import services.ProgressReporter.ProgressState._
import services.Temporal._

object GraphSaver {

  case object GraphSaveComplete

  case class SaveGraphs(scheduledOpt: Option[Scheduled],
                        registryRunId: Option[Long] = None,
                        saveMode: Option[dataset.PipelinePlan.SaveMode] = None)

  /** Internal messages for thread-safe Future result handling */
  private case object RevisionReady
  private case class ChunkSaved(ids: Seq[String])
  private case object RegistryDropsDone
  private case class AsyncFailure(ex: Throwable)

  def props(datasetContext: DatasetContext, orgContext: OrgContext) =
    Props(new GraphSaver(datasetContext, orgContext))

}

class GraphSaver(datasetContext: DatasetContext, val orgContext: OrgContext)
    extends Actor
    with ActorLogging {

  import context.dispatcher
  import triplestore.GraphSaver._

  implicit val ts: TripleStore = orgContext.ts

  val saveTime = new DateTime()
  val startSave = timeToString(new DateTime())
  var isScheduled = false
  var isIncremental = false
  var isExplicitFileSave = false
  var chunksSaved = 0
  var recordsSent = 0
  var sentIds = Set.empty[String]
  var registryRunIdOpt: Option[Long] = None
  var expectedIndexIds = Set.empty[String]
  var registryIndexFiltering = false
  var willRevisionSweep = false

  var reader: Option[GraphReader] = None
  var progressOpt: Option[ProgressReporter] = None

  private val registry = orgContext.recordRegistry
  private val registryEnabled = orgContext.narthexConfig.registryEnabled
  private val keepRevisionSweep = orgContext.narthexConfig.registryKeepRevisionSweep
  private val spec = datasetContext.dsInfo.spec
  private val dropBatchSize = 10000

  private def localIdsInChunk(chunk: GraphChunk): Seq[String] = {
    val dsInfo = chunk.dsInfo
    chunk.dataset
      .listNames()
      .asScala
      .toList
      .flatMap { graphUri =>
        scala.util.Try(dsInfo.extractSpecIdFromGraphName(graphUri))
          .toOption
          .map(_._2)
      }
  }

  private def processedInvalidCount: Int =
    datasetContext.dsInfo
      .getLiteralProp(triplestore.GraphProperties.processedInvalid)
      .flatMap(value => scala.util.Try(value.toInt).toOption)
      .getOrElse(0)

  private def emitPendingDrops(): scala.concurrent.Future[Unit] = {
    def loop(): scala.concurrent.Future[Unit] = {
      val pendingDrops = registry.pendingDropBatch(spec, dropBatchSize)
      if (pendingDrops.isEmpty) scala.concurrent.Future.successful(())
      else {
        log.info(s"Registry: emitting drop_records for ${pendingDrops.size} ids ($spec)")
        datasetContext.dsInfo.dropRecordsByIds(pendingDrops).map { _ =>
          registry.confirmDropped(spec, pendingDrops)
          ()
        }.flatMap(_ => loop())
      }
    }

    if (registryEnabled) loop()
    else scala.concurrent.Future.successful(())
  }
  
  override def postStop(): Unit = {
    // Ensure resources are cleaned up even if actor is terminated unexpectedly
    reader.foreach(_.close())
    reader = None
    // Lease cleanup is the parent DatasetActor/OrgActor's concern.
    super.postStop()
  }

  def failure(ex: Throwable) = {
    ex match {
      case _: InterruptedException =>
        // Graceful interruption - not an error, user cancelled the operation
        log.info(s"GraphSaver interrupted for ${datasetContext.dsInfo.spec}")
        handleInterruption()

      case hub3Ex: Hub3BulkApiException =>
        // Hub3 rejected records - log detailed error to activity log for user visibility
        log.error(s"GraphSaver Hub3 rejection for ${datasetContext.dsInfo.spec}: ${hub3Ex.message}")
        log.error(s"  Hub3 error details: ${hub3Ex.hub3ErrorMessage.getOrElse("(no parsed message)")}")
        log.error(s"  Affected records: ${hub3Ex.affectedRecordIds.mkString(", ")}")

        // Log to activity log so users can see it in the UI
        ActivityLogger.logBulkApiError(
          activityLog = datasetContext.activityLog,
          operation = "SAVE",
          statusCode = hub3Ex.statusCode,
          errorMessage = hub3Ex.message,
          fullResponse = hub3Ex.hub3FullResponse,
          affectedRecordIds = hub3Ex.affectedRecordIds
        )

        reader.foreach(_.close())
        reader = None

        context.parent ! WorkFailure(hub3Ex.message, Some(hub3Ex))

      case _ =>
        log.error(s"GraphSaver failure for ${datasetContext.dsInfo.spec}: ${ex.getMessage}", ex)
        reader.foreach(_.close())
        reader = None

        context.parent ! WorkFailure(ex.getMessage, Some(ex))
    }
  }

  def handleInterruption() = {
    log.info(s"Handling interruption for ${datasetContext.dsInfo.spec}")
    reader.foreach(_.close())
    reader = None

    // Notify parent that save was interrupted (not a failure, just stopped)
    context.parent ! WorkFailure("Save operation interrupted by user", None)
  }

  def sendGraphChunkOpt() = {
    try {
      // Check for interruption before processing next chunk
      progressOpt.foreach(_.checkInterrupt())
      progressOpt.get.sendValue()
      self ! reader.get.readChunkOpt
    } catch {
      case ex: Throwable => failure(ex)
    }
  }

  override def receive = {

    case SaveGraphs(scheduledOpt, runIdOpt, saveModeOpt) =>
      actorWork(context) {
        log.info("Save graphs")
        val progressReporter = ProgressReporter(SAVING, context.parent)
        progressOpt = Some(progressReporter)
        isScheduled = !scheduledOpt.isEmpty
        isExplicitFileSave = scheduledOpt.isDefined
        isIncremental = isScheduled && !scheduledOpt.head.modifiedAfter.isEmpty
        // Manual saves ("start saving", fast-save from PROCESSED) carry no
        // run id. Adopt the run that produced the processed output — the
        // latest completed FULL run — so chunk confirms, the missing-sweep
        // and completeRun happen exactly as in the harvest-chained flow.
        registryRunIdOpt = runIdOpt.orElse {
          if (registryEnabled && !isIncremental) {
            val adopted = registry.latestCompletedFullRunId(spec)
            adopted.foreach(id => log.info(s"Registry: manual save adopting run $id ($spec)"))
            adopted
          } else None
        }

        val invalidFileOpt = scheduledOpt.map(_.file).filterNot { file =>
          file.getName.endsWith(".xml") || file.getName.endsWith(".xml.zst")
        }
        invalidFileOpt.foreach { file =>
          throw new RuntimeException(
            s"SaveGraphs received non-processed file ${file.getName}; expected .xml or .xml.zst")
        }

        // The save mode is decided ONCE — by the planner for chained saves,
        // by the same pure function here for legacy callers — and makes the
        // sweep and delta filtering mutually exclusive by construction: the
        // revision sweep (increment_revision + clear_orphans) deletes
        // everything in Hub3 not re-indexed at the new revision, so
        // filtering a swept save would mass-delete every unchanged record
        // while the registry still records them as sent.
        val saveMode = saveModeOpt.getOrElse(
          dataset.PipelinePlan.saveModeFor(isIncremental, registryEnabled, keepRevisionSweep))
        willRevisionSweep = saveMode == dataset.PipelinePlan.FullSendWithSweep

        expectedIndexIds =
          if (registryEnabled && !willRevisionSweep) registry.pendingIndexBatch(spec, Int.MaxValue).map(_._1).toSet
          else Set.empty[String]
        val includeLocalIdsOpt =
          if (registryEnabled && !willRevisionSweep && (registryRunIdOpt.isDefined || expectedIndexIds.nonEmpty)) Some(expectedIndexIds)
          else None
        registryIndexFiltering = includeLocalIdsOpt.isDefined
        if (registryEnabled) {
          if (willRevisionSweep) {
            log.info(s"Registry: delta filtering disabled for $spec — revision sweep (clear_orphans) requires a full send")
          } else includeLocalIdsOpt match {
            case Some(_) =>
              log.info(s"Registry: ${expectedIndexIds.size} pending index record(s) will be sent for $spec")
            case None =>
              log.warning(s"Registry: no run/pending rows for $spec; falling back to full processed-file send")
          }
        }

        registryRunIdOpt.foreach { runId =>
          registry.stageStarted(spec, runId, "save", Some(play.api.libs.json.Json.stringify(
            play.api.libs.json.Json.obj(
              "saveMode" -> saveMode.name,
              "incremental" -> isIncremental,
              "deltaFiltering" -> registryIndexFiltering,
              "pending" -> expectedIndexIds.size,
              "file" -> scheduledOpt.map(_.file.getName)
            ))))
        }

        reader = Some(
          datasetContext.processedRepo.createGraphReaderXML(
            scheduledOpt.map(_.file),
            saveTime,
            progressReporter,
            includeLocalIdsOpt))

        if (willRevisionSweep) {
          log.info(s"Incrementing dataset revision (keepRevisionSweep=$keepRevisionSweep)")
          // IMPORTANT: Wait for increment_revision to complete before sending records.
          // Otherwise, the first batch may be indexed with the OLD revision and then
          // deleted as an orphan when clear_orphans runs.
          // Use message-passing to avoid accessing actor state from Future thread
          val selfRef = self
          val incrementFuture = datasetContext.dsInfo.updateDatasetRevision()
          incrementFuture.onComplete {
            case Success(_) =>
              log.info("Dataset revision incremented, starting to send graph chunks")
              selfRef ! RevisionReady
            case Failure(ex) =>
              selfRef ! AsyncFailure(ex)
          }
        } else {
          sendGraphChunkOpt()
        }
      }

    case RevisionReady =>
      actorWork(context) {
        sendGraphChunkOpt()
      }

    case ChunkSaved(ids) =>
      actorWork(context) {
        chunksSaved += 1
        recordsSent += ids.size
        sentIds ++= ids
        sendGraphChunkOpt()
      }

    case AsyncFailure(ex) =>
      failure(ex)

    case Some(chunk: GraphChunk) =>
      actorWork(context) {
        // Check for interruption before processing chunk
        val isInterrupted = try {
          progressOpt.foreach(_.checkInterrupt())
          false
        } catch {
          case _: InterruptedException =>
            handleInterruption()
            true
        }

        if (!isInterrupted) {
          // Use message-passing to avoid accessing actor state from Future thread
          val selfRef = self
          val update = if (!chunk.bulkActions.isEmpty) {
            chunk.dsInfo.bulkApiUpdate(chunk.bulkActions)
          } else {
            log.info(s"Save a chunk of graphs")
            chunk.dsInfo.bulkApiUpdate(chunk.bulkAPIQ(orgContext.appConfig.orgId))
          }
          update.onComplete {
            case Success(_) =>
              val ids = if (registryEnabled) localIdsInChunk(chunk) else Seq.empty
              if (registryEnabled) {
                registryRunIdOpt.foreach { runId =>
                  if (ids.nonEmpty) {
                    scala.util.Try(registry.confirmIndexedByIds(spec, ids, runId))
                      .recover { case ex: Throwable =>
                        log.warning(s"Registry: confirmIndexedByIds failed for ${ids.size} ids: ${ex.getMessage}")
                      }
                  }
                }
              }
              selfRef ! ChunkSaved(ids)
            case Failure(ex) => selfRef ! AsyncFailure(ex)
          }
        }
      }

    case None =>
      actorWork(context) {
        reader.foreach(_.close())
        reader = None

        // Compare the DISTINCT ids actually sent against the pending set: a
        // raw count can double-count an id present in two processed files and
        // mask a genuinely missing record.
        val missingPending: Set[String] =
          if (registryIndexFiltering) expectedIndexIds -- sentIds else Set.empty

        if (isExplicitFileSave && chunksSaved == 0 &&
            (!registryEnabled || (registryIndexFiltering && expectedIndexIds.nonEmpty && !isIncremental))) {
          failure(new RuntimeException("Explicit processed-file save completed with zero graph chunks"))
        } else if (registryIndexFiltering && !isIncremental && missingPending.nonEmpty) {
          failure(new RuntimeException(
            s"Registry save for $spec sent only ${sentIds.size} of ${expectedIndexIds.size} pending index records — ${missingPending.size} missing from processed output"))
        } else {
          // A file-scoped incremental save can legitimately lack pending
          // leftovers from earlier runs (failed save, record turned invalid).
          // Not fatal: they stay pending and flush on the next full save.
          if (registryIndexFiltering && isIncremental && missingPending.nonEmpty) {
            log.warning(s"Registry: ${missingPending.size} pending record(s) not in this incremental file for $spec; they remain pending for the next full save")
          }

          registryRunIdOpt.foreach { runId =>
            registry.stageCompleted(spec, runId, "save", Some(play.api.libs.json.Json.stringify(
              play.api.libs.json.Json.obj("chunks" -> chunksSaved, "sent" -> sentIds.size))))
            registry.stageStarted(spec, runId, "reconcile", None)
          }
          // Full-harvest registry sweep: mark records not seen this run as
          // deleted so they get drop_records actions below. Deliberately runs
          // only HERE, after every chunk was acked by Hub3 — a failed or
          // truncated save must never commit destructive tombstones, or the
          // next innocuous incremental save would mass-drop live records.
          // Incremental saves skip the sweep — they see only the delta and
          // cannot reason about the absent set.
          if (registryEnabled && !isIncremental && processedInvalidCount == 0) {
            registryRunIdOpt.foreach { runId =>
              val swept = registry.markMissingForFullRun(spec, runId)
              if (swept > 0) log.info(s"Registry: marked $swept records missing for full run $runId ($spec)")
            }
          } else if (registryEnabled && !isIncremental) {
            log.warning(s"Registry: skipping missing-record sweep for $spec because processedInvalid=$processedInvalidCount")
          }

          // Registry-driven drops: explicit drop_records for every record
          // the registry knows is deleted (OAI tombstones + full-run sweep
          // misses). Runs for both full and incremental saves.
          val selfRef = self
          emitPendingDrops().onComplete {
            case Success(_) =>
              selfRef ! RegistryDropsDone
            case Failure(ex) =>
              // Non-fatal: all chunks are already in Hub3, and unconfirmed
              // drops stay pending in the registry and are retried on the
              // next save. Also keeps saves working while Hub3 predates the
              // drop_records action.
              log.warning(s"Registry: drop_records failed, will retry next save: ${ex.getMessage}")
              selfRef ! RegistryDropsDone
          }
        }
      }

    case RegistryDropsDone =>
      actorWork(context) {
        if (willRevisionSweep) {
          datasetContext.dsInfo.removeNaveOrphans(startSave)
          log.info(s"Revision sweep: clear_orphans emitted (keepRevisionSweep=$keepRevisionSweep)")
        }

        // Close the run. The registry computes the real per-run diff
        // (added/changed/deleted) internally; recordsSent answers "how much
        // did this save actually push to Hub3". Run bookkeeping is pipeline
        // infrastructure and runs regardless of registryEnabled (which gates
        // only the Hub3-state semantics: stamping, confirms, drops, filter).
        registryRunIdOpt.foreach { runId =>
          registry.stageCompleted(spec, runId, "reconcile", Some(play.api.libs.json.Json.stringify(
            play.api.libs.json.Json.obj("revisionSweep" -> willRevisionSweep))))
          scala.util.Try(registry.completeRun(spec, runId, recordsSent))
            .recover { case ex: Throwable =>
              log.warning(s"Registry: completeRun failed for run $runId: ${ex.getMessage}")
            }
        }

        log.info("All graphs saved")
        context.parent ! GraphSaveComplete
      }
  }
}
