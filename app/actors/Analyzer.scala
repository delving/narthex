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
import actors.Lingo._
import play.api.libs.json._
import org.apache.commons.io.FileUtils
import java.io.{File, FileReader, BufferedReader}
import scala.collection.mutable.ArrayBuffer
import actors.Lingo.Sort
import actors.Lingo.Analyze
import actors.Lingo.AnalysisComplete
import scala.util.Failure
import scala.Some
import actors.Lingo.Counted
import actors.Lingo.Sorted
import actors.Lingo.AnalysisProgress
import actors.Lingo.AnalysisTreeComplete
import scala.util.Success
import actors.Lingo.AnalysisError
import actors.Lingo.Count

/*
 * @author Gerald de Jong <gerald@delving.eu>
 */

object Analyzer {
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
       def sendProgress(percent: Int) = sender ! AnalysisProgress(fileRepo, percent)
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
                 val sorter = context.actorOf(Sorter.props(node.nodeRepo))
                 sorters = sorter :: sorters
                 sorter ! Sort(SortType.VALUE_SORT)
               }
           }
           sender ! AnalysisTreeComplete(fileRepo, Json.toJson(tree), digest)

         case Failure(e) =>
           log.error(e, "Problem reading the file")
           sender ! AnalysisError(fileRepo, file)
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
             context.parent ! AnalysisComplete(nodeRepo.parent)
           }
       }
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
