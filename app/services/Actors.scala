package services

import akka.actor.{ActorRef, Props, ActorLogging, Actor}
import java.io._
import scala.language.postfixOps
import scala.io.Source
import java.util.zip.GZIPInputStream
import play.api.libs.json._
import org.apache.commons.io.FileUtils
import scala.collection.mutable.ArrayBuffer
import services.Actors._

object Actors {

  case class AnalyzeThese(jobs: List[(File, File)])

  case class Analyze(file: File, directory: FileAnalysisDirectory)

  case class Progress(progressCount: Long, directory: FileAnalysisDirectory)

  case class TreeComplete(json: JsValue, directory: FileAnalysisDirectory)

  case class AnalysisComplete(directory: FileAnalysisDirectory)

  case class SortType(ordering: Ordering[String])

  object SortType {
    val VALUE_SORT = SortType(Ordering[String])
    val HISTOGRAM_SORT: SortType = SortType(Ordering[String].reverse)
  }

  case class Sort(directory: NodeDirectory, sortType: SortType)

  case class Sorted(directory: NodeDirectory, sortedFile: File, sortType: SortType)

  case class Count(directory: NodeDirectory)

  case class Counted(directory: NodeDirectory, uniqueCount: Int)

  case class Merge(directory: NodeDirectory, inFileA: File, inFileB: File, mergeResultFile: File, sortType: SortType)

  case class Merged(merge: Merge, fileA: File, sortType: SortType)

  def jsonFile(file: File)(xform: JsValue => JsObject) = {
    def input = if (file.exists()) Json.parse(FileUtils.readFileToString(file)) else Json.obj()
    FileUtils.writeStringToFile(file, Json.prettyPrint(xform(input)), "UTF-8")
  }

}

class Boss extends Actor with ActorLogging {

  override def receive: Receive = {

    case AnalyzeThese(jobs) =>
      jobs.map {
        job =>
          val analyzer = context.actorOf(Props[Analyzer], job._1.getName)
          analyzer ! Analyze(job._1, new FileAnalysisDirectory(job._2))
      }

    case Progress(count, directory) =>
      jsonFile(directory.statusFile) {
        current => Json.obj("elements" -> count)
      }

    case TreeComplete(json, directory) =>
      jsonFile(directory.statusFile) {
        current =>
          val elements = (current \ "elements").as[Int]
          Json.obj("elements" -> elements, "index" -> true)
      }
      log.info(s"Tree Complete at ${directory.directory.getName}")

    case AnalysisComplete(directory) =>
      log.info(s"Analysis Complete, kill: ${sender.toString()}")
      context.stop(sender)
      jsonFile(directory.statusFile) {
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

  def writeHistograms(directory: NodeDirectory) = {
    val input = new BufferedReader(new FileReader(directory.histogramTextFile))

    def lineOption = {
      val string = input.readLine()
      if (string != null) Some(string) else None
    }

    def createFile(entries: ArrayBuffer[JsArray], histogramFile: File) = {
      jsonFile(histogramFile) {
        current => Json.obj("histogram" -> entries)
      }
    }

    var accumulators = directory.histogramJsonFiles.map(pair => (pair._1, new ArrayBuffer[JsArray], pair._2))
    var line = lineOption
    var count = 1
    while (line.isDefined && !accumulators.isEmpty) {
      val lineMatch = LINE.findFirstMatchIn(line.get)
      accumulators = accumulators.filter {
        triple =>
          lineMatch.map(groups => triple._2 += Json.arr(groups.group(1), groups.group(2)))
          val keep = count < triple._1
          if (!keep) createFile(triple._2, triple._3)
          keep
      }
      line = lineOption
      count += 1
    }
    accumulators.foreach(triple => createFile(triple._2, triple._3))
    input.close()
  }

  def receive = {

    case Analyze(file, directory) =>
      log.debug(s"Analyzer on ${file.getName}")
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

    case Counted(directory, uniqueCount) =>
      log.debug(s"Count finished : ${directory.countedFile.getAbsolutePath}")
      val sorter = context.actorOf(Props[Sorter])
      sorters = sorter :: sorters
      collators = collators.filter(collator => collator != sender)
      FileUtils.deleteQuietly(directory.sortedFile)
      jsonFile(directory.statusFile) {
        current => Json.obj("uniqueCount" -> uniqueCount)
      }
      sorter ! Sort(directory, SortType.HISTOGRAM_SORT)

    case Sorted(directory, sortedFile, sortType) =>
      log.debug(s"Sort finished : ${sortedFile.getAbsolutePath}")
      sorters = sorters.filter(sorter => sender != sorter)
      sortType match {
        case SortType.VALUE_SORT =>
          FileUtils.deleteQuietly(directory.valuesFile)
          val collator = context.actorOf(Props[Collator])
          collators = collator :: collators
          collator ! Count(directory)

        case SortType.HISTOGRAM_SORT =>
          log.debug(s"writing histograms : ${directory.directory.getAbsolutePath}")
          writeHistograms(directory)
          FileUtils.deleteQuietly(directory.countedFile)
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
      FileUtils.deleteQuietly(inFileA)
      FileUtils.deleteQuietly(inFileB)
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
        jsonFile(sampleFile) {
          current => Json.obj("sample" -> randomSample.values)
        }
      }

      def lineOption = {
        val string = sorted.readLine()
        if (string != null) Some(string) else None
      }

      val counted = new FileWriter(directory.countedFile)
      val unique = new FileWriter(directory.uniqueFile)
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
      samples.foreach(pair => createSampleFile(pair._1, pair._2))
      sender ! Counted(directory, uniqueCount)
  }
}



