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

import akka.actor.{Props, ActorLogging, Actor}
import java.io._
import scala.language.postfixOps
import play.api.libs.json._
import org.apache.commons.io.FileUtils._
import services.Repo._
import scala.Some
import services.ActorMessages._

class Sorter extends Actor with ActorLogging {

  val linesToSort = 10000
  var sortFiles = List.empty[File]
  var merges = List.empty[Merge]

  def initiateMerges(directory: NodeRepo, outFile: File, sortType: SortType): Unit = {
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

  def reportSorted(directory: NodeRepo, sortedFile: File, sortType: SortType): Unit = {
    sortFiles.head.renameTo(sortedFile)
    sortFiles = List.empty
    context.parent ! Sorted(directory, sortedFile, sortType)
  }

  def receive = {

    case Sort(directory, sortType) =>
      val (inputFile, sortedFile) = sortType match {
        case SortType.VALUE_SORT => (directory.values, directory.sorted)
        case SortType.HISTOGRAM_SORT => (directory.counted, directory.histogramText)
      }
      log.debug(s"Sorter on ${inputFile.getName}")
      val input = new BufferedReader(new FileReader(inputFile))

      var lines = List.empty[String]
      def dumpSortedToFile() = {
        val outputFile = directory.tempSort
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

      val outputFile = directory.tempSort
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
      log.debug(s"Count : ${directory.sorted.getName}")
      val sorted = new BufferedReader(new FileReader(directory.sorted))

      val samples = directory.sampleJson.map(pair => (new RandomSample(pair._1), pair._2))

      def createSampleFile(randomSample: RandomSample, sampleFile: File) = {
        updateJson(sampleFile) {
          current => Json.obj("sample" -> randomSample.values)
        }
      }

      def lineOption = {
        val string = sorted.readLine()
        if (string != null) Some(string) else None
      }

      val counted = new FileWriter(directory.counted)
      val unique = new FileWriter(directory.uniqueText)
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