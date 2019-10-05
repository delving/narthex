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

import akka.actor.{Actor, ActorLogging, Props}
import dataset.DatasetActor.{Scheduled, WorkFailure}
import dataset.DatasetContext
import dataset.DsInfo
import dataset.ProcessedRepo.{GraphChunk, GraphReader}
import organization.OrgContext
import nxutil.Utils._
import org.joda.time.DateTime
import services.ProgressReporter
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

  implicit val ts = orgContext.ts

  val saveTime = new DateTime()
  val startSave = timeToString(new DateTime())
  var isScheduled = false
  var isIncremental = false

  var reader: Option[GraphReader] = None
  var progressOpt: Option[ProgressReporter] = None

  def failure(ex: Throwable) = {
    log.info(ex.getMessage)
    reader.foreach(_.close())

    reader = None
    context.parent ! WorkFailure(ex.getMessage, Some(ex))
  }

  def sendGraphChunkOpt() = {
    try {
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
          datasetContext.dsInfo.updateDatasetRevision()
        }
        sendGraphChunkOpt()
      }

    case Some(chunk: GraphChunk) =>
      actorWork(context) {
        //log.info(s"Save a chunk of graphs")
        //log.info(s"chunk: ${chunk}")
        if (!chunk.bulkActions.isEmpty) {
          val update =
            chunk.dsInfo.bulkApiUpdate(chunk.bulkActions)

          update.map(ok => sendGraphChunkOpt())
          update.onFailure {
            case ex: Throwable => failure(ex)
          }
        } else {
		  log.info(s"Save a chunk of graphs")
		  val update = chunk.dsInfo.bulkApiUpdate(chunk.bulkAPIQ(orgContext.appConfig.orgId))

		  update.map(ok => sendGraphChunkOpt())
		  update.onFailure {
			case ex: Throwable => failure(ex)
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
        if (isScheduled) {
          orgContext.semaphore.release(datasetContext.dsInfo.spec)
          log.info(
            s"${datasetContext.dsInfo.spec} is scheduled so semaphore should be released: ${orgContext.semaphore.activeSpecs().toString()}"
          )
        }
        log.info("All graphs saved")
        context.parent ! GraphSaveComplete
      }
  }
}
