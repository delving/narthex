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

import java.io._
import play.api.libs.json._
import akka.actor.SupervisorStrategy.Escalate
import akka.actor._
import org.apache.commons.io.FileUtils.deleteQuietly

import analysis.Analyzer._
import analysis.Collator._
import analysis.Merger._
import analysis.NodeRepo._
import analysis.Sorter._
import analysis.TreeNode.RandomSample
import dataset.DatasetActor.WorkFailure
import dataset.DatasetContext
import nxutil.Utils.actorWork
import services.FileHandling.{createReader, createWriter, sourceFromFile}
import services.ProgressReporter.ProgressState._
import services._

import scala.concurrent._
import scala.util.{Failure, Success}

object Analyzer {

  /** Analysis type enumeration - replaces boolean processed flag */
  object AnalysisType extends Enumeration {
    type AnalysisType = Value
    val RAW, SOURCE, PROCESSED = Value
  }
  import AnalysisType._

  /** Message to start analysis. Optional treeRoot and indexFile for source analysis. */
  case class AnalyzeFile(
    file: File,
    analysisType: AnalysisType,
    treeRoot: Option[NodeRepo] = None,
    indexFile: Option[File] = None
  )

  case class AnalysisTreeComplete(json: JsValue)

  case class AnalysisComplete(error: Option[String], analysisType: AnalysisType)

  def props(datasetContext: DatasetContext) = Props(new Analyzer(datasetContext))

}

class Analyzer(val datasetContext: DatasetContext) extends Actor with ActorLogging {

  import context.dispatcher
  import Analyzer.AnalysisType
  import Analyzer.AnalysisType._

  val LINE = """^ *(\d*) (.*)$""".r
  var progress: Option[ProgressReporter] = None
  var sorters = List.empty[ActorRef]
  var collators = List.empty[ActorRef]
  var recordCount = 0
  var analysisTypeOpt: Option[AnalysisType] = None
  var customTreeRoot: Option[NodeRepo] = None
  var customIndexFile: Option[File] = None

  override val supervisorStrategy = OneForOneStrategy() {
    case throwable: Throwable =>
      log.info(s"Escalating $throwable")
      Escalate
  }

  def receive = {

    case AnalyzeFile(file, analysisType, treeRootOpt, indexFileOpt) => actorWork(context) {
      log.info(s"Analyzer on $file analysisType=$analysisType")
      analysisTypeOpt = Some(analysisType)
      customTreeRoot = treeRootOpt
      customIndexFile = indexFileOpt

      // Only drop tree if no custom root (DatasetActor handles cleanup for source)
      if (customTreeRoot.isEmpty) {
        datasetContext.dropTree()
      }

      Future {
        val (source, readProgress) = sourceFromFile(file)
        try {
          val progressReporter = ProgressReporter(SPLITTING, context.parent)
          progressReporter.setReadProgress(readProgress)
          progress = Some(progressReporter)
          val tree = TreeNode(source, analysisType, datasetContext, progressReporter,
                              customTreeRoot, customIndexFile)
          progress.get.checkInterrupt()
          tree.launchSorters { node =>
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
        }
        finally {
          source.close()
        }
      } onComplete {
        case Success(_) => ()
        case Failure(e) =>
          context.parent ! WorkFailure(e.getMessage, Some(e))
      }
    }

    case AnalysisTreeComplete(json) => actorWork(context) {
      val progressReporter = ProgressReporter(COLLATING, context.parent)
      progress = Some(progressReporter)
      progressReporter.sendWorkers(sorters.size + collators.size)
      log.info(s"Tree complete")
    }

    case Counted(nodeRepo, uniqueCount, sampleSizes, typeAnalysis, valueStats, patternAnalysis) => actorWork(context) {
      progress.get.checkInterrupt()
      collators = collators.filter(collator => collator != sender())
      deleteQuietly(nodeRepo.sorted)
      var statusJson = Json.obj(
        "uniqueCount" -> uniqueCount,
        "samples" -> sampleSizes,
        "typeInfo" -> typeAnalysis.toJson,
        "valueStats" -> valueStats.toJson
      )
      // Only include patternInfo if patterns were detected
      val patternJson = patternAnalysis.toJson
      if (patternJson.fields.nonEmpty) {
        statusJson = statusJson + ("patternInfo" -> patternJson)
      }
      nodeRepo.setStatus(statusJson)
      val sorter = context.actorOf(Sorter.props(nodeRepo))
      sorters = sorter :: sorters
      sorter ! Sort(SortType.HISTOGRAM_SORT)
    }

    case Sorted(nodeRepo, sortedFile, sortType) => actorWork(context) {
      progress.get.checkInterrupt()
      sorters = sorters.filter(sorter => sender() != sorter)
      sortType match {
        case SortType.VALUE_SORT =>
          deleteQuietly(nodeRepo.values)
          progress.foreach { p =>
            val collator = context.actorOf(Collator.props(nodeRepo))
            collators = collator :: collators
            collator ! Count()
          }

        case SortType.HISTOGRAM_SORT =>
          updateJson(nodeRepo.status) {
            current =>
              val uniqueCount = (current \ "uniqueCount").as[Int]
              val samples = current \ "samples"
              val typeInfo = current \ "typeInfo"
              val valueStats = current \ "valueStats"
              val patternInfo = current \ "patternInfo"
              val histogramSizes = nodeRepo.writeHistograms(uniqueCount)
              var result = Json.obj(
                "uniqueCount" -> uniqueCount,
                "samples" -> samples.get,
                "histograms" -> histogramSizes
              )
              // Preserve typeInfo, valueStats, and patternInfo if present
              typeInfo.asOpt[JsObject].foreach(ti => result = result + ("typeInfo" -> ti))
              valueStats.asOpt[JsObject].foreach(vs => result = result + ("valueStats" -> vs))
              patternInfo.asOpt[JsObject].foreach(pi => result = result + ("patternInfo" -> pi))
              result
          }
          deleteQuietly(nodeRepo.counted)
          progress.foreach { p =>
            if (sorters.isEmpty && collators.isEmpty) {
              context.parent ! AnalysisComplete(None, analysisTypeOpt.get)
            }
            else {
              p.sendWorkers(sorters.size + collators.size)
            }
          }
      }
    }
  }
}

object Collator {

