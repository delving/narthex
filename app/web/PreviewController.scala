package web

import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.inject.Inject
import play.api.mvc._
import play.api.libs.ws._
import scala.concurrent.{ExecutionContext, Future}
import organization.OrgContext
import services.CredentialEncryption
import triplestore.GraphProperties._

class PreviewController @Inject() (orgContext: OrgContext)(implicit val ec: ExecutionContext) extends InjectedController {

  def previewHarvestUrl(dataUrl: String): Action[AnyContent] = Action.async { request =>

    val queryParams: Map[String, Seq[String]] = request.queryString

    // Extract optional spec parameter for credential lookup
    val specOpt = queryParams.get("__spec").flatMap(_.headOption)

    // Remove internal parameters from the query string
    val cleanedQueryParams = queryParams.filterNot { case (key, _) =>
      key.startsWith("__")
    }

    val queryString = cleanedQueryParams.map { case (key, values) =>
      values.map(value => s"$key=$value").mkString("&")
    }.mkString("&")

    val url = if (queryString.nonEmpty) {
      s"$dataUrl?$queryString"
    } else {
      s"$dataUrl"
    }

    // Look up credentials if spec is provided
    val credentialsOpt: Option[(String, String)] = specOpt.flatMap { spec =>
      val dsInfo = orgContext.datasetContext(spec).dsInfo
      val username = dsInfo.getLiteralProp(harvestUsername).getOrElse("")
      val encryptedPassword = dsInfo.getLiteralProp(harvestPassword).getOrElse("")
      if (username.nonEmpty && encryptedPassword.nonEmpty) {
        val password = CredentialEncryption.decrypt(encryptedPassword, orgContext.appConfig.appSecret)
        Some((username, password))
      } else {
        None
      }
    }

    // Build request with optional Basic Auth header
    val wsRequest = credentialsOpt match {
      case Some((username, password)) =>
        val encoded = Base64.getEncoder.encodeToString(s"$username:$password".getBytes(StandardCharsets.UTF_8))
        orgContext.wsApi.url(url).withHttpHeaders("Authorization" -> s"Basic $encoded")
      case None =>
        orgContext.wsApi.url(url)
    }

    wsRequest.get().flatMap { response =>
      val headers = response.allHeaders.map { case (k, v) => k -> v.mkString(",") }

      // Preserve Content-Type but filter out Content-Length (which Play recalculates)
      val filteredHeaders = headers.filterNot { case (key, _) =>
        key.equalsIgnoreCase("Content-Length")
      }

      // Get Content-Type for proper response formatting (case-insensitive lookup)
      val contentTypeOpt = headers.find { case (key, _) =>
        key.equalsIgnoreCase("Content-Type")
      }.map(_._2)

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