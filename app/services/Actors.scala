package services

import akka.actor.{ActorRef, Props, ActorLogging, Actor}
import java.io._
import scala.language.postfixOps
import scala.io.Source
import java.util.zip.GZIPInputStream
import play.api.libs.json._
import org.apache.commons.io.FileUtils

case class AnalyzeThese(jobs: List[(File, File)])

case class Analyze(file: File, directory: File)

case class SortType(name: String, ordering: Ordering[String])

case class Sort(valuesFile: File, sortedFile: File, sortType: SortType)

case class Sorted(sortedFile: File, sortType: SortType)

case class Count(sortedFile: File)

case class Counted(countedFile: File)

case class Merge(fileA: File, fileB: File, sortedFile: File, sortType: SortType)

case class Merged(merge: Merge, fileA: File, sortedFile: File, sortType: SortType)

case class TreeComplete(json: JsValue, directory: File)

case class Progress(progressCount: Long, directory: File)

case class AnalysisComplete()

class Boss extends Actor with ActorLogging {

  override def receive: Receive = {

    case AnalyzeThese(jobs) =>
      log.info(s"Boss has ${jobs.size} jobs to do")
      jobs.map {
        job =>
          val analyzer = context.actorOf(Props[Analyzer], job._1.getName)
          analyzer ! Analyze(job._1, job._2)
      }

    case Progress(count, directory) =>
      val statusFile: File = FileRepository.statusFile(directory)
      val json = Json.obj("count" -> count, "complete" -> (count < 0))
      FileUtils.writeStringToFile(statusFile, Json.prettyPrint(json), "UTF-8")

    case TreeComplete(json, directory) =>
      log.info(s"Tree Complete at ${directory.getName}")

    case AnalysisComplete() =>
      log.info(s"Analysis Complete, kill: ${sender.toString()}")
      context.stop(sender)

  }
}

class Analyzer extends Actor with XRay with ActorLogging {

  var sorters = List.empty[ActorRef]
  var counters = List.empty[ActorRef]

  def receive = {

    case Analyze(file, directory) =>
      log.info(s"Analyzer on ${file.getName}")
      val source = Source.fromInputStream(new GZIPInputStream(new FileInputStream(file)))
      val root: XRayNode = XRayNode(source, directory, count => sender ! Progress(count, directory))
      source.close()
      root.sort {
        node =>
          val sorter = context.actorOf(Props[Sorter])
          sorters = sorter :: sorters
          val valuesFile: File = FileRepository.valuesFile(node.directory)
          val sortedFile: File = FileRepository.sortedFile(node.directory)
          sorter ! Sort(valuesFile, sortedFile, SortType("Values", Ordering[String]))
      }
      sender ! TreeComplete(Json.toJson(root), directory)

    case Counted(countedFile) =>
      log.info(s"Count finished : ${countedFile.getAbsolutePath}")
      val sorter = context.actorOf(Props[Sorter])
      sorters = sorter :: sorters
      counters.filter(counter => counter != sender)
      val histogramText: File = FileRepository.histogramTextFile(countedFile.getParentFile)
      sorter ! Sort(countedFile, histogramText, SortType("Counts", Ordering[String].reverse))

    case Sorted(sortedFile, sortType) =>
      log.info(s"Sort finished : ${sortedFile.getAbsolutePath}")
      sorters = sorters.filter(sorter => sender != sorter)
      sortType.name match {
        case "Values" =>
          val counter = context.actorOf(Props[CounterActor])
          counters = counter :: counters
          counter ! Count(sortedFile)

        case "Counts" =>
          if (sorters.isEmpty && counters.isEmpty) {
            context.parent ! AnalysisComplete()
          }
      }
  }
}

class Sorter extends Actor with ActorLogging {

  val linesToSort = 10000
  var sortFiles = List.empty[File]
  var merges = List.empty[Merge]

  def initiateMerges(sortedFile: File, sortType: SortType): Unit = {
    while (sortFiles.size > 1) {
      sortFiles = sortFiles match {
        case a :: b :: remainder =>
          val merge: Merge = Merge(a, b, sortedFile, sortType)
          merges = merge :: merges
          val merger = context.actorOf(Props[Merger])
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
    context.parent ! Sorted(sortedFile, sortType)
  }

  def receive = {

    case Sort(valuesFile, sortedFile, sortType) =>
      log.info(s"Sorter on ${valuesFile.getName}")
      val input = new BufferedReader(new FileReader(valuesFile))

      var lines = List.empty[String]
      def dumpSortedToFile() = {
        val outputFile = FileRepository.tempSortFile(valuesFile.getParentFile)
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
      initiateMerges(sortedFile, sortType)
      if (merges.isEmpty) reportSorted(sortedFile, sortType)

    case Merged(merge, file, sortedFile, sortType) =>
      merges = merges.filter(pending => pending != merge)
      log.info(s"Merged : ${file.getName} (size=${merges.size})")
      sortFiles = file :: sortFiles
      if (merges.isEmpty) {
        initiateMerges(sortedFile, sortType)
        if (merges.isEmpty) reportSorted(sortedFile, sortType)
      }
  }
}

class Merger extends Actor with ActorLogging {

  def receive = {

    case Merge(fileA, fileB, sortedFile, sortType) =>
      log.info(s"Merge : ${fileA.getName} and ${fileB.getName}")
      val inputA = new BufferedReader(new FileReader(fileA))
      val inputB = new BufferedReader(new FileReader(fileB))

      def lineOption(reader: BufferedReader) = {
        val string = reader.readLine()
        if (string != null) Some(string) else None
      }

      val outputFile = FileRepository.tempSortFile(fileA.getParentFile)
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
      FileUtils.deleteQuietly(fileA)
      FileUtils.deleteQuietly(fileB)
      sender ! Merged(Merge(fileA, fileB, sortedFile, sortType), outputFile, sortedFile, sortType)
  }
}


class CounterActor extends Actor with ActorLogging {

  def receive = {

    case Count(sortedFile) =>
      log.info(s"Count : ${sortedFile.getName}")
      val sorted = new BufferedReader(new FileReader(sortedFile))

      def lineOption = {
        val string = sorted.readLine()
        if (string != null) Some(string) else None
      }

      val countedFile = FileRepository.countedFile(sortedFile.getParentFile)
      val counted = new FileWriter(countedFile)
      var count: Int = 0
      var previous: Option[String] = None
      var current = lineOption
      while (current.isDefined) {
        if (current == previous) {
          count += 1
        }
        else {
          previous.map(string => counted.write(f"$count%7d $string%s\n"))
          previous = current
          count = 1
        }
        current = lineOption
      }
      previous.map(string => counted.write(f"$count%7d $string%s\n"))
      sorted.close()
      counted.close()
      sender ! Counted(countedFile)
  }
}



