package controllers

import play.api.mvc._
import java.io.File
import play.api.Logger
import services.{AnalyzeFile, Analyzer, FileRepository}
import play.api.libs.json._
import akka.actor.{Props, ActorSystem}


object FileHandling extends Controller with Security {

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
        analyzer ! AnalyzeFile(file)
        Ok(s"${file.getName} is ${file.length()}")
      }
      else {
        Ok(s"${file.getAbsolutePath} doesn't exist!")
      }
    }

  }
}
