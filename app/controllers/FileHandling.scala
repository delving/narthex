package controllers

import play.api.mvc._
import java.io.File
import play.api.Logger
import services.FileRepository
import play.api.libs.json._


object FileHandling extends Controller with Security {

  def list = HasToken() {
    token => email => implicit request => {
      val repo = new FileRepository(email)
      val fileList: Seq[File] = repo.list
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
          file.ref.moveTo(repo.file(filename))
          Ok(filename)
      }.getOrElse {
        Redirect(routes.Application.index()).flashing(
          "error" -> "Missing file"
        )
      }
    }
  }
}
