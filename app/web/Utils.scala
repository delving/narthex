package web

import java.io.{File, FileInputStream, FileNotFoundException}

import play.api.http._
import play.api.libs.iteratee.Enumerator
import play.api.libs.json.Json
import play.api.mvc.{ResponseHeader, Result, _}


object Utils {

  import play.api.libs.concurrent.Execution.Implicits._

  val SIP_APP_VERSION = "1.0.9"
  def okFile(file: File, attempt: Int = 0): Result = {
    try {
      val input = new FileInputStream(file)
      val resourceData = Enumerator.fromStream(input)
      val contentType = if (file.getName.endsWith(".json")) "application/json" else "text/plain; charset=utf-8"
      Result(
        ResponseHeader(Status.OK, Map(
          HeaderNames.CONTENT_LENGTH -> file.length().toString,
          HeaderNames.CONTENT_TYPE -> contentType
        )),
        resourceData
      )
    }
    catch {
      case ex: FileNotFoundException if attempt < 5 => // sometimes status files are in the process of being written
        Thread.sleep(333)
        okFile(file, attempt + 1)
      case x: Throwable =>
        Results.NotFound(Json.obj("file" -> file.getName, "message" -> x.getMessage))
    }
  }

  def okXml(xml: String): Result = {
    Result(
      ResponseHeader(Status.OK, Map(
        HeaderNames.CONTENT_LENGTH -> xml.length().toString,
        HeaderNames.CONTENT_TYPE -> "application/xml"
      )),
      body = Enumerator(xml.getBytes)
    )
  }
}
