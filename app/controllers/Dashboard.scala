package controllers

import play.api.mvc._
import java.io.{FileInputStream, File}
import play.api.Logger
import services._
import play.api.libs.json._
import play.api.libs.iteratee.Enumerator
import scala.concurrent.ExecutionContext.Implicits.global

object Dashboard extends Controller with Security with XRay {

  def upload = Secure(parse.multipartFormData) {
    token => email => implicit request => {
      val repo = FileRepository(email)
      request.body.file("file") match {
        case Some(file) =>
          val filename = file.filename
          val contentType = file.contentType
          Logger.info(s"upload $filename ($contentType) to $email")
          file.ref.moveTo(repo.uploadedFile(filename))
          Ok(filename)
        case None =>
          Redirect(routes.Application.index()).flashing("error" -> "Missing file")
      }
    }
  }

  def list = Secure() {
    token => email => implicit request => {
      val repo = FileRepository(email)
      repo.scanForWork()
      val fileList: Seq[File] = repo.listUploadedFiles
      val stringList = fileList.map(file => s"${file.getName}")
      Ok(Json.toJson(stringList))
    }
  }

  def work = Secure() {
    token => email => implicit request => {
      val repo = FileRepository(email)
      repo.scanForWork()
      Ok
    }
  }

  def status(fileName: String) = Secure() {
    token => email => implicit request => {
      val repo = FileRepository(email)
      fileResult(repo.analysis(fileName).statusFile)
    }
  }

  def index(fileName: String) = Secure() {
    token => email => implicit request => {
      println(s"index for $fileName")
      val repo = FileRepository(email)
      fileResult(repo.analysis(fileName).indexFile)
    }
  }

  def sample(fileName: String, path: String, size: Int) = Secure() {
    token => email => implicit request => {
      val repo = FileRepository(email)
      repo.analysis(fileName).sampleFile(path, size) match {
        case None => NotFound(Json.obj("path" -> path, "size" -> size))
        case Some(file) => fileResult(file)
      }
    }
  }

  def histogram(fileName: String, path: String, size: Int) = Secure() {
    token => email => implicit request => {
      val repo = FileRepository(email)
      repo.analysis(fileName).histogramFile(path, size) match {
        case None => NotFound(Json.obj("path" -> path, "size" -> size))
        case Some(file) => fileResult(file)
      }
    }
  }

  private def fileResult(file: File) = {
    if (file.exists()) {
      val resourceData = Enumerator.fromStream(new FileInputStream(file))
      SimpleResult(
        ResponseHeader(
          OK,
          Map(
            CONTENT_LENGTH -> file.length().toString,
            CONTENT_TYPE -> "application/json"
          )
        ),
        resourceData
      )
    }
    else {
      println(s"index for ${file.getAbsolutePath}")
      NotFound(Json.obj("file" -> file.getName))
    }
  }
}
