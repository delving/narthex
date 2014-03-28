package controllers

import play.api.mvc._
import java.io.File
import play.api.Logger
import services.FileRepository
import play.api.libs.json._
import play.api.libs.functional.syntax._


object FileHandling extends Controller with Security {
  val REPO = new FileRepository("gumby")

  def list = Action {
    val fileList: Seq[File] = REPO.list
    val stringList = fileList.map(file => s"${file.getName}")
    Ok(Json.toJson(stringList))
  }

  def upload = Action(parse.multipartFormData) {
    request =>
      request.body.file("file").map { file =>
        val filename = file.filename
        val contentType = file.contentType
        Logger.info(s"upload $filename ($contentType)")
        file.ref.moveTo(REPO.file(filename))
        Ok(filename)
      }.getOrElse {
        Redirect(routes.Application.index()).flashing(
          "error" -> "Missing file"
        )
      }
  }
}
