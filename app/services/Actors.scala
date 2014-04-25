package services

import akka.actor.{Props, ActorLogging, Actor}
import java.io._
import scala.language.postfixOps
import scala.io.Source
import java.util.zip.GZIPInputStream
import play.api.libs.json._
import org.apache.commons.io.FileUtils

case class AnalyzeThese(jobs: List[(File, File)])

case class Analyze(file: File, directory: File)

case class Sort(valuesFile: File)

case class Sorted(sortedFile: File)

case class Count(sortedFile: File)

case class Counted(countedFile: File)

case class Merge(fileA: File, fileB: File)

case class Merged(merge: Merge, fileA: File)

case class TreeComplete(json: JsValue, directory: File)

case class Progress(progressCount: Long, directory: File)

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

  }
}

class Analyzer extends Actor with XRay with ActorLogging {

  def receive = {

    case Analyze(file, directory) =>
      log.info(s"Analyzer on ${file.getName}")
      val source = Source.fromInputStream(new GZIPInputStream(new FileInputStream(file)))
      val root: XRayNode = XRayNode(source, directory, {
        count =>
          sender ! Progress(count, directory)
      })
      source.close()
      root.sort { node =>
        val sorter = context.actorOf(Props[Sorter])
        sorter ! Sort(FileRepository.valuesFile(node.directory))
      }
      sender ! TreeComplete(Json.toJson(root), directory)

    case Sorted(sortedFile) =>
      log.info(s"Sort finished : ${sortedFile.getAbsolutePath}")
      val counter = context.actorOf(Props[CounterActor])
      counter ! Count(sortedFile)

    case Counted(countedFile) =>
      log.info(s"Count finished : ${countedFile.getAbsolutePath}")
      // todo: build histogram
        
  }
}

class Sorter extends Actor with ActorLogging {

  val linesToSort = 10000
  var sortFiles = List.empty[File]
  var merges = List.empty[Merge]
  
  def initiateMerges(): Unit = {
    while (sortFiles.size > 1) {
      sortFiles = sortFiles match {
        case a :: b :: remainder =>
          val merge: Merge = Merge(a, b)
          merges = merge :: merges
          val merger = context.actorOf(Props[Merger])
          merger ! merge
          remainder
      }
    }
  }

  def reportSorted(directory: File): Unit = {
    val sortedFile: File = FileRepository.sortedFile(directory)
    sortFiles.head.renameTo(sortedFile)
    sortFiles = List.empty
    context.parent ! Sorted(sortedFile)
  }

  def receive = {

    case Sort(valuesFile) =>
      log.info(s"Sorter on ${valuesFile.getName}")
      val input = new BufferedReader(new FileReader(valuesFile))
      var count = linesToSort
      var lines = List.empty[String]
      
      def dumpSortedToFile() = {
        val outputFile = FileRepository.tempSortedFile(valuesFile.getParentFile)
        val output = new FileWriter(outputFile)
        lines.sorted.foreach {
          line =>
            output.write(line)
            output.write("\n")
        }
        output.close()
        lines = List.empty[String]
        sortFiles = outputFile :: sortFiles
      }
      
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
      initiateMerges()
      if (merges.isEmpty) reportSorted(valuesFile.getParentFile)

    case Merged(merge, file) =>
      merges = merges.filter(pending => pending != merge)
      log.info(s"Merged : ${file.getName} (size=${merges.size})")
      sortFiles = file :: sortFiles
      if (merges.isEmpty) {
        initiateMerges()
        if (merges.isEmpty) reportSorted(file.getParentFile)
      }
  }
}

class Merger extends Actor with ActorLogging {

  def receive = {

    case Merge(fileA, fileB) =>
      log.info(s"Merge : ${fileA.getName} and ${fileB.getName}")
      val inputA = new BufferedReader(new FileReader(fileA))
      val inputB = new BufferedReader(new FileReader(fileB))
      
      def lineOption(reader: BufferedReader) = {
        val string = reader.readLine()
        if (string != null) Some(string) else None
      } 
      
      val outputFile = FileRepository.tempSortedFile(fileA.getParentFile)
      val output = new FileWriter(outputFile)
      
      def write(line: Option[String]) = {
        output.write(line.get)
        output.write("\n")
      }
      
      var lineA: Option[String] = lineOption(inputA)
      var lineB: Option[String] = lineOption(inputB)
      while (lineA.isDefined || lineB.isDefined) {
        if (lineA.isDefined && lineB.isDefined) {
          if (lineA.get < lineB.get) {
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
      sender ! Merged(Merge(fileA, fileB), outputFile)
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
          previous.map{
            string =>
              counted.write(s"$count $string\n")
          }
          previous = current
          count = 1
        }
        current = lineOption
      }
      sorted.close()
      counted.close()
      sender ! Counted(countedFile)
  }
}



