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
import services.Actors._
import org.apache.commons.io.input.BOMInputStream
import scala.util.{Failure, Success}

object Actors {

  case class AnalyzeThese(jobs: List[(File, File)])

  case class Analyze(file: File, directory: FileAnalysisDirectory)

  case class Progress(progressCount: Long, directory: FileAnalysisDirectory)

  case class TreeComplete(json: JsValue, directory: FileAnalysisDirectory)

  case class FileError(file: File, directory: FileAnalysisDirectory)

  case class AnalysisComplete(directory: FileAnalysisDirectory)

  case class SortType(ordering: Ordering[String])

  object SortType {
    val VALUE_SORT = SortType(Ordering[String])
    val HISTOGRAM_SORT: SortType = SortType(Ordering[String].reverse)
  }

  case class Sort(directory: NodeDirectory, sortType: SortType)

  case class Sorted(directory: NodeDirectory, sortedFile: File, sortType: SortType)

  case class Count(directory: NodeDirectory)

  case class Counted(directory: NodeDirectory, uniqueCount: Int, sampleFiles: Seq[Int])

  case class Merge(directory: NodeDirectory, inFileA: File, inFileB: File, mergeResultFile: File, sortType: SortType)

  case class Merged(merge: Merge, fileA: File, sortType: SortType)

  def updateJson(file: File)(xform: JsValue => JsObject) = {
    if (file.exists()) {
      val value = Json.parse(readFileToString(file))
      val tempFile = new File(file.getParentFile, s"${file.getName}.temp")
      writeStringToFile(tempFile, Json.prettyPrint(xform(value)), "UTF-8")
      deleteQuietly(file)
      moveFile(tempFile, file)
    }
    else {
      writeStringToFile(file, Json.prettyPrint(xform(Json.obj())), "UTF-8")
    }
//    def input = if (file.exists()) Json.parse(FileUtils.readFileToString(file)) else Json.obj()
//    FileUtils.writeStringToFile(file, Json.prettyPrint(xform(input)), "UTF-8")
  }

}

class Boss extends Actor with ActorLogging {

  override def receive: Receive = {

    case AnalyzeThese(jobs) =>
      jobs.foreach {
        job =>
          val analyzer = context.actorOf(Props[Analyzer], job._1.getName)
          analyzer ! Analyze(job._1, new FileAnalysisDirectory(job._2))
      }

    case Progress(count, directory) =>
      updateJson(directory.statusFile) {
        current => Json.obj("elements" -> count)
      }

    case FileError(file, directory) =>
      deleteQuietly(file)
      deleteQuietly(directory.directory)
      context.stop(sender)

    case TreeComplete(json, directory) =>
      updateJson(directory.statusFile) {
        current =>
          val elements = (current \ "elements").as[Int]
          Json.obj("elements" -> elements, "index" -> true)
      }
      log.info(s"Tree Complete at ${directory.directory.getName}")

    case AnalysisComplete(directory) =>
      log.info(s"Analysis Complete, kill: ${sender.toString()}")
      context.stop(sender)
      updateJson(directory.statusFile) {
        current =>
          val elements = (current \ "elements").as[Int]
          Json.obj("elements" -> elements, "index" -> true, "complete" -> true)
      }
  }
}

class Analyzer extends Actor with XRay with ActorLogging {

  val LINE = """^ *(\d*) (.*)$""".r
  var sorters = List.empty[ActorRef]
  var collators = List.empty[ActorRef]

