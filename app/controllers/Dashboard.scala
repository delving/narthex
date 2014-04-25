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

  def list = Secure() {
    token => email => implicit request => {
      val repo = FileRepository(email)
      repo.scanForWork()
      val fileList: Seq[File] = repo.listUploadedFiles
      val stringList = fileList.map(file => s"${file.getName}")
      Ok(Json.toJson(stringList)).withToken(token, email)
    }
  }

  def work = Secure() {
    token => email => implicit request => {
      val repo = FileRepository(email)
      repo.scanForWork()
      Ok(Json.obj("should report how many" -> "or something"))
    }
  }

  def status(fileName: String) = Secure() {
    token => email => implicit request => {
      val repo = FileRepository(email)
      fileResult(repo.statusFile(fileName))
    }
  }

  def analysis(fileName: String) = Secure() {
    token => email => implicit request => {
      val repo = FileRepository(email)
      fileResult(repo.treeFile(fileName))
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
      Ok(Json.obj("problem" -> "no status"))
    }
  }
}
