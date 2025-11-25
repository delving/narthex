package web

import javax.inject.Inject
import play.api.mvc._
import play.api.libs.ws._
import scala.concurrent.{ExecutionContext, Future}
import organization.OrgContext

class PreviewController @Inject() (orgContext: OrgContext)(implicit val ec: ExecutionContext) extends InjectedController {

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

      // Preserve Content-Type but filter out Content-Length (which Play recalculates)
      val filteredHeaders = headers.filterNot { case (key, _) =>
        key.equalsIgnoreCase("Content-Length")
      }

      // Get Content-Type for proper response formatting
      val contentTypeOpt = headers.get("Content-Type")

      // Strip XSL stylesheet reference to allow XML to render in browser
      val bodyText = response.body
      val cleanedBody = if (bodyText.contains("<?xml-stylesheet")) {
        bodyText.replaceAll("""<\?xml-stylesheet[^>]*\?>""", "")
      } else {
        bodyText
      }

      val result = contentTypeOpt match {
        case Some(contentType) => Ok(cleanedBody).as(contentType)
        case None => Ok(cleanedBody)
      }

      Future.successful(result.withHeaders(filteredHeaders.toSeq: _*))
    }.recover {
      case e: Exception => InternalServerError(s"Error retrieving data from $url: ${e.getMessage}")
    }
  }
}