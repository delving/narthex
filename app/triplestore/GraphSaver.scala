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

import java.io.{OutputStream, InputStream}
import java.util
import javax.xml.ws.http.HTTPException

import akka.actor.{Actor, ActorLogging, Props}
import dataset.DatasetActor.{Incremental, WorkFailure}
import dataset.DatasetContext
import dataset.ProcessedRepo.{GraphChunk, GraphReader}
import org.OrgContext
import org.OrgContext._
import org.joda.time.DateTime
import play.api.Logger
import play.api.libs.ws.{WSResponse, WS}
import play.api.Play.current
import services.ProgressReporter
import services.ProgressReporter.ProgressState._
import triplestore.GraphProperties.acceptanceOnly

object GraphSaver {

  case object GraphSaveComplete

  case class SaveGraphs(incrementalOpt: Option[Incremental])

  def props(datasetContext: DatasetContext) = Props(new GraphSaver(datasetContext))

}

class GraphSaver(datasetContext: DatasetContext) extends Actor with ActorLogging {

  import context.dispatcher
  import triplestore.GraphSaver._

  implicit val ts = OrgContext.TS

  val saveTime = new DateTime()
  var reader: Option[GraphReader] = None
  var progressOpt: Option[ProgressReporter] = None
  val bulkApi = s"${OrgContext.NAVE_API_URL}/api/index/bulk/"

  private def checkUpdateResponse(response: WSResponse, logString: String): Unit = {
    if (response.status != 201) {
      Logger.error(logString)
      throw new Exception(s"${response.statusText}: ${response.body}:")
    }
  }

  def bulkApiUpdate(bulkActions: String) = {
    if (OrgContext.LOG_BULK_API) {
      Logger.info(bulkActions)
    }
    val request = WS.url(s"$bulkApi").withHeaders(
      "Content-Type" -> "text/plain; charset=utf-8",
      "Accept" -> "application/json",
      "Authorization" -> s"Token ${OrgContext.NAVE_BULK_API_AUTH_TOKEN}"
    )
    request.post(bulkActions).map(checkUpdateResponse(_, bulkActions))
  }

  def failure(ex: Throwable) = {
    reader.foreach(_.close())
    reader = None
    context.parent ! WorkFailure(ex.getMessage, Some(ex))
  }

  def sendGraphChunkOpt() = {
    try {
      progressOpt.get.sendValue()
      self ! reader.get.readChunkOpt
    }
    catch {
      case ex: Throwable => failure(ex)
    }
  }

  override def receive = {

    case SaveGraphs(incrementalOpt) => actorWork(context) {
      log.info("Save graphs")
      val progressReporter = ProgressReporter(SAVING, context.parent)
      progressOpt = Some(progressReporter)
      reader = Some(datasetContext.processedRepo.createGraphReader(incrementalOpt.map(_.file), saveTime, progressReporter))
      sendGraphChunkOpt()
    }

    case Some(chunk: GraphChunk) => actorWork(context) {
      log.info(s"Save a chunk of graphs (bulk-api: ${OrgContext.USE_BULK_API})")
      val update = if (OrgContext.USE_BULK_API) bulkApiUpdate(chunk.bulkAPIQ) else ts.up.acceptanceOnly(chunk.dsInfo.getBooleanProp(acceptanceOnly)).sparqlUpdate(chunk.sparqlUpdateQ)
      update.map(ok => sendGraphChunkOpt())
      update.onFailure {
        case ex: Throwable => failure(ex)
      }
    }

    case None => actorWork(context) {
      reader.foreach(_.close())
      reader = None
      log.info("All graphs saved")
      context.parent ! GraphSaveComplete
    }
  }
}
