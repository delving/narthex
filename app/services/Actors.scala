package services

import akka.actor.{Props, ActorLogging, Actor}
import java.io.{FileInputStream, File}
import scala.language.postfixOps
import scala.io.Source
import java.util.zip.GZIPInputStream
import play.api.libs.json._
import org.apache.commons.io.FileUtils

case class AnalyzeThese(jobs: List[(File, File)])
case class Analyze(file: File, directory: File)
case class AnalysisComplete(json: JsValue, directory: File)
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
      val statusFile: File = new File(directory, FileRepository.statusFileName)
      val json = Json.obj("count" -> count, "complete" -> (count < 0))
      FileUtils.writeStringToFile(statusFile, Json.prettyPrint(json), "UTF-8")

    case AnalysisComplete(json, directory) =>
      log.info(s"AnalysisComplete at ${directory.getName}")

  }
}

class Analyzer extends Actor with XRay with ActorLogging {

  def receive = {

    case Analyze(file, directory) =>
      log.info(s"Analyzer looking at ${file.getName}")
      val source = Source.fromInputStream(new GZIPInputStream(new FileInputStream(file)))
      val root: XRayNode = XRayNode(source, directory, {
        count =>
          sender ! Progress(count, directory)
      })
      sender ! AnalysisComplete(Json.toJson(root), directory)

  }
}