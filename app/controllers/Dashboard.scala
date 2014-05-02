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
      request.body.file("file") match {
        case Some(file) =>
          Logger.info(s"upload ${file.filename} (${file.contentType}) to $email")
          val repo = FileRepository(email)
          file.ref.moveTo(repo.uploadedFile(file.filename))
          Ok(file.filename)
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
      Ok(Json.toJson(stringList.map(string => Json.obj("name" -> string))))
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
      OkFile(repo.analysis(fileName).statusFile)
    }
  }

  def index(fileName: String) = Secure() {
    token => email => implicit request => {
      println(s"index for $fileName")
      val repo = FileRepository(email)
      OkFile(repo.analysis(fileName).indexFile)
    }
  }

  def nodeStatus(fileName: String, path: String) = Secure() {
    token => email => implicit request => {
      val repo = FileRepository(email)
      repo.analysis(fileName).statusFile(path) match {
        case None => NotFound(Json.obj("path" -> path))
        case Some(file) => OkFile(file)
      }
    }
  }

  def sample(fileName: String, path: String, size: Int) = Secure() {
    token => email => implicit request => {
      val repo = FileRepository(email)
      repo.analysis(fileName).sampleFile(path, size) match {
        case None => NotFound(Json.obj("path" -> path, "size" -> size))
        case Some(file) => OkFile(file)
      }
    }
  }

  def histogram(fileName: String, path: String, size: Int) = Secure() {
    token => email => implicit request => {
      val repo = FileRepository(email)
      repo.analysis(fileName).histogramFile(path, size) match {
        case None => NotFound(Json.obj("path" -> path, "size" -> size))
        case Some(file) => OkFile(file)
      }
    }
  }

  def uniqueText(fileName: String, path: String) = Secure() {
    token => email => implicit request => {
      val repo = FileRepository(email)
      repo.analysis(fileName).uniqueTextFile(path) match {
        case None => NotFound(Json.obj("path" -> path))
        case Some(file) => OkFile(file)
      }
    }
  }

  def histogramText(fileName: String, path: String) = Secure() {
    token => email => implicit request => {
      val repo = FileRepository(email)
      repo.analysis(fileName).histogramTextFile(path) match {
        case None => NotFound(Json.obj("path" -> path))
        case Some(file) => OkFile(file)
      }
    }
  }

  def uniqueTextAPI(fileName: String, path: String, email:String) = Action(parse.anyContent) {
    implicit request => {
      val repo = FileRepository(email)
      repo.analysis(fileName).uniqueTextFile(path) match {
        case None => NotFound(Json.obj("path" -> path))
        case Some(file) => OkFile(file)
      }
    }
  }

  def histogramTextAPI(fileName: String, path: String, email:String) = Action(parse.anyContent) {
    implicit request => {
      val repo = FileRepository(email)
      repo.analysis(fileName).histogramTextFile(path) match {
        case None => NotFound(Json.obj("path" -> path))
        case Some(file) => OkFile(file)
      }
    }
  }

  private def OkFile(file: File, attempt: Int = 0): SimpleResult = {
    try {
      val input = new FileInputStream(file)
      val resourceData = Enumerator.fromStream(input)
      val contentType = if (file.getName.endsWith(".json")) "application/json" else "text/plain"
      SimpleResult(
        ResponseHeader(OK, Map(CONTENT_LENGTH -> file.length().toString, CONTENT_TYPE -> contentType)),
        resourceData
      )
    }
    catch {
      case ex: FileNotFoundException if attempt < 5 => // sometimes status files are in the process of being written
        Thread.sleep(30)
        OkFile(file, attempt + 1)
      case x: Throwable =>
        NotFound(Json.obj("file" -> file.getName))
    }
  }
}
