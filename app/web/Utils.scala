package web

import java.io.{File, FileInputStream, FileNotFoundException}

import akka.stream.IOResult
import akka.stream.scaladsl.{Source, StreamConverters}
import akka.util.ByteString
import play.api.http.HttpEntity.Strict
import play.api.http._
import play.api.libs.iteratee.Enumerator
import play.api.libs.json.Json
import play.api.libs.streams.Streams
import play.api.mvc.{ResponseHeader, Result, _}

import scala.concurrent.Future



object Utils {

  import play.api.libs.concurrent.Execution.Implicits._

  val SIP_APP_VERSION = "1.0.9"
  def okFile(file: File, attempt: Int = 0): Result = {
    try {
      val input = new FileInputStream(file)
      val stream: Source[ByteString, Future[IOResult]] = StreamConverters.fromInputStream(() => input)
      val contentType = if (file.getName.endsWith(".json")) "application/json" else "text/plain; charset=utf-8"
      Result(
        ResponseHeader(Status.OK),
        HttpEntity.Streamed(stream, Some(file.length()), Some(contentType))
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
      ResponseHeader(Status.OK),
      HttpEntity.Strict(ByteString(xml), Some("application/xml"))
    )
  }
}
