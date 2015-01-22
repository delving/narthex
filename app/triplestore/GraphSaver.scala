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
import dataset.DatasetActor.WorkFailure
import dataset.ProcessedRepo
import dataset.ProcessedRepo.{GraphChunk, GraphReader}
import triplestore.GraphSaver.{ChunkSent, GraphSaveComplete, SaveGraphs}

import scala.util.{Failure, Success}

object GraphSaver {

  case object SaveGraphs

  case object ChunkSent

  case object GraphSaveComplete

  def props(repo: ProcessedRepo, client: TripleStore) = Props(new GraphSaver(repo, client))

}

class GraphSaver(repo: ProcessedRepo, client: TripleStore) extends Actor with ActorLogging {

  import context.dispatcher

  var reader: Option[GraphReader] = None

  def readGraphChunkOpt: Option[GraphChunk] = reader.get.readChunk

  override def receive = {

    /*
      This is still naive because there is no distinction between first and incremental.
      Not tested yet.
     */

    case SaveGraphs =>
      reader = Some(repo.createGraphReader(chunkSize = 5))
      self ! readGraphChunkOpt

    case Some(chunk: GraphChunk) =>

      // todo: this is where enrichment will have to happen

      client.update(chunk.toSparqlUpdate).onComplete {
        case Success(nothing) =>
          self ! readGraphChunkOpt
        case Failure(ex) =>
          reader.get.close()
          context.parent ! WorkFailure(ex.getMessage, Some(ex))
      }

    case None =>
      reader.get.close()
      context.parent ! GraphSaveComplete

    case ChunkSent =>
      self ! readGraphChunkOpt

  }
}
