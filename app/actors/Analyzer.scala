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

import akka.actor.{Props, ActorRef, ActorLogging, Actor}
import services._
import play.api.libs.json._
import org.apache.commons.io.FileUtils
import java.io.{File, FileReader, BufferedReader}
import scala.collection.mutable.ArrayBuffer
import scala.util.Failure
import scala.Some
import scala.util.Success
import java.security.MessageDigest
import Analyzer._
import Sorter._
import Collator._

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
       Repo.updateJson(fileRepo.status) {
         current => Json.obj("percent" -> 0)
       }
       val (source, countingStream, digest) = FileHandling.countingSource(file)
       val progress = context.actorOf(Props(new Actor() {
         override def receive: Receive = {
           case AnalysisProgress(percent) =>
             Repo.updateJson(fileRepo.status) {
               current => Json.obj("percent" -> percent)
             }
         }
       }))
       def sendProgress(percent: Int) = progress ! AnalysisProgress(percent)
       TreeNode(source, file.length, countingStream, fileRepo, sendProgress) match {
         case Success(tree) =>
           tree.launchSorters {
             node =>
               if (node.lengths.isEmpty) {
                 Repo.updateJson(node.nodeRepo.status) {
                   current => Json.obj("uniqueCount" -> 0)
                 }
               }
               else {
                 Repo.updateJson(node.nodeRepo.status) {
                   current => Json.obj("percent" -> 0)
                 }
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
       Repo.updateJson(nodeRepo.status) {
         current => Json.obj(
           "uniqueCount" -> uniqueCount,
           "samples" -> sampleSizes
         )
       }
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
               val histogramSizes = writeHistograms(nodeRepo, uniqueCount)
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
             Repo.updateJson(fileRepo.status) {
               current =>
                 Json.obj(
                   "index" -> true,
                   "workers" -> (sorters.size + collators.size)
                 )
             }
           }
       }

     case AnalysisError(file) =>
       log.info(s"File error at ${fileRepo.dir.getName}")
       FileUtils.deleteQuietly(file)
       FileUtils.deleteQuietly(fileRepo.dir)
       context.stop(self)

     case AnalysisTreeComplete(json, digest) =>
       Repo.updateJson(fileRepo.status) {
         current =>
           Json.obj(
             "index" -> true,
             "workers" -> (sorters.size + collators.size)
           )
       }
       // todo: rename the original file to include date and digest
       log.info(s"Tree Complete at ${fileRepo.dir.getName}, digest=${FileHandling.hex(digest)}")

     case AnalysisComplete() =>
       log.info(s"Analysis Complete, kill: ${self.toString()}")
       Repo.updateJson(fileRepo.status) {
         current =>
           Json.obj("index" -> true, "complete" -> true)
       }
       context.stop(self)

   }

   def writeHistograms(nodeRepo: NodeRepo, uniqueCount: Int) = {
     val input = new BufferedReader(new FileReader(nodeRepo.histogramText))

     def lineOption = {
       val string = input.readLine()
       if (string != null) Some(string) else None
     }

     def createFile(maximum: Int, entries: ArrayBuffer[JsArray], histogramFile: File) = {
       Repo.updateJson(histogramFile) {
         current => Json.obj(
           "uniqueCount" -> uniqueCount,
           "entries" -> entries.size,
           "maximum" -> maximum,
           "complete" -> (entries.size == uniqueCount),
           "histogram" -> entries
         )
       }
     }

     var activeCounters = nodeRepo.histogramJson.map(pair => (pair._1, new ArrayBuffer[JsArray], pair._2))
     activeCounters = activeCounters.filter(pair => pair._1 == activeCounters.head._1 || uniqueCount > pair._1 / nodeRepo.sizeFactor)
     val counters = activeCounters
     var line = lineOption
     var count = 1
     while (line.isDefined && !activeCounters.isEmpty) {
       val lineMatch = LINE.findFirstMatchIn(line.get)
       activeCounters = activeCounters.filter {
         triple =>
           lineMatch.map(groups => triple._2 += Json.arr(groups.group(1), groups.group(2)))
           val keep = count < triple._1
           if (!keep) createFile(triple._1, triple._2, triple._3) // side effect
           keep
       }
       line = lineOption
       count += 1
     }
     activeCounters.foreach(triple => createFile(triple._1, triple._2, triple._3))
     counters.map(triple => triple._1)
   }


 }
