package services

import akka.actor.{ActorRef, Props, ActorLogging, Actor}
import java.io._
import scala.language.postfixOps
import scala.io.Source
import java.util.zip.GZIPInputStream
import play.api.libs.json._
import org.apache.commons.io.FileUtils

case class AnalyzeThese(jobs: List[(File, File)])

case class Analyze(file: File, directory: FileAnalysisDirectory)

case class Progress(progressCount: Long, directory: FileAnalysisDirectory)

case class TreeComplete(json: JsValue, directory: FileAnalysisDirectory)

case class SortType(ordering: Ordering[String])

object SortType {
  val VALUE_SORT = SortType(Ordering[String])
  val HISTOGRAM_SORT: SortType = SortType(Ordering[String].reverse)
}

case class Sort(directory: NodeDirectory, sortType: SortType)

case class Sorted(directory: NodeDirectory, sortedFile: File, sortType: SortType)

case class Count(directory: NodeDirectory)

case class Counted(directory: NodeDirectory)

case class Merge(directory: NodeDirectory, inFileA: File, inFileB: File, mergeResultFile: File, sortType: SortType)

case class Merged(merge: Merge, fileA: File, sortType: SortType)

case class AnalysisComplete()

class Boss extends Actor with ActorLogging {

  override def receive: Receive = {

    case AnalyzeThese(jobs) =>
      log.info(s"Boss has ${jobs.size} jobs to do")
      jobs.map {
        job =>
          val analyzer = context.actorOf(Props[Analyzer], job._1.getName)
          analyzer ! Analyze(job._1, new FileAnalysisDirectory(job._2))
      }

    case Progress(count, directory) =>
      val statusFile: File = directory.statusFile
      val json = Json.obj("count" -> count, "complete" -> (count < 0))
      FileUtils.writeStringToFile(statusFile, Json.prettyPrint(json), "UTF-8")

    case TreeComplete(json, directory) =>
      log.info(s"Tree Complete at ${directory.directory.getName}")

    case AnalysisComplete() =>
      log.info(s"Analysis Complete, kill: ${sender.toString()}")
      context.stop(sender)

  }
}

class Analyzer extends Actor with XRay with ActorLogging {

  val LINE = """^ *(\d*) (.*)$""".r
  var sorters = List.empty[ActorRef]
  var collators = List.empty[ActorRef]

  def writeHistograms(directory: NodeDirectory) = {
    val input = new BufferedReader(new FileReader(directory.histogramTextFile))

    def lineOption = {
      val string = input.readLine()
      if (string != null) Some(string) else None
    }

    val writers = directory.histogramJsonFiles.map(pair => (pair._1, new FileWriter(pair._2)))
    writers.foreach(writer => writer._2.write("{\n  histogram : [\n"))
    var activeWriters = writers.map(writer => writer)
    var line = lineOption
    var count = 1
    while (line.isDefined && !activeWriters.isEmpty) {
      val mm = LINE.findFirstMatchIn(line.get)
      line = lineOption
      count += 1
      activeWriters.foreach {
        pair =>
          mm.map {
            mmm =>
              pair._2.write( s"""    [${mmm.group(1)}, "${mmm.group(2)}"]""")
              if (count < pair._1) pair._2.write(",")
              pair._2.write("\n")
          }
      }
      activeWriters = activeWriters.filter(pair => count < pair._1)
    }
    input.close()
    writers.foreach(writer => writer._2.write("  ]\n}"))
    writers.foreach(_._2.close())
  }

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
          sorter ! Sort(node.directory, SortType.VALUE_SORT)
      }
      sender ! TreeComplete(Json.toJson(root), directory)

    case Counted(directory) =>
      log.info(s"Count finished : ${directory.countedFile.getAbsolutePath}")
      val sorter = context.actorOf(Props[Sorter])
      sorters = sorter :: sorters
      collators = collators.filter(collator => collator != sender)
      sorter ! Sort(directory, SortType.HISTOGRAM_SORT)

    case Sorted(directory, sortedFile, sortType) =>
      log.info(s"Sort finished : ${sortedFile.getAbsolutePath}")
      sorters = sorters.filter(sorter => sender != sorter)
      sortType match {
        case SortType.VALUE_SORT =>
          val collator = context.actorOf(Props[Collator])
          collators = collator :: collators
          collator ! Count(directory)

        case SortType.HISTOGRAM_SORT =>
          log.info(s"writing histograms : ${directory.directory.getAbsolutePath}")
          writeHistograms(directory)
          if (sorters.isEmpty && collators.isEmpty) {
            context.parent ! AnalysisComplete()
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
      log.info(s"Sorter on ${inputFile.getName}")
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
      log.info(s"Merged : ${file.getName} (size=${merges.size})")
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
      log.info(s"Merge : ${inFileA.getName} and ${inFileB.getName}")
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
      FileUtils.deleteQuietly(inFileA)
      FileUtils.deleteQuietly(inFileB)
      sender ! Merged(Merge(directory, inFileA, inFileB, mergeResultFile, sortType), outputFile, sortType)
  }
}


class Collator extends Actor with ActorLogging {

  def receive = {

    case Count(directory) =>
      log.info(s"Count : ${directory.sortedFile.getName}")
      val sorted = new BufferedReader(new FileReader(directory.sortedFile))

      def lineOption = {
        val string = sorted.readLine()
        if (string != null) Some(string) else None
      }

      val counted = new FileWriter(directory.countedFile)
      val unique = new FileWriter(directory.uniqueFile)
      var count: Int = 0
      var previous: Option[String] = None
      var current = lineOption
      while (current.isDefined) {
        if (current == previous) {
          count += 1
        }
        else {
          previous.foreach {
            string =>
              counted.write(f"$count%7d $string%s\n")
              unique.write(string)
              unique.write("\n")
          }
          previous = current
          count = 1
        }
        current = lineOption
      }
      previous.map(string => counted.write(f"$count%7d $string%s\n"))
      sorted.close()
      counted.close()
      unique.close()
      sender ! Counted(directory)
  }
}



