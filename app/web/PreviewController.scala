package web

import javax.inject.Inject
import play.api.mvc._
import play.api.libs.ws._
import scala.concurrent.{ExecutionContext, Future}
import organization.OrgContext

class PreviewController (orgContext: OrgContext)(implicit val ec: ExecutionContext) extends Controller() {

  def previewHarvestUrl(dataUrl: String): Action[AnyContent] = Action.async { request =>

    val queryParams: Map[String, Seq[String]] = request.queryString

    val queryString = queryParams.map { case (key, values) =>
      values.map(value => s"$key=$value").mkString("&")
    }.mkString("&")

    val url = if (queryString.nonEmpty) {
      s"$dataUrl?$queryString"
    } else {
      s"$dataUrl"
    }

    orgContext.wsApi.url(url).get().flatMap { response =>
      val headers = response.allHeaders.map { case (k, v) => k -> v.mkString(",") }

      val filteredHeaders = headers.filterNot { case (key, _) =>
        key.equalsIgnoreCase("Content-Length") || key.equalsIgnoreCase("Content-Type")
      }

      val result = Ok(response.body)
      Future.successful(result.withHeaders(filteredHeaders.toSeq: _*))
    }.recover {
      // Handle errors, like if the URL is incorrect or there's a network issue
      case e: Exception => InternalServerError(s"Error retrieving data: ${e.getMessage}")
    }
  }
}