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

package actors

import java.io.File

import actors.Analyzer._
import actors.Collator._
import actors.Sorter._
import akka.actor.{Actor, ActorRef, Props}
import org.apache.commons.io.FileUtils
import play.api.Logger
import play.api.libs.json._
import services.DatasetState._
import services._

import scala.concurrent._

/*
 * @author Gerald de Jong <gerald@delving.eu>
 */

object Analyzer {

  case class AnalyzeFile(file: File)

  case class AnalysisProgress(percent: Int)

  case class AnalysisTreeComplete(json: JsValue)

  case class AnalysisError(error: String)

  case class AnalysisComplete()

  case class InterruptAnalysis()

  def props(datasetRepo: DatasetRepo) = Props(new Analyzer(datasetRepo))
}

class Analyzer(val datasetRepo: DatasetRepo) extends Actor with TreeHandling {
  val log = Logger
  val LINE = """^ *(\d*) (.*)$""".r
  var bomb: Option[ActorRef] = None
  var sorters = List.empty[ActorRef]
  var collators = List.empty[ActorRef]

  def receive = {

    case InterruptAnalysis() =>
      log.debug(s"Interrupted analysis $datasetRepo")
      bomb = Some(sender)

    case AnalyzeFile(file) =>
      log.debug(s"Analyzer on ${file.getName}")
      val progress = context.actorOf(Props(new Actor() {
        override def receive: Receive = {
          case AnalysisProgress(percent) =>
            datasetRepo.datasetDb.setStatus(SPLITTING, percent = percent)
        }
      }))
      def sendProgress(percent: Int): Boolean = {
        if (bomb.isDefined) return false
        progress ! AnalysisProgress(percent)
        true
      }
      val (source, readProgress) = FileHandling.sourceFromFile(file)
      import context.dispatcher
      val f = future {
        TreeNode(source, file.length, readProgress, datasetRepo, sendProgress) match {
          case Some(tree) =>
            tree.launchSorters {
              node =>
                if (node.lengths.isEmpty) {
                  node.nodeRepo.setStatus(Json.obj("uniqueCount" -> 0))
                }
                else {
                  node.nodeRepo.setStatus(Json.obj("sorting" -> true))
                  val sorter = context.actorOf(Sorter.props(node.nodeRepo))
                  sorters = sorter :: sorters
                  sorter ! Sort(SortType.VALUE_SORT)
                }
            }
            self ! AnalysisTreeComplete(Json.toJson(tree))
          case None =>
            self ! AnalysisError(s"Interrupted while splitting ${file.getName}")
        }
        source.close()
      }
      f.onFailure {
        case ex: Exception =>
          source.close()
          log.error("Problem reading the file", ex)
          self ! AnalysisError(s"Unable to read ${file.getName}: $ex")
      }

    case Counted(nodeRepo, uniqueCount, sampleSizes) =>
      log.debug(s"Count finished : ${nodeRepo.counted.getAbsolutePath}")
      collators = collators.filter(collator => collator != sender)
      FileUtils.deleteQuietly(nodeRepo.sorted)
      nodeRepo.setStatus(Json.obj(
        "uniqueCount" -> uniqueCount,
        "samples" -> sampleSizes
      ))
      if (!bomb.isDefined) {
        val sorter = context.actorOf(Sorter.props(nodeRepo))
        sorters = sorter :: sorters
        sorter ! Sort(SortType.HISTOGRAM_SORT)
      }

    case Sorted(nodeRepo, sortedFile, sortType) =>
      log.debug(s"Sort finished : ${sortedFile.getAbsolutePath}")
      sorters = sorters.filter(sorter => sender != sorter)
      sortType match {
        case SortType.VALUE_SORT =>
          FileUtils.deleteQuietly(nodeRepo.values)
          if (!bomb.isDefined) {
            val collator = context.actorOf(Collator.props(nodeRepo))
            collators = collator :: collators
            collator ! Count()
          }

        case SortType.HISTOGRAM_SORT =>
          log.debug(s"writing histograms : ${datasetRepo.analyzedDir.getAbsolutePath}")
          RepoUtil.updateJson(nodeRepo.status) {
            current =>
              val uniqueCount = (current \ "uniqueCount").as[Int]
              val samples = current \ "samples"
              val histogramSizes = nodeRepo.writeHistograms(uniqueCount)
              Json.obj(
                "uniqueCount" -> uniqueCount,
                "samples" -> samples,
                "histograms" -> histogramSizes
              )
          }
          FileUtils.deleteQuietly(nodeRepo.counted)
          if (sorters.isEmpty && collators.isEmpty) {
            self ! AnalysisComplete()
          }
          else {
            datasetRepo.datasetDb.setStatus(ANALYZING, workers = sorters.size + collators.size)
          }
      }

    case AnalysisTreeComplete(json) =>
      datasetRepo.datasetDb.setStatus(ANALYZING, workers = sorters.size + collators.size)
      log.info(s"Tree Complete at ${datasetRepo.analyzedDir.getName}")

    case AnalysisComplete() =>
      if (bomb.isDefined) {
        self ! AnalysisError("Interrupted")
      }
      else {
        log.info(s"Analysis Complete, kill: ${self.toString()}")
        datasetRepo.datasetDb.setStatus(ANALYZED)
        context.stop(self)
      }

    case AnalysisError(error) =>
      log.info(s"Analysis error: $error")
      datasetRepo.datasetDb.setStatus(READY, error = error)
      FileUtils.deleteQuietly(datasetRepo.analyzedDir)
      context.stop(self)

  }
}