  def writeHistograms(directory: NodeDirectory, uniqueCount: Int) = {
    val input = new BufferedReader(new FileReader(directory.histogramTextFile))

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

    var activeCounters = directory.histogramJsonFiles.map(pair => (pair._1, new ArrayBuffer[JsArray], pair._2))
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

  def receive = {

    case Analyze(file, directory) =>
      log.debug(s"Analyzer on ${file.getName}")
      val inputStream = new FileInputStream(file)
      val stream = if (file.getName.endsWith(".gz")) new GZIPInputStream(inputStream) else inputStream
      val source = Source.fromInputStream(new BOMInputStream(stream))
      XRayNode(source, directory, count => sender ! Progress(count, directory)) match {
        case Success(root) =>
          root.sort {
            node =>
              if (node.lengths.isEmpty) {
                updateJson(node.directory.statusFile) {
                  current => Json.obj("uniqueCount" -> 0)
                }
              }
              else {
                val sorter = context.actorOf(Props[Sorter])
                sorters = sorter :: sorters
                sorter ! Sort(node.directory, SortType.VALUE_SORT)
              }
          }
          sender ! TreeComplete(Json.toJson(root), directory)

        case Failure(e) =>
          log.error(e, "Problem reading the file")
          sender ! FileError(file, directory)
      }
      source.close()

    case Counted(directory, uniqueCount, sampleSizes) =>
      log.debug(s"Count finished : ${directory.countedFile.getAbsolutePath}")
      val sorter = context.actorOf(Props[Sorter])
      sorters = sorter :: sorters
      collators = collators.filter(collator => collator != sender)
      deleteQuietly(directory.sortedFile)
      updateJson(directory.statusFile) {
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
          deleteQuietly(directory.valuesFile)
          val collator = context.actorOf(Props[Collator])
          collators = collator :: collators
          collator ! Count(directory)

        case SortType.HISTOGRAM_SORT =>
          log.debug(s"writing histograms : ${directory.directory.getAbsolutePath}")
          updateJson(directory.statusFile) {
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
          deleteQuietly(directory.countedFile)
          if (sorters.isEmpty && collators.isEmpty) {
            context.parent ! AnalysisComplete(directory.fileAnalysisDirectory)
          }
      }
  }
}

class Sorter extends Actor with ActorLogging {

  val linesToSort = 10000
  var sortFiles = List.empty[File]
  var merges = List.empty[Merge]

  def initiateMerges(directory: NodeDirectory, outFile: File, sortType: SortType): Unit = {
    while (sortFiles.size > 1) {
      sortFiles = sortFiles match {
        case inFileA :: inFileB :: remainder =>
          val merge: Merge = Merge(directory, inFileA, inFileB, outFile, sortType)
          merges = merge :: merges
          val merger = context.actorOf(Props[Merger])
          merger ! merge
          remainder
        case tooSmall =>
          tooSmall

      }
    }
  }

  def reportSorted(directory: NodeDirectory, sortedFile: File, sortType: SortType): Unit = {
    sortFiles.head.renameTo(sortedFile)
    sortFiles = List.empty
    context.parent ! Sorted(directory, sortedFile, sortType)
  }

  def receive = {

    case Sort(directory, sortType) =>
      val (inputFile, sortedFile) = sortType match {
        case SortType.VALUE_SORT => (directory.valuesFile, directory.sortedFile)
        case SortType.HISTOGRAM_SORT => (directory.countedFile, directory.histogramTextFile)
      }
      log.debug(s"Sorter on ${inputFile.getName}")
      val input = new BufferedReader(new FileReader(inputFile))

      var lines = List.empty[String]
      def dumpSortedToFile() = {
        val outputFile = directory.tempSortFile
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
          if (!lines.isEmpty) dumpSortedToFile()
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
      initiateMerges(directory, sortedFile, sortType)
      if (merges.isEmpty) reportSorted(directory, sortedFile, sortType)

    case Merged(merge, file, sortType) =>
      merges = merges.filter(pending => pending != merge)
      log.debug(s"Merged : ${file.getName} (size=${merges.size})")
      sortFiles = file :: sortFiles
      if (merges.isEmpty) {
        initiateMerges(merge.directory, merge.mergeResultFile, sortType)
        if (merges.isEmpty) reportSorted(merge.directory, merge.mergeResultFile, sortType)
      }
  }
}

class Merger extends Actor with ActorLogging {

  def receive = {

    case Merge(directory, inFileA, inFileB, mergeResultFile, sortType) =>
      log.debug(s"Merge : ${inFileA.getName} and ${inFileB.getName}")
      val inputA = new BufferedReader(new FileReader(inFileA))
      val inputB = new BufferedReader(new FileReader(inFileB))

      def lineOption(reader: BufferedReader) = {
        val string = reader.readLine()
        if (string != null) Some(string) else None
      }

      val outputFile = directory.tempSortFile
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
      deleteQuietly(inFileA)
      deleteQuietly(inFileB)
      sender ! Merged(Merge(directory, inFileA, inFileB, mergeResultFile, sortType), outputFile, sortType)
  }
}


class Collator extends Actor with ActorLogging with XRay {


  def receive = {

    case Count(directory) =>
      log.debug(s"Count : ${directory.sortedFile.getName}")
      val sorted = new BufferedReader(new FileReader(directory.sortedFile))

      val samples = directory.sampleJsonFiles.map(pair => (new RandomSample(pair._1), pair._2))

      def createSampleFile(randomSample: RandomSample, sampleFile: File) = {
        updateJson(sampleFile) {
          current => Json.obj("sample" -> randomSample.values)
        }
      }

      def lineOption = {
        val string = sorted.readLine()
        if (string != null) Some(string) else None
      }

      val counted = new FileWriter(directory.countedFile)
      val unique = new FileWriter(directory.uniqueTextFile)
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
      sender ! Counted(directory, uniqueCount, usefulSamples.map(pair => pair._1.size))
  }
}