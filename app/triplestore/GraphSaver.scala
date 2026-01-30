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

import scala.util.{Failure, Success}
import akka.actor.{Actor, ActorLogging, Props}
import org.joda.time.DateTime

import dataset.DatasetActor.{Scheduled, WorkFailure}
import dataset.DatasetContext
import dataset.DsInfo.Hub3BulkApiException
import dataset.ProcessedRepo.{GraphChunk, GraphReader}
import organization.OrgContext
import nxutil.Utils._
import services.{ActivityLogger, ProgressReporter}
import services.ProgressReporter.InterruptedException
import services.ProgressReporter.ProgressState._
import services.Temporal._

object GraphSaver {

  case object GraphSaveComplete

  case class SaveGraphs(scheduledOpt: Option[Scheduled])

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

  var reader: Option[GraphReader] = None
  var progressOpt: Option[ProgressReporter] = None
  
  override def postStop(): Unit = {
    // Ensure resources are cleaned up even if actor is terminated unexpectedly
    reader.foreach(_.close())
    reader = None
    
    // Release BOTH semaphores if this actor is stopped without proper completion
    if (orgContext.semaphore.isActive(datasetContext.dsInfo.spec) || 
        orgContext.saveSemaphore.isActive(datasetContext.dsInfo.spec)) {
      log.warning(s"GraphSaver stopped unexpectedly for ${datasetContext.dsInfo.spec}, releasing both semaphores")
      orgContext.semaphore.release(datasetContext.dsInfo.spec)
      orgContext.saveSemaphore.release(datasetContext.dsInfo.spec)
    }
    
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

        // CRITICAL: Always release BOTH semaphores on failure to be safe
        orgContext.semaphore.release(datasetContext.dsInfo.spec)
        orgContext.saveSemaphore.release(datasetContext.dsInfo.spec)
        log.info(s"Released both semaphores for ${datasetContext.dsInfo.spec} due to Hub3 rejection")

        context.parent ! WorkFailure(hub3Ex.message, Some(hub3Ex))

      case _ =>
        log.error(s"GraphSaver failure for ${datasetContext.dsInfo.spec}: ${ex.getMessage}", ex)
        reader.foreach(_.close())
        reader = None

        // CRITICAL: Always release BOTH semaphores on failure to be safe
        orgContext.semaphore.release(datasetContext.dsInfo.spec)
        orgContext.saveSemaphore.release(datasetContext.dsInfo.spec)
        log.info(s"Released both semaphores for ${datasetContext.dsInfo.spec} due to failure")

        context.parent ! WorkFailure(ex.getMessage, Some(ex))
    }
  }

  def handleInterruption() = {
    log.info(s"Handling interruption for ${datasetContext.dsInfo.spec}")
    reader.foreach(_.close())
    reader = None

    // Release semaphores since operation was cancelled
    orgContext.semaphore.release(datasetContext.dsInfo.spec)
    orgContext.saveSemaphore.release(datasetContext.dsInfo.spec)
    log.info(s"Released both semaphores for ${datasetContext.dsInfo.spec} due to interruption")

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

    case SaveGraphs(scheduledOpt) =>
      actorWork(context) {
        log.info("Save graphs")
        val progressReporter = ProgressReporter(SAVING, context.parent)
        progressOpt = Some(progressReporter)
        isScheduled = !scheduledOpt.isEmpty
        isIncremental = isScheduled && !scheduledOpt.head.modifiedAfter.isEmpty
        reader = Some(
          datasetContext.processedRepo.createGraphReaderXML(
            scheduledOpt.map(_.file),
            saveTime,
            progressReporter))
        if (!isIncremental) {
          log.info(s"Only increment dataset revision when not in incremental mode")
          // IMPORTANT: Wait for increment_revision to complete before sending records.
          // Otherwise, the first batch may be indexed with the OLD revision and then
          // deleted as an orphan when clear_orphans runs.
          val incrementFuture = datasetContext.dsInfo.updateDatasetRevision()
          incrementFuture.onComplete {
            case Success(_) =>
              log.info("Dataset revision incremented, starting to send graph chunks")
              sendGraphChunkOpt()
            case Failure(ex) =>
              failure(ex)
          }
        } else {
          sendGraphChunkOpt()
        }
      }

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
          if (!chunk.bulkActions.isEmpty) {
            val update =
              chunk.dsInfo.bulkApiUpdate(chunk.bulkActions)

            update.map(_ => sendGraphChunkOpt())
            update.onComplete {
              case Success(_) => ()
              case Failure(ex) => failure(ex)
            }
          } else {
            log.info(s"Save a chunk of graphs")
            val update = chunk.dsInfo.bulkApiUpdate(chunk.bulkAPIQ(orgContext.appConfig.orgId))

            update.map(_ => sendGraphChunkOpt())
            update.onComplete {
              case Success(_) => ()
              case Failure(ex) => failure(ex)
            }
          }
        }
      }

    case None =>
      actorWork(context) {
        reader.foreach(_.close())
        reader = None
        if (!isIncremental){
          datasetContext.dsInfo.removeNaveOrphans(startSave)
          log.info(s"Only drop orphans when not in incremental mode")
        }

        // Log deleted record IDs for future orphan control (Hub3 bulk delete)
        // TODO: Implement actual Hub3 bulk delete API call in future iteration
        datasetContext.sourceRepoOpt.foreach { sourceRepo =>
          val deletedIds = sourceRepo.deletedIdSet
          if (deletedIds.nonEmpty) {
            log.info(s"Found ${deletedIds.size} deleted record IDs for future orphan control in Hub3")
            // Future: Call Hub3 bulk delete API here
            // datasetContext.dsInfo.deleteRecordsByIds(deletedIds.toList)
          }
        }

        // Release BOTH semaphores on successful completion to be safe
        orgContext.semaphore.release(datasetContext.dsInfo.spec)
        orgContext.saveSemaphore.release(datasetContext.dsInfo.spec)
        log.info(
          s"${datasetContext.dsInfo.spec} both semaphores released. Active specs - semaphore: ${orgContext.semaphore.activeSpecs().toString()}, saveSemaphore: ${orgContext.saveSemaphore.activeSpecs().toString()}"
        )
        log.info("All graphs saved")
        context.parent ! GraphSaveComplete
      }
  }
}
