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
import scala.io.Source
import java.util.zip.GZIPInputStream
import play.api.libs.json._
import org.apache.commons.io.FileUtils._
import scala.collection.mutable.ArrayBuffer
import org.apache.commons.io.input.{CountingInputStream, BOMInputStream}
import services.Repo._
import play.api.libs.json.JsArray
import scala.util.Failure
import scala.Some
import scala.util.Success
import services.ActorMessages._

class Boss extends Actor with ActorLogging {

  override def receive: Receive = {

    case AnalyzeThese(jobs) =>
      jobs.foreach {
        job =>
          val analyzer = context.actorOf(Props[Analyzer], job._1.getName)
          analyzer ! Analyze(job._1, job._2)
      }

    case Progress(count, directory) =>
      updateJson(directory.status) {
        current => Json.obj("percent" -> count)
      }

    case FileError(file, directory) =>
      log.info(s"File error at ${directory.dir.getName}")
      deleteQuietly(file)
      deleteQuietly(directory.dir)
      context.stop(sender)

    case TreeComplete(json, directory) =>
      updateJson(directory.status) {
        current =>
          Json.obj("index" -> true)
      }
      log.info(s"Tree Complete at ${directory.dir.getName}")

    case AnalysisComplete(directory) =>
      log.info(s"Analysis Complete, kill: ${sender.toString()}")
      context.stop(sender)
      updateJson(directory.status) {
        current =>
          Json.obj("index" -> true, "complete" -> true)
      }
  }
}

class Analyzer extends Actor with XRay with ActorLogging {

  val LINE = """^ *(\d*) (.*)$""".r
  var sorters = List.empty[ActorRef]
  var collators = List.empty[ActorRef]

  def receive = {

    case Analyze(file, directory) =>
      log.debug(s"Analyzer on ${file.getName}")
      val countingStream = new CountingInputStream(new FileInputStream(file))
      val stream = if (file.getName.endsWith(".gz")) new GZIPInputStream(countingStream) else countingStream
      val source = Source.fromInputStream(new BOMInputStream(stream))

      def sendProgress(percent: Int) = {
        sender ! Progress(percent, directory)
      }

      XRayNode(source, file.length, countingStream, directory, sendProgress) match {
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
          sender ! TreeComplete(Json.toJson(tree), directory)

        case Failure(e) =>
          log.error(e, "Problem reading the file")
          sender ! FileError(file, directory)
      }
      source.close()

    case Counted(directory, uniqueCount, sampleSizes) =>
      log.debug(s"Count finished : ${directory.counted.getAbsolutePath}")
      val sorter = context.actorOf(Props[Sorter])
      sorters = sorter :: sorters
      collators = collators.filter(collator => collator != sender)
      deleteQuietly(directory.sorted)
      updateJson(directory.status) {
        current => Json.obj(
          "uniqueCount" -> uniqueCount,
          "samples" -> sampleSizes
        )
      }
      sorter ! Sort(directory, SortType.HISTOGRAM_SORT)

    case Sorted(directory, sortedFile, sortType) =>
      log.debug(s"Sort finished : ${sortedFile.getAbsolutePath}")
      sorters = sorters.filter(sorter => sender != sorter)
      sortType match {
        case SortType.VALUE_SORT =>
          deleteQuietly(directory.values)
          val collator = context.actorOf(Props[Collator])
          collators = collator :: collators
          collator ! Count(directory)

        case SortType.HISTOGRAM_SORT =>
          log.debug(s"writing histograms : ${directory.dir.getAbsolutePath}")
          updateJson(directory.status) {
            current =>
              val uniqueCount = (current \ "uniqueCount").as[Int]
              val samples = current \ "samples"
              val histogramSizes = writeHistograms(directory, uniqueCount)
              Json.obj(
                "uniqueCount" -> uniqueCount,
                "samples" -> samples,
                "histograms" -> histogramSizes
              )
          }
          deleteQuietly(directory.counted)
          if (sorters.isEmpty && collators.isEmpty) {
            context.parent ! AnalysisComplete(directory.parent)
          }
      }
  }

  def writeHistograms(directory: NodeRepo, uniqueCount: Int) = {
    val input = new BufferedReader(new FileReader(directory.histogramText))

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

    var activeCounters = directory.histogramJson.map(pair => (pair._1, new ArrayBuffer[JsArray], pair._2))
    activeCounters = activeCounters.filter(pair => pair._1 == activeCounters.head._1 || uniqueCount > pair._1 / directory.sizeFactor)
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
