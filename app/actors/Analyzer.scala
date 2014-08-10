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
import java.security.MessageDigest

import actors.Analyzer._
import actors.Collator._
import actors.Sorter._
import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import org.apache.commons.io.FileUtils
import play.api.libs.json._
import services.Repo.State._
import services._

import scala.util.{Failure, Success}

/*
 * @author Gerald de Jong <gerald@delving.eu>
 */

object Analyzer {

  case class Analyze(file: File)

  case class AnalysisProgress(percent: Int)

  case class AnalysisTreeComplete(json: JsValue, digest: MessageDigest)

  case class AnalysisError(file: File)

  case class AnalysisComplete()

  def props(fileRepo: FileRepo) = Props(new Analyzer(fileRepo))
}

class Analyzer(val fileRepo: FileRepo) extends Actor with TreeHandling with ActorLogging {

  val LINE = """^ *(\d*) (.*)$""".r
  var sorters = List.empty[ActorRef]
  var collators = List.empty[ActorRef]

  def receive = {

    case Analyze(file) =>
      log.debug(s"Analyzer on ${file.getName}")
      val (source, countingStream, digest) = FileHandling.countingSource(file)
      val progress = context.actorOf(Props(new Actor() {
        override def receive: Receive = {
          case AnalysisProgress(percent) =>
            fileRepo.setStatus(SPLITTING, percent = percent)
        }
      }))
      def sendProgress(percent: Int) = progress ! AnalysisProgress(percent)
      TreeNode(source, file.length, countingStream, fileRepo, sendProgress) match {
        case Success(tree) =>
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
          self ! AnalysisTreeComplete(Json.toJson(tree), digest)

        case Failure(e) =>
          log.error(e, "Problem reading the file")
          self ! AnalysisError(file)
      }
      source.close()

    case Counted(nodeRepo, uniqueCount, sampleSizes) =>
      log.debug(s"Count finished : ${nodeRepo.counted.getAbsolutePath}")
      val sorter = context.actorOf(Sorter.props(nodeRepo))
      sorters = sorter :: sorters
      collators = collators.filter(collator => collator != sender)
      FileUtils.deleteQuietly(nodeRepo.sorted)
      nodeRepo.setStatus(Json.obj(
        "uniqueCount" -> uniqueCount,
        "samples" -> sampleSizes
      ))
      sorter ! Sort(SortType.HISTOGRAM_SORT)

    case Sorted(nodeRepo, sortedFile, sortType) =>
      log.debug(s"Sort finished : ${sortedFile.getAbsolutePath}")
      sorters = sorters.filter(sorter => sender != sorter)
      sortType match {
        case SortType.VALUE_SORT =>
          FileUtils.deleteQuietly(nodeRepo.values)
          val collator = context.actorOf(Collator.props(nodeRepo))
          collators = collator :: collators
          collator ! Count()

        case SortType.HISTOGRAM_SORT =>
          log.debug(s"writing histograms : ${fileRepo.dir.getAbsolutePath}")
          Repo.updateJson(nodeRepo.status) {
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
            fileRepo.setStatus(ANALYZING, workers = sorters.size + collators.size)
          }
      }

    case AnalysisError(file) =>
      log.info(s"File error at ${fileRepo.dir.getName}")
      FileUtils.deleteQuietly(file)
      FileUtils.deleteQuietly(fileRepo.dir)
      context.stop(self)

    case AnalysisTreeComplete(json, digest) =>
      fileRepo.setStatus(ANALYZING, workers = sorters.size + collators.size)
      log.info(s"Tree Complete at ${fileRepo.dir.getName}, digest=${FileHandling.hex(digest)}")

    case AnalysisComplete() =>
      log.info(s"Analysis Complete, kill: ${self.toString()}")
      fileRepo.setStatus(ANALYZED)
      context.stop(self)

  }
}
