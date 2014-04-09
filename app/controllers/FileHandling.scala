package controllers

import play.api.mvc._
import java.io.File
import play.api.Logger
import services._
import play.api.libs.json._
import akka.actor.{Props, ActorSystem}
import akka.pattern.ask
import akka.util.Timeout
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

object FileHandling extends Controller with Security with XRay {

  val actorSystem = ActorSystem("Analysis")

  def list = HasToken() {
    token => email => implicit request => {
      val repo = new FileRepository(email)
      val fileList: Seq[File] = repo.listUploadedFiles
      val stringList = fileList.map(file => s"${file.getName}")
      Ok(Json.toJson(stringList)).withToken(token -> email)
    }
  }

  def upload = HasToken(parse.multipartFormData) {
    token => email => implicit request => {
      val repo = new FileRepository(email)
      request.body.file("file").map {
        file =>
          val filename = file.filename
          val contentType = file.contentType
          Logger.info(s"upload $filename ($contentType) to $email")
          file.ref.moveTo(repo.uploadedFile(filename))
          Ok(filename)
      }.getOrElse {
        Redirect(routes.Application.index()).flashing(
          "error" -> "Missing file"
        )
      }
    }
  }

  def analyze(fileName: String) = HasToken() {
    token => email => implicit request => {
      val repo = new FileRepository(email)
      val file = repo.uploadedFile(fileName)
      if (file.exists()) {
        val analyzer = actorSystem.actorOf(Props[Analyzer], fileName)
        analyzer ! Initialize(file)
        Ok(s"${file.getName} is ${file.length()}")
      }
      else {
        Ok(s"${file.getAbsolutePath} doesn't exist!")
      }
    }
  }

//  def progress(fileName: String) = Action.async {
//    implicit request => {
//
//      val repo = new FileRepository(email)
//      val file = repo.uploadedFile(fileName)
//      if (file.exists()) {
//        val analyzer = actorSystem.actorOf(Props[Analyzer], fileName)
//        implicit val timeout = Timeout(5 seconds)
//        val answer: Future[Any] = analyzer ? GetStatus
//        val status = answer.mapTo[XRayStatus]
//        status.map(xs => Ok(s"${file.getAbsolutePath} "))
//      }
//      else {
//        Future.successful(Ok(s"${file.getAbsolutePath} doesn't exist!"))
//      }
//    }
//  }
}
