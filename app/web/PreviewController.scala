package web

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.inject.Inject
import play.api.mvc._
import play.api.libs.ws._
import play.api.libs.json._
import scala.concurrent.{ExecutionContext, Future}
import scala.xml.{Elem, Node}
import scala.util.Try
import organization.OrgContext
import services.CredentialEncryption
import triplestore.GraphProperties._

class PreviewController @Inject() (orgContext: OrgContext)(implicit val ec: ExecutionContext) extends InjectedController {

  def previewHarvestUrl(dataUrl: String): Action[AnyContent] = Action.async { request =>

    // Decode the URL in case it was URL-encoded (e.g., from preview buttons)
    val decodedDataUrl = URLDecoder.decode(dataUrl, StandardCharsets.UTF_8.name())

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

    // Build final URL - if decoded URL already has query params, append with &
    val url = if (queryString.nonEmpty) {
      if (decodedDataUrl.contains("?")) {
        s"$decodedDataUrl&$queryString"
      } else {
        s"$decodedDataUrl?$queryString"
      }
    } else {
      decodedDataUrl
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

  /**
   * Preview JSON harvest - fetches first page from JSON API, transforms to XML
   */
  def previewJsonHarvest(spec: String): Action[AnyContent] = Action.async { request =>
    val dsInfo = orgContext.datasetContext(spec).dsInfo

    // Get JSON harvest config from dataset properties
    val urlOpt = dsInfo.getLiteralProp(harvestURL)
    val itemsPath = dsInfo.getLiteralProp(harvestJsonItemsPath).getOrElse("")
    val idPath = dsInfo.getLiteralProp(harvestJsonIdPath).getOrElse("")
    val pageParam = dsInfo.getLiteralProp(harvestJsonPageParam).getOrElse("page")
    val pageSizeParam = dsInfo.getLiteralProp(harvestJsonPageSizeParam).getOrElse("pagesize")
    val pageSize = dsInfo.getLiteralProp(harvestJsonPageSize).flatMap(s => Try(s.toInt).toOption).getOrElse(10)
    val xmlRoot = dsInfo.getLiteralProp(harvestJsonXmlRoot).getOrElse("records")
    val xmlRecord = dsInfo.getLiteralProp(harvestJsonXmlRecord).getOrElse("record")

    urlOpt match {
      case None =>
        Future.successful(BadRequest("No harvest URL configured"))

      case Some(url) if itemsPath.isEmpty || idPath.isEmpty =>
        Future.successful(BadRequest("JSON harvest requires itemsPath and idPath configuration"))

      case Some(baseUrl) =>
        // Get credentials if configured
        val credentialsOpt: Option[(String, String)] = {
          val username = dsInfo.getLiteralProp(harvestUsername).getOrElse("")
          val encryptedPassword = dsInfo.getLiteralProp(harvestPassword).getOrElse("")
          if (username.nonEmpty && encryptedPassword.nonEmpty) {
            val password = CredentialEncryption.decrypt(encryptedPassword, orgContext.appConfig.appSecret)
            Some((username, password))
          } else {
            None
          }
        }

        // Get API key if configured
        val apiKeyOpt: Option[(String, String)] = {
          val paramName = dsInfo.getLiteralProp(harvestApiKeyParam).getOrElse("")
          val encryptedKey = dsInfo.getLiteralProp(harvestApiKey).getOrElse("")
          if (paramName.nonEmpty && encryptedKey.nonEmpty) {
            val keyValue = CredentialEncryption.decrypt(encryptedKey, orgContext.appConfig.appSecret)
            Some((paramName, keyValue))
          } else {
            None
          }
        }

        val cleanUrl = baseUrl.stripSuffix("?").stripSuffix("/")

        // Build request with pagination params
        var wsRequest = orgContext.wsApi.url(cleanUrl)
          .withQueryStringParameters(
            pageParam -> "1",
            pageSizeParam -> pageSize.toString
          )

        // Add API key if configured
        apiKeyOpt.foreach { case (paramName, keyValue) =>
          wsRequest = wsRequest.withQueryStringParameters(paramName -> keyValue)
        }

        // Add Basic Auth if configured
        credentialsOpt.foreach { case (username, password) =>
          val encoded = Base64.getEncoder.encodeToString(s"$username:$password".getBytes(StandardCharsets.UTF_8))
          wsRequest = wsRequest.withHttpHeaders("Authorization" -> s"Basic $encoded")
        }

        wsRequest.get().map { response =>
          if (response.status != 200) {
            InternalServerError(s"JSON API returned status ${response.status}: ${response.statusText}")
          } else {
            try {
              val json = response.json

              // Extract items using path
              val items = extractJsonPath(json, itemsPath) match {
                case JsArray(arr) => arr.toSeq
                case _ => Seq.empty
              }

              if (items.isEmpty) {
                Ok(s"<$xmlRoot><error>No records found at path '$itemsPath'</error></$xmlRoot>").as("application/xml")
              } else {
                // Transform to XML (limit to first 10 for preview)
                val previewItems = items.take(10)
                val xmlRecords = previewItems.map { item =>
                  jsonRecordToXml(item, xmlRecord, idPath)
                }
                val xmlString = wrapRecordsInXml(xmlRecords, xmlRoot)
                Ok(xmlString).as("application/xml")
              }
            } catch {
              case e: Exception =>
                InternalServerError(s"Error parsing JSON: ${e.getMessage}")
            }
          }
        }.recover {
          case e: Exception => InternalServerError(s"Error retrieving JSON from $cleanUrl: ${e.getMessage}")
        }
    }
  }

  // JSON path extraction using dot notation
  private def extractJsonPath(json: JsValue, path: String): JsValue = {
    if (path.isEmpty) json
    else path.split("\\.").foldLeft(json) { (js, key) =>
      (js \ key).getOrElse(JsNull)
    }
  }

  // Convert JSON value to XML nodes
  private def jsonToXmlNodes(key: String, value: JsValue): Seq[Node] = {
    value match {
      case JsNull => Seq.empty
      case JsBoolean(b) => Seq(Elem(null, key, scala.xml.Null, scala.xml.TopScope, false, scala.xml.Text(b.toString)))
      case JsNumber(n) => Seq(Elem(null, key, scala.xml.Null, scala.xml.TopScope, false, scala.xml.Text(n.toString)))
      case JsString(s) => Seq(Elem(null, key, scala.xml.Null, scala.xml.TopScope, false, scala.xml.Text(s)))
      case JsArray(arr) =>
        arr.flatMap(item => jsonToXmlNodes(key, item)).toSeq
      case obj: JsObject =>
        val children = obj.fields.flatMap { case (k, v) => jsonToXmlNodes(k, v) }
        Seq(Elem(null, key, scala.xml.Null, scala.xml.TopScope, false, children: _*))
    }
  }

  // Convert JSON record to XML element with id attribute
  private def jsonRecordToXml(json: JsValue, recordName: String, idPath: String): Elem = {
    val id = extractJsonPath(json, idPath) match {
      case JsString(s) => s
      case JsNumber(n) => n.toString
      case _ => ""
    }
    val children = json match {
      case obj: JsObject =>
        obj.fields.flatMap { case (k, v) => jsonToXmlNodes(k, v) }
      case _ => Seq.empty
    }
    Elem(null, recordName, new scala.xml.UnprefixedAttribute("id", id, scala.xml.Null), scala.xml.TopScope, false, children: _*)
  }

  // Wrap records in root element
  private def wrapRecordsInXml(records: Seq[Elem], rootName: String): String = {
    val root = Elem(null, rootName, scala.xml.Null, scala.xml.TopScope, false, records: _*)
    root.toString()
  }
}