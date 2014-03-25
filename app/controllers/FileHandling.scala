package controllers

import play.api.mvc._
import java.io.File
import play.api.Logger

object FileHandling extends Controller {

  def upload = Action(parse.multipartFormData) {
    request =>
      request.body.file("file").map { file =>
        val filename = file.filename
        val contentType = file.contentType
        Logger.info(s"upload $filename ($contentType)")
        file.ref.moveTo(new File(s"/tmp/xml-ray/$filename"))
        Ok("File uploaded")
      }.getOrElse {
        Redirect(routes.Application.index()).flashing(
          "error" -> "Missing file"
        )
      }
  }

}
