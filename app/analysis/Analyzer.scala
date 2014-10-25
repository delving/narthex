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

package analysis

import java.io.{BufferedReader, File, FileReader, FileWriter}

import akka.actor.{Actor, ActorRef, Props}
import analysis.Analyzer._
import analysis.Collator._
import analysis.Merger._
import analysis.Sorter._
import dataset.DatasetState._
import dataset.{DatasetRepo, DatasetState}
import org.OrgActor.ActorShutdown
import org.OrgRepo
import org.apache.commons.io.FileUtils
import play.api.Logger
import play.api.libs.json._
import services._

import scala.concurrent._

/*
 * @author Gerald de Jong <gerald@delving.eu>
 */

object Analyzer {

  case class AnalyzeFile(file: File)

  case class AnalysisTreeComplete(json: JsValue)

  case class AnalysisError(error: String)

  case class AnalysisComplete()

  def props(datasetRepo: DatasetRepo) = Props(new Analyzer(datasetRepo))
}

class Analyzer(val datasetRepo: DatasetRepo) extends Actor with TreeHandling {
  val log = Logger
  val LINE = """^ *(\d*) (.*)$""".r
  var progress: Option[ProgressReporter] = None
  var sorters = List.empty[ActorRef]
  var collators = List.empty[ActorRef]

  def receive = {

    case ActorShutdown(name) =>
      log.info(s"Interrupted analysis $datasetRepo")
      progress.foreach(_.bomb = Some(sender))

    case AnalyzeFile(file) =>
      log.info(s"Analyzer on ${file.getName}")
      val (source, readProgress) = FileHandling.sourceFromFile(file)
      import context.dispatcher
      val f = future {
        val progressReporter = ProgressReporter(datasetRepo.datasetDb)
        progressReporter.setReadProgress(readProgress)
        progress = Some(progressReporter)
        TreeNode(source, file.length, datasetRepo, progressReporter) match {
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
        Logger.info(s"Closing source $datasetRepo")
        source.close()
      }
      f.onFailure {
        case ex: Exception =>
          source.close()
          log.error("Problem reading the file", ex)
          self ! AnalysisError(s"Unable to read ${file.getName}: $ex")
      }

    case AnalysisTreeComplete(json) =>
      datasetRepo.datasetDb.setStatus(ANALYZING)
      val progressReporter = ProgressReporter(datasetRepo.datasetDb)
      progress = Some(progressReporter)
      progressReporter.sendWorkers(sorters.size + collators.size)
      log.info(s"Tree Complete at ${datasetRepo.analyzedDir.getName}")

    case Counted(nodeRepo, uniqueCount, sampleSizes) =>
      collators = collators.filter(collator => collator != sender)
      FileUtils.deleteQuietly(nodeRepo.sorted)
      nodeRepo.setStatus(Json.obj(
        "uniqueCount" -> uniqueCount,
        "samples" -> sampleSizes
      ))
      progress.foreach { p =>
        if (p.keepWorking) {
          val sorter = context.actorOf(Sorter.props(nodeRepo))
          sorters = sorter :: sorters
          sorter ! Sort(SortType.HISTOGRAM_SORT)
        }
      }

    case Sorted(nodeRepo, sortedFile, sortType) =>
      sorters = sorters.filter(sorter => sender != sorter)
      sortType match {
        case SortType.VALUE_SORT =>
          FileUtils.deleteQuietly(nodeRepo.values)
          progress.foreach { p =>
            if (p.keepWorking) {
              val collator = context.actorOf(Collator.props(nodeRepo))
              collators = collator :: collators
              collator ! Count()
            }
          }

        case SortType.HISTOGRAM_SORT =>
          OrgRepo.updateJson(nodeRepo.status) {
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
            progress.foreach { p =>
              p.sendWorkers(sorters.size + collators.size)
            }
          }
      }

    case AnalysisComplete() =>
      progress.foreach { p =>
        if (p.keepWorking) {
          log.info(s"Analysis Complete, kill: ${self.toString()}")
          datasetRepo.datasetDb.setStatus(ANALYZED)
//          val delimit = datasetRepo.datasetDb.getDatasetInfoOption.get \ "delimit"
//          val recordRoot = (delimit \ "recordRoot").text
//          if (recordRoot.nonEmpty) datasetRepo.saveRecords()
          context.stop(self)
        }
        else {
          self ! AnalysisError("Interrupted")
        }
      }

    case AnalysisError(error) =>
      log.info(s"Analysis error: $error")
      datasetRepo.datasetDb.setStatus(READY, error = error)
      FileUtils.deleteQuietly(datasetRepo.analyzedDir)
      context.stop(self)

  }
}

object Collator {

  case class Count()

  case class Counted(nodeRepo: NodeRepo, uniqueCount: Int, sampleFiles: Seq[Int])

  def props(nodeRepo: NodeRepo) = Props(new Collator(nodeRepo))
}

class Collator(val nodeRepo: NodeRepo) extends Actor with TreeHandling {

