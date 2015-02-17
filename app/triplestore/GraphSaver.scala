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
import dataset.DatasetActor.{Incremental, InterruptWork, WorkFailure}
import dataset.ProcessedRepo
import dataset.ProcessedRepo.{GraphChunk, GraphReader}
import services.ProgressReporter
import services.ProgressReporter.ProgressState._

object GraphSaver {

  case object GraphSaveComplete

  case class SaveGraphs(incrementalOpt: Option[Incremental])

  def props(repo: ProcessedRepo, ts: TripleStore) = Props(new GraphSaver(repo, ts))

}

class GraphSaver(repo: ProcessedRepo, ts: TripleStore) extends Actor with ActorLogging {

  import context.dispatcher
  import triplestore.GraphSaver._

  var reader: Option[GraphReader] = None
  var progressOpt: Option[ProgressReporter] = None

  def failure(ex: Throwable) = {
    reader.map(_.close())
    reader = None
    context.parent ! WorkFailure(ex.getMessage, Some(ex))
  }

  def sendGraphChunkOpt() = {
    try {
      val progress = progressOpt.get
      // todo: should graphs be deleted in case of interruption?
      val chunkOpt = if (progress.keepWorking) reader.get.readChunk else None
      self ! chunkOpt
    }
    catch {
      case ex: Throwable => failure(ex)
    }
  }

  override def receive = {

    case InterruptWork =>
      progressOpt.map(_.interruptBy(sender()))

    case SaveGraphs(incrementalOpt) =>
      log.info("Save graphs")
      val progressReporter = ProgressReporter(SAVING, context.parent)
      progressOpt = Some(progressReporter)
      reader = Some(repo.createGraphReader(incrementalOpt.map(_.file), progressReporter))
      sendGraphChunkOpt()

    case Some(chunk: GraphChunk) =>
      log.info("Save chunk")
      val update = ts.update(chunk.toSparqlUpdate)
      update.map(ok => sendGraphChunkOpt())
      update.onFailure {
        case ex: Throwable => failure(ex)
      }

    case None =>
      reader.map(_.close())
      reader = None
      context.parent ! GraphSaveComplete

  }
}
