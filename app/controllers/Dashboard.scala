package controllers

import play.api.mvc._
import java.io.{FileNotFoundException, FileInputStream, File}
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
      // todo: ask the boss, because he's writing the files, so he can be the best one to give you one (avoid conflict)
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

  def nodeStatus(fileName: String, path: String) = Secure() {
    token => email => implicit request => {
      val repo = FileRepository(email)
      repo.analysis(fileName).statusFile(path) match {
        case None => NotFound(Json.obj("path" -> path))
        case Some(file) => fileResult(file)
      }
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

  private def fileResult(file: File, attempt: Int = 0): SimpleResult = {
    try {
      val input = new FileInputStream(file)
      val resourceData = Enumerator.fromStream(input)
      SimpleResult(
        ResponseHeader(OK, Map(CONTENT_LENGTH -> file.length().toString, CONTENT_TYPE -> "application/json")),
        resourceData
      )
    }
    catch {
      case ex: FileNotFoundException if attempt < 3 => // sometimes status files are in the process of being written
        Thread.sleep(10)
        fileResult(file, attempt + 1)
      case x: Throwable =>
        NotFound(Json.obj("file" -> file.getName))
    }
  }
}