  case class Count()

  case class Counted(
    nodeRepo: NodeRepo,
    uniqueCount: Int,
    sampleFiles: Seq[Int],
    typeAnalysis: TypeDetector.TypeAnalysis,
    valueStats: ValueStats.ValueStatistics,
    patternAnalysis: TypeDetector.PatternAnalysis
  )

  def props(nodeRepo: NodeRepo) = Props(new Collator(nodeRepo))
}

class Collator(val nodeRepo: NodeRepo) extends Actor with ActorLogging {

  override val supervisorStrategy = OneForOneStrategy() {
    case throwable: Throwable =>
      log.info(s"Escalating $throwable")
      Escalate
  }

  def receive = {

    case Count() =>
      val sorted = createReader(nodeRepo.sorted)

      val samples = nodeRepo.sampleJson.map(pair => (new RandomSample(pair._1), pair._2))
      val typeCounter = new TypeDetector.TypeCounter()
      val statsTracker = new ValueStats.StatsTracker()
      val patternTracker = new TypeDetector.PatternTracker()

      def createSampleFile(randomSample: RandomSample, sampleFile: File) = {
        createJson(sampleFile, Json.obj("sample" -> randomSample.values))
      }

      def lineOption = Option(sorted.readLine())

      val counted = createWriter(nodeRepo.counted)
      val unique = createWriter(nodeRepo.uniqueText)
      var occurrences = 0
      var uniqueCount = 0

      def writeValue(string: String) = {
        counted.write(f"$occurrences%7d $string%s\n")
        unique.write(string)
        unique.write("\n")
        samples.foreach(pair => pair._1.record(string))
        typeCounter.record(string)    // Track type for each unique value
        statsTracker.record(string)   // Track value statistics
        patternTracker.record(string) // Track patterns and URI validation
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
      sender() ! Counted(nodeRepo, uniqueCount, usefulSamples.map(pair => pair._1.size),
        typeCounter.getAnalysis, statsTracker.getStatistics, patternTracker.getAnalysis)
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

class Sorter(val nodeRepo: NodeRepo) extends Actor with ActorLogging {
  val linesToSort = 10000
  var sortFiles = List.empty[File]
  var merges = List.empty[Merge]

  override val supervisorStrategy = OneForOneStrategy() {
    case throwable: Throwable =>
      log.info(s"Escalating $throwable")
      Escalate
  }

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
      val input = createReader(inputFile)

      var lines = List.empty[String]
      def dumpSortedToFile() = {
        val outputFile = nodeRepo.tempSort
        val output = createWriter(outputFile)
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

class Merger(val nodeRepo: NodeRepo) extends Actor with ActorLogging {

  def receive = {

    case Merge(inFileA, inFileB, mergeResultFile, sortType) =>
      val inputA = createReader(inFileA)
      val inputB = createReader(inFileB)

      def lineOption(reader: BufferedReader) = Option(reader.readLine())

      val outputFile = nodeRepo.tempSort
      val output = createWriter(outputFile)

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
      deleteQuietly(inFileA)
      deleteQuietly(inFileB)
      sender() ! Merged(Merge(inFileA, inFileB, mergeResultFile, sortType), outputFile, sortType)
  }
}
