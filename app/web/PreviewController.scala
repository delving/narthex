package web

import javax.inject.Inject
import play.api.mvc._
import play.api.libs.ws._
import scala.concurrent.{ExecutionContext, Future}
import organization.OrgContext

class PreviewController (orgContext: OrgContext)(implicit val ec: ExecutionContext) extends Controller() {

  def previewHarvestUrl(dataUrl: String): Action[AnyContent] = Action.async { request =>

    // Extract the incoming query parameters
    val queryParams: Map[String, Seq[String]] = request.queryString

    // Convert the query parameters to the format expected in a URL string
    val queryString = queryParams.map { case (key, values) => 
      values.map(value => s"$key=$value").mkString("&")
    }.mkString("&")

    // Append the query string to the URL
    val url = if (queryString.nonEmpty) {
      s"$dataUrl?$queryString"
    } else {
      s"$dataUrl"
    }

    // Make a request to the external URL
    orgContext.wsApi.url(url).get().flatMap { response =>
      // Extract headers from the response
      val headers = response.allHeaders.map { case (k, v) => k -> v.mkString(",") }

      // Remove "Content-Length" from the headers
      val filteredHeaders = headers.filterNot(_._1.equalsIgnoreCase("Content-Length"))

      // Get the Content-Type header (if it exists)
      val contentTypeHeader = headers.get("Content-Type")
      
      // Build the response
      val result = Ok(response.body)

      // Apply Content-Type if present, otherwise return without setting it explicitly
      // val finalResult = contentTypeHeader match {
      //   case Some(contentType) => result.as(contentType)
      //   case None => result
      // }

      // Return the result with headers, excluding Content-Length
      Future.successful(result.withHeaders(filteredHeaders.toSeq: _*))
    }.recover {
      // Handle errors, like if the URL is incorrect or there's a network issue
      case e: Exception => InternalServerError(s"Error retrieving data: ${e.getMessage}")
    }
  }
}