  def receive = {

    case Count() =>
      val sorted = new BufferedReader(new FileReader(nodeRepo.sorted))

      val samples = nodeRepo.sampleJson.map(pair => (new RandomSample(pair._1), pair._2))

      def createSampleFile(randomSample: RandomSample, sampleFile: File) = {
        OrgRepo.createJson(sampleFile, Json.obj("sample" -> randomSample.values))
      }

      def lineOption = {
        val string = sorted.readLine()
        if (string != null) Some(string) else None
      }

      val counted = new FileWriter(nodeRepo.counted)
      val unique = new FileWriter(nodeRepo.uniqueText)
      var occurrences = 0
      var uniqueCount = 0

      def writeValue(string: String) = {
        counted.write(f"$occurrences%7d $string%s\n")
        unique.write(string)
        unique.write("\n")
        samples.foreach(pair => pair._1.record(string))
        uniqueCount += 1
      }

      var previous: Option[String] = None
      var current = lineOption
      while (current.isDefined) {
        if (current == previous) {
          occurrences += 1
        }
        else {
          previous.foreach(writeValue)
          previous = current
          occurrences = 1
        }
        current = lineOption
      }
      previous.foreach(writeValue)
      sorted.close()
      counted.close()
      unique.close()
      val bigEnoughSamples = samples.filter(pair => uniqueCount > pair._1.size * 2)
      val usefulSamples = if (bigEnoughSamples.isEmpty) List(samples.head) else bigEnoughSamples
      usefulSamples.foreach(pair => createSampleFile(pair._1, pair._2))
      sender ! Counted(nodeRepo, uniqueCount, usefulSamples.map(pair => pair._1.size))
  }
}

object Sorter {

  case class SortType(ordering: Ordering[String])

  object SortType {
    val VALUE_SORT = SortType(Ordering[String])
    val HISTOGRAM_SORT: SortType = SortType(Ordering[String].reverse)
  }

  case class Sort(sortType: SortType)

  case class Sorted(nodeRepo: NodeRepo, sortedFile: File, sortType: SortType)

  def props(nodeRepo: NodeRepo) = Props(new Sorter(nodeRepo))
}

class Sorter(val nodeRepo: NodeRepo) extends Actor {
  val linesToSort = 10000
  var sortFiles = List.empty[File]
  var merges = List.empty[Merge]

  def initiateMerges(outFile: File, sortType: SortType): Unit = {
    while (sortFiles.size > 1) {
      sortFiles = sortFiles match {
        case inFileA :: inFileB :: remainder =>
          val merge: Merge = Merge(inFileA, inFileB, outFile, sortType)
          merges = merge :: merges
          val merger = context.actorOf(Merger.props(nodeRepo))
          merger ! merge
          remainder
        case tooSmall =>
          tooSmall

      }
    }
  }

  def reportSorted(sortedFile: File, sortType: SortType): Unit = {
    sortFiles.head.renameTo(sortedFile)
    sortFiles = List.empty
    context.parent ! Sorted(nodeRepo, sortedFile, sortType)
  }

  def receive = {

    case Sort(sortType) =>
      val (inputFile, sortedFile) = sortType match {
        case SortType.VALUE_SORT => (nodeRepo.values, nodeRepo.sorted)
        case SortType.HISTOGRAM_SORT => (nodeRepo.counted, nodeRepo.histogramText)
      }
      val input = new BufferedReader(new FileReader(inputFile))

      var lines = List.empty[String]
      def dumpSortedToFile() = {
        val outputFile = nodeRepo.tempSort
        val output = new FileWriter(outputFile)
        lines.sorted(sortType.ordering).foreach {
          line =>
            output.write(line)
            output.write("\n")
        }
        output.close()
        lines = List.empty[String]
        sortFiles = outputFile :: sortFiles
      }

      var count = linesToSort
      while (count > 0) {
        val line = input.readLine()
        if (line == null) {
          if (lines.nonEmpty) dumpSortedToFile()
          count = 0
        }
        else {
          lines = line :: lines
          count -= 1
          if (count == 0) {
            dumpSortedToFile()
            count = linesToSort
          }
        }
      }
      input.close()
      initiateMerges(sortedFile, sortType)
      if (merges.isEmpty) reportSorted(sortedFile, sortType)

    case Merged(merge, file, sortType) =>
      merges = merges.filter(pending => pending != merge)
      sortFiles = file :: sortFiles
      if (merges.isEmpty) {
        initiateMerges(merge.mergeResultFile, sortType)
        if (merges.isEmpty) reportSorted(merge.mergeResultFile, sortType)
      }
  }
}

object Merger {

  case class Merge(inFileA: File, inFileB: File, mergeResultFile: File, sortType: SortType)

  case class Merged(merge: Merge, fileA: File, sortType: SortType)

  def props(nodeRepo: NodeRepo) = Props(new Merger(nodeRepo))
}

class Merger(val nodeRepo: NodeRepo) extends Actor {

  def receive = {

    case Merge(inFileA, inFileB, mergeResultFile, sortType) =>
      val inputA = new BufferedReader(new FileReader(inFileA))
      val inputB = new BufferedReader(new FileReader(inFileB))

      def lineOption(reader: BufferedReader) = {
        val string = reader.readLine()
        if (string != null) Some(string) else None
      }

      val outputFile = nodeRepo.tempSort
      val output = new FileWriter(outputFile)

      def write(line: Option[String]) = {
        output.write(line.get)
        output.write("\n")
      }

      var lineA: Option[String] = lineOption(inputA)
      var lineB: Option[String] = lineOption(inputB)
      while (lineA.isDefined || lineB.isDefined) {
        if (lineA.isDefined && lineB.isDefined) {
          val comparison = sortType.ordering.compare(lineA.get, lineB.get)
          if (comparison < 0) {
            write(lineA)
            lineA = lineOption(inputA)
          }
          else {
            write(lineB)
            lineB = lineOption(inputB)
          }
        }
        else if (lineA.isDefined) {
          write(lineA)
          lineA = lineOption(inputA)
        }
        else if (lineB.isDefined) {
          write(lineB)
          lineB = lineOption(inputB)
        }
      }
      output.close()
      inputA.close()
      inputB.close()
      FileUtils.deleteQuietly(inFileA)
      FileUtils.deleteQuietly(inFileB)
      sender ! Merged(Merge(inFileA, inFileB, mergeResultFile, sortType), outputFile, sortType)
  }
}
