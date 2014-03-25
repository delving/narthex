package controllers

import play.api.mvc._
import java.io.File
import play.api.Logger

object FileHandling extends Controller {
  def upload = Action(parse.temporaryFile) {
    request =>
      Logger.info(request.toString())
      request.body.moveTo(new File("/tmp/xml-ray/" + java.util.UUID.randomUUID()))
      Ok("File uploaded")
  }
}
