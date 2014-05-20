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

package services

import akka.actor.{ActorRef, Props, ActorLogging, Actor}
import java.io._
import scala.language.postfixOps
import play.api.libs.json._
import org.apache.commons.io.FileUtils._
import scala.collection.mutable.ArrayBuffer
import services.Repo._
import play.api.libs.json.JsArray
import scala.util.Failure
import scala.Some
import scala.util.Success
import services.ActorMessages._
import play.Logger

class Boss extends Actor with ActorLogging {

  override def receive: Receive = {

    case AnalyzeThese(jobs) =>
      jobs.foreach {
        job =>
          val analyzer = context.actorOf(Props[Analyzer], job._1.getName)
          analyzer ! Analyze(job._1, job._2)
      }

    case AnalysisProgress(count, fileRepo) =>
      updateJson(fileRepo.status) {
        current => Json.obj("percent" -> count)
      }

    case AnalysisError(file, fileRepo) =>
      log.info(s"File error at ${fileRepo.dir.getName}")
      deleteQuietly(file)
      deleteQuietly(fileRepo.dir)
      context.stop(sender)

    case AnalysisTreeComplete(json, fileRepo) =>
      updateJson(fileRepo.status) {
        current =>
          Json.obj("index" -> true)
      }
      log.info(s"Tree Complete at ${fileRepo.dir.getName}")

    case AnalysisComplete(fileRepo) =>
      log.info(s"Analysis Complete, kill: ${sender.toString()}")
      context.stop(sender)
      updateJson(fileRepo.status) {
        current =>
          Json.obj("index" -> true, "complete" -> true)
      }

    case SaveRecords(fileRepo, recordRoot, uniqueId) =>
      // todo: save record root and unique id in a JSON file
      var saver = context.actorOf(Props[RecordSaver], fileRepo.dir.getName)
      saver ! SaveRecords(fileRepo, recordRoot, uniqueId)

    case SaveProgress(count, fileRepo) =>
      // todo: record progress in an asset

    case RecordsSaved(fileRepo) =>
      // todo: save the fact that it is finished
      context.stop(sender)
  }
}

class RecordSaver extends Actor with RecordHandling with ActorLogging {

  def receive = {

    case SaveRecords(fileRepo, recordRoot, uniqueId) =>
      val parser = new RecordParser(recordRoot, uniqueId)
      val source = FileHandling.source(fileRepo.sourceFile)
      val totalRecords = 100 // todo: get this from the JSON file
      def sendProgress(percent: Int) = sender ! SaveProgress(percent, fileRepo)
      def receiveRecord(record:String) = Logger.info("\n" + record)
      parser.parse(source, receiveRecord, totalRecords, sendProgress)
      source.close()
      sender ! RecordsSaved(fileRepo)
  }
}

class Analyzer extends Actor with TreeHandling with ActorLogging {

  val LINE = """^ *(\d*) (.*)$""".r
  var sorters = List.empty[ActorRef]
  var collators = List.empty[ActorRef]

  def receive = {

    case Analyze(file, fileRepo) =>
      log.debug(s"Analyzer on ${file.getName}")
      val (countingStream, source) = FileHandling.countingSource(file)
      def sendProgress(percent: Int) = sender ! AnalysisProgress(percent, fileRepo)
      TreeNode(source, file.length, countingStream, fileRepo, sendProgress) match {
        case Success(tree) =>
          tree.launchSorters {
            node =>
              if (node.lengths.isEmpty) {
                updateJson(node.directory.status) {
                  current => Json.obj("uniqueCount" -> 0)
                }
              }
              else {
                val sorter = context.actorOf(Props[Sorter])
                sorters = sorter :: sorters
                sorter ! Sort(node.directory, SortType.VALUE_SORT)
              }
          }
          sender ! AnalysisTreeComplete(Json.toJson(tree), fileRepo)

        case Failure(e) =>
          log.error(e, "Problem reading the file")
          sender ! AnalysisError(file, fileRepo)
      }
      source.close()

    case Counted(fileRepo, uniqueCount, sampleSizes) =>
      log.debug(s"Count finished : ${fileRepo.counted.getAbsolutePath}")
      val sorter = context.actorOf(Props[Sorter])
      sorters = sorter :: sorters
      collators = collators.filter(collator => collator != sender)
      deleteQuietly(fileRepo.sorted)
      updateJson(fileRepo.status) {
        current => Json.obj(
          "uniqueCount" -> uniqueCount,
          "samples" -> sampleSizes
        )
      }
      sorter ! Sort(fileRepo, SortType.HISTOGRAM_SORT)

    case Sorted(fileRepo, sortedFile, sortType) =>
      log.debug(s"Sort finished : ${sortedFile.getAbsolutePath}")
      sorters = sorters.filter(sorter => sender != sorter)
      sortType match {
        case SortType.VALUE_SORT =>
          deleteQuietly(fileRepo.values)
          val collator = context.actorOf(Props[Collator])
          collators = collator :: collators
          collator ! Count(fileRepo)

        case SortType.HISTOGRAM_SORT =>
          log.debug(s"writing histograms : ${fileRepo.dir.getAbsolutePath}")
          updateJson(fileRepo.status) {
            current =>
              val uniqueCount = (current \ "uniqueCount").as[Int]
              val samples = current \ "samples"
              val histogramSizes = writeHistograms(fileRepo, uniqueCount)
              Json.obj(
                "uniqueCount" -> uniqueCount,
                "samples" -> samples,
                "histograms" -> histogramSizes
              )
          }
          deleteQuietly(fileRepo.counted)
          if (sorters.isEmpty && collators.isEmpty) {
            context.parent ! AnalysisComplete(fileRepo.parent)
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
      updateJson(histogramFile) {
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
