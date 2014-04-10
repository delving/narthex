package controllers

import play.api.mvc._
import java.io.File
import play.api.Logger
import services._
import play.api.libs.json._
import akka.actor.{ActorRef, ActorSelection, Props, ActorSystem}
import akka.pattern.ask
import akka.util.Timeout
import scala.language.postfixOps
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import play.api.libs.concurrent.Akka
import play.api.Play.current

object FileController extends Controller with Security with XRay {

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

  def list = HasToken() {
    token => email => implicit request => {
      val repo = new FileRepository(email)
      val fileList: Seq[File] = repo.listUploadedFiles
      val stringList = fileList.map(file => s"${file.getName}")
      Ok(Json.toJson(stringList)).withToken(token -> email)
    }
  }

  def analyze(fileName: String) = HasToken() {
    token => email => implicit request => {
      val repo = new FileRepository(email)
      val file = repo.uploadedFile(fileName)
      if (file.exists()) {
        session.get("actor") match {
          case Some(path) =>
            Ok(Json.obj(
              "problem" -> "Analysis actor already exists. Ask for its status instead."
            ))

          case None =>
            val analyzer = Akka.system.actorOf(Props[Analyzer], fileName)
            analyzer ! Initialize(file)
            Ok(Json.obj(
              "message" -> "Initialized",
              "fileLength" -> file.length()
            )).withSession("actor" -> analyzer.path.toString)
        }
      }
      else {
        Ok(Json.obj(
          "problem" -> s"${file.getAbsolutePath} doesn't exist!"
        )).withNewSession
      }
    }
  }

  def status(fileName: String) = HasTokenAsync() {
    token => email => implicit request => {
      val repo = new FileRepository(email)
      val file = repo.uploadedFile(fileName)
      if (file.exists()) {
        session.get("actor") match {
          case Some(path) =>
            implicit val timeout = Timeout(5 seconds)
            val analyzer = Akka.system.actorSelection(path)
            val futureStatus = (analyzer ? GetStatus()).mapTo[Progress]
            futureStatus.map(status => Ok(Json.obj(
              "progressCount" -> status.progressCount,
              "completed" -> status.completed
            )))

          case None =>
            Future.successful(Ok(Json.obj(
              "problem" -> "Actor is not available"
            )).withNewSession)
        }
      }
      else {
        Future.successful(Ok(Json.obj(
          "problem" -> "File does not exist"
        )).withNewSession)
      }
    }
  }
}
