//===========================================================================
//    Copyright 2014 Delving B.V.
//
//    Licensed under the Apache License, Version 2.0 (the "License");
//    you may not use this file except in compliance with the License.
//    You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
//    Unless required by applicable law or agreed to in writing, software
//    distributed under the License is distributed on an "AS IS" BASIS,
//    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//    See the License for the specific language governing permissions and
//    limitations under the License.
//===========================================================================

package harvest

import java.io.{BufferedReader, InputStreamReader}
import java.nio.charset.StandardCharsets
import java.util.Base64
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.xml.{Elem, Node, NodeSeq, XML}
import play.api.Logger
import play.api.libs.json._
import play.api.libs.ws.{WSClient, WSResponse}
import play.shaded.ahc.org.asynchttpclient.netty.NettyResponse
import org.joda.time.DateTime

import dataset.DatasetActor._
import services.Temporal._

object Harvesting {

  private val logger = Logger(getClass)

  case class HarvestType(name: String, recordRoot: String, uniqueId: String, recordContainer: Option[String] = None) {
    override def toString = name

    def matches(otherName: String) = name == otherName
  }

  object HarvestType {
    val PMH = HarvestType(
      name = "pmh",
      recordRoot = "/OAI-PMH/ListRecords/record",
      uniqueId = "/OAI-PMH/ListRecords/record/header/identifier",
      recordContainer = None
    )
    val ADLIB = HarvestType(
      name = "adlib",
      recordRoot = "/adlibXML/recordList/record",
      uniqueId = "/adlibXML/recordList/record/@priref",
      recordContainer = None
    )
    val DOWNLOAD = HarvestType(
      name = "download",
      recordRoot = "/adlibXML/recordList/record",
      uniqueId = "/adlibXML/recordList/record/@priref",
      recordContainer = None
    )
    val JSON = HarvestType(
      name = "json",
      recordRoot = "/records/record",          // Default, configurable via harvestJsonXmlRoot/harvestJsonXmlRecord
      uniqueId = "/records/record/@id",
      recordContainer = None
    )

    val ALL_TYPES = List(PMH, ADLIB, DOWNLOAD, JSON)

    def harvestTypeFromString(string: String): Option[HarvestType] = ALL_TYPES.find(s => s.matches(string))
  }

  case class AdLibDiagnostic(totalItems: Int, current: Int, pageItems: Int) {
    def isLast = current + pageItems >= totalItems

    def percentComplete: Int = {
      val pc = (100 * current) / totalItems
      if (pc < 1) 1 else pc
    }
  }

  case class PMHResumptionToken(value: String, currentRecord: Int, totalRecords: Int, prefix: String) {

    def hasPercentComplete: Boolean = totalRecords > 0 && currentRecord > 0 && currentRecord < totalRecords

    def percentComplete: Int = {
      val pc = (100 * currentRecord) / totalRecords
      if (pc < 1) 1 else pc
    }
  }

  case class HarvestError(error: String, strategy: HarvestStrategy)

  case class NoRecordsMatch(message: String, strategy: HarvestStrategy)

  case class PMHHarvestPage
  (
    records: String,
    url: String,
    set: String,
    metadataPrefix: String,
    totalRecords: Int,
    strategy: HarvestStrategy,
    resumptionToken: Option[PMHResumptionToken],
    deletedIds: List[String] = List.empty,    // Record IDs with status="deleted"
    deletedCount: Int = 0                     // Count of deleted records in this page
  )

  case class AdLibHarvestPage
  (
    records: String,
    url: String,
    database: String,
    search: String,
    strategy: HarvestStrategy,
    diagnostic: AdLibDiagnostic,
    errorOpt: Option[String] = None)

  // JSON harvest configuration and page result
  case class JsonHarvestConfig(
    itemsPath: String,           // JSON path to items array, e.g., "Items" or "data.results"
    idPath: String,              // JSON path to record ID, e.g., "ID" or "record.id"
    totalPath: Option[String],   // JSON path to total count, e.g., "TotalItems"
    pageParam: String,           // Query param for page number, e.g., "page"
    pageSizeParam: String,       // Query param for page size, e.g., "pagesize"
    pageSize: Int,               // Number of records per page
    detailPath: Option[String],  // URL path template for detail fetch, e.g., "/items/{id}"
    skipDetail: Boolean,         // If true, use list records directly (optimization)
    xmlRoot: String,             // Root element name for XML output, e.g., "records"
    xmlRecord: String            // Record element name for XML output, e.g., "record"
  )

  case class JsonDiagnostic(totalItems: Option[Int], current: Int, pageItems: Int) {
    def isLast: Boolean = totalItems.map(total => current + pageItems >= total).getOrElse(false)

    def percentComplete: Int = totalItems match {
      case Some(total) if total > 0 =>
        val pc = (100 * current) / total
        if (pc < 1) 1 else pc
      case _ => 0
    }
  }

  case class JsonHarvestPage(
    records: String,             // XML string containing all records
    url: String,
    strategy: HarvestStrategy,
    config: JsonHarvestConfig,
    diagnostic: JsonDiagnostic,
    errorOpt: Option[String] = None
  )

  case class HarvestCron(previous: DateTime, delay: Int, unit: DelayUnit, incremental: Boolean) {

    def now = HarvestCron(new DateTime(), delay, unit, incremental)

    def next = HarvestCron(unit.after(previous, delay), delay, unit, incremental)

    def timeToWork = unit.after(previous, delay).isBeforeNow
  }

}

trait Harvesting {

  import harvest.Harvesting._

  def tagToInt(nodeSeq: NodeSeq, tag: String, default: Int = 0) = try {
    (nodeSeq \ tag).text.toInt
  }
  catch {
    case e: Exception =>
      logger.info(s"$tag: $e")
      default
  }

  def fetchAdLibPage(timeOut: Long, wsApi: WSClient, strategy: HarvestStrategy, url: String, database: String, search: String,
                     diagnosticOption: Option[AdLibDiagnostic] = None,
                     limit: Int = 50,
                     startFrom: Option[Int] = None,
                     credentials: Option[(String, String)] = None)
                    (implicit harvestExecutionContext: ExecutionContext): Future[AnyRef] = {
    val startFromValue = startFrom.getOrElse(diagnosticOption.map(d => d.current + d.pageItems).getOrElse(1))
    val cleanUrl = url.stripSuffix("?")
    val requestUrl = wsApi.url(cleanUrl).withRequestTimeout(timeOut.milliseconds)
    // UMU 2014-10-16T15:00
    val searchModified = strategy match {
      case ModifiedAfter(mod, _) =>
        s"modification greater '${timeToLocalString(mod)}'"
      case _ =>
        val cleanSearch = if (search.isEmpty) "all" else search
        if (cleanSearch contains "%20") cleanSearch.replace("%20", "") else cleanSearch
    }
    val baseRequest = requestUrl.withQueryString(
      "database" -> database,
      "search" -> searchModified,
      "xmltype" -> "grouped",
      "limit" -> limit.toString,
      "startFrom" -> startFromValue.toString
    )
    // Add Basic Auth header if credentials provided
    val request = credentials match {
      case Some((username, password)) if username.nonEmpty =>
        val encoded = Base64.getEncoder.encodeToString(s"$username:$password".getBytes(StandardCharsets.UTF_8))
        baseRequest.withHttpHeaders("Authorization" -> s"Basic $encoded")
      case _ =>
        baseRequest
    }
    logger.info(s"harvest url: ${request.uri}")
    // define your success condition
    implicit val success = new retry.Success[WSResponse](r => !((500 to 599) contains r.status))
    // retry 4 times with a delay of 1 second which will be multipled
    // by 2 on every attempt
    val wsResponseFuture = retry.Backoff(6, 1.seconds).apply { () =>
      request.get()
    }

    wsResponseFuture.map { response =>
      val sourceUrl = request.uri.toString
      val diagnostic = response.xml \ "diagnostic"
      val errorNode = diagnostic \ "error"

      // Always parse diagnostic info, even if there's an error
      val diagnosticInfo = AdLibDiagnostic(
        totalItems = tagToInt(diagnostic, "hits"),
        current = tagToInt(diagnostic, "first_item"),
        pageItems = tagToInt(diagnostic, "hits_on_display")
      )

      val error: Option[HarvestError] = if (errorNode.isEmpty) None
      else {
        val errorInfo = (errorNode \ "info").text
        val errorMessage = (errorNode \ "message").text
        logger.error(s"AdLib API error from $sourceUrl: $errorInfo, '$errorMessage'")
        Some(HarvestError(s"Error from $sourceUrl: $errorInfo, '$errorMessage'", strategy))
      }

      error match {
        case Some(harvestError) =>
          // Return an AdLibHarvestPage with empty records but valid diagnostic
          // This allows error recovery in the harvester to know which page failed
          AdLibHarvestPage(
            "", // empty records
            cleanUrl,
            database,
            search,
            strategy,
            diagnosticInfo,
            Some(harvestError.error) // include error message
          )
        case None =>
          AdLibHarvestPage(
            response.xml.toString(),
            cleanUrl,
            database,
            search,
            strategy,
            diagnosticInfo,
            None
          )
      }
    }
  }

  def fetchPMHPage(pageNumber: Int, timeOut: Long, wsApi: WSClient, strategy: HarvestStrategy, url: String, set: String, metadataPrefix: String,
                   resumption: Option[PMHResumptionToken] = None, recordId: Option[String] = None)(implicit harvestExecutionContext: ExecutionContext): Future[AnyRef] = {

    logger.debug(s"start fetch PMH Page: $url, $resumption")
    val verb = if (recordId != None) "GetRecord" else "ListRecords"
    val listRecords = wsApi.url(url)
      .withRequestTimeout(timeOut.milliseconds)
      .withQueryString("verb" -> verb)
    val request = resumption match {
      case None =>
        val withPrefix = listRecords.withQueryString("metadataPrefix" -> metadataPrefix)
        val withRecord = if (recordId.isEmpty) withPrefix else withPrefix.withQueryString("identifier" -> recordId.get)
        val withSet = if (set.isEmpty) withRecord else withRecord.withQueryString("set" -> set)
        strategy match {
          case ModifiedAfter(mod, justDate) =>
            withSet.withQueryString("from" -> {
              val dateTime = timeToUTCString(mod)
              val withoutMillis = dateTime.replaceAll("\\.[0-9]+", "")
              if (justDate) withoutMillis.substring(0, withoutMillis.indexOf('T')) else withoutMillis.replaceAll("\\.[0-9]{3}[Z]{0,1}$", "Z")
            })
          case _ => withSet
        }
      case Some(token) =>
        if (url.contains("anet.be")) {
          listRecords
            .withQueryString("resumptionToken" -> token.value)
            .withQueryString("metadataPrefix" -> metadataPrefix)
        } else {
          listRecords.withQueryString("resumptionToken" -> token.value)
        }
    }

    // define your success condition
    implicit val success = new retry.Success[WSResponse](r => !((500 to 599) contains r.status))
    // retry 4 times with a delay of 1 second which will be multipled
    // by 2 on every attempt
    val wsResponseFuture = retry.Backoff(6, 1.seconds).apply { () =>
      request.get()
    }

    wsResponseFuture.map { response =>
      val sourceUri = response.underlying[NettyResponse].getUri.toString
      logger.debug(s"start get for: \n $sourceUri")
      val error: Option[HarvestError] = if (response.status != 200) {
        logger.error(s"HTTP error from $sourceUri: ${response.statusText}")
        logger.debug(s"error response: ${response.underlying[NettyResponse].getResponseBody}")
        Some(HarvestError(s"HTTP Response: ${response.statusText} from $sourceUri", strategy))
      }
      else {
        val netty = response.underlying[NettyResponse]
        var body = netty.getResponseBody(StandardCharsets.UTF_8)
        if (body.indexOf('\uFEFF') == 0) {
          body = body.substring(1)
        }

        logger.trace(s"OAI-PMH Response: \n ''''${body}''''")

        // Parse XML with better error handling
        val xmlResult = try {
          scala.util.Success(XML.loadString(body.replace("ï»¿", "").replace("\u0239\u0187\u0191", "")))
        } catch {
          case e: org.xml.sax.SAXParseException =>
            logger.error(s"SAX parsing error from $sourceUri at line ${e.getLineNumber}, column ${e.getColumnNumber}: ${e.getMessage}")
            scala.util.Failure(e)
          case e: Exception =>
            logger.error(s"XML parsing error from $sourceUri: ${e.getMessage}")
            scala.util.Failure(e)
        }

        xmlResult match {
          case scala.util.Failure(e) =>
            Some(HarvestError(s"XML parsing failed for $sourceUri: ${e.getMessage}", strategy))
          case scala.util.Success(xml) =>
            val errorNode = xml \ "error"

            val records = xml \ verb \ "record"
            // todo: if there is a resumptionToken end the list size is greater than the cursor but zero records are returned through an error.
            val tokenNode = xml \ verb \ "resumptionToken"
            val faultyEmptyResponse: String = if (tokenNode.nonEmpty && tokenNode.text.trim.nonEmpty) {
              val completeListSize = tagToInt(tokenNode, "@completeListSize")
              val cursor = tagToInt(tokenNode, "@cursor", 1)
              if (completeListSize > cursor && records.isEmpty) {
                s"""For set ${set} with <a href="$sourceUri" target="_blank">url</a>, the completeLisSize was reported to be ${completeListSize} but at cursor ${cursor} 0 records were returned"""
              } else ""
            } else ""
            if (errorNode.nonEmpty || records.isEmpty || faultyEmptyResponse.nonEmpty) {
              val errorCode = if (errorNode.nonEmpty) (errorNode \ "@code").text else "noRecordsMatch"
              if (faultyEmptyResponse.nonEmpty) {
                logger.error(s"Faulty empty response from $sourceUri: $faultyEmptyResponse")
                Some(HarvestError(faultyEmptyResponse, strategy))
              }
              else if ("noRecordsMatch" == errorCode) {
                logger.debug(s"No PMH Records returned from $sourceUri")
                if (pageNumber > 1) {
                  Some(HarvestError("noRecordsMatchRecoverable", strategy))
                } else {
                  Some(HarvestError("noRecordsMatch", strategy))
                }
              }
              else {
                logger.error(s"OAI-PMH error from $sourceUri: ${errorNode.text}")
                Some(HarvestError(s"${errorNode.text} from $sourceUri", strategy))
              }
            }
            else {
              None
            }
        }
      }
      if (!error.isEmpty) {
        logger.debug(s"HarvestState in error: $error")
        error.get match {
          case HarvestError("noRecordsMatch", strategy) => NoRecordsMatch("noRecordsMatch", strategy)
          case _ => error.get
        }
      }
      else {
        val netty = response.underlying[NettyResponse]
        val sourceUri = netty.getUri.toString
        var body = netty.getResponseBody(StandardCharsets.UTF_8)
        if (body.indexOf('\uFEFF') == 0) {
          body = body.substring(1)
        }

        // Parse XML with better error handling
        val xmlOrError = try {
          scala.util.Right(XML.loadString(body.replace("ï»¿", "").replace("\u0239\u0187\u0191", "")))
        } catch {
          case e: org.xml.sax.SAXParseException =>
            logger.error(s"SAX parsing error from $sourceUri at line ${e.getLineNumber}, column ${e.getColumnNumber}: ${e.getMessage}")
            scala.util.Left(HarvestError(s"XML parsing failed for $sourceUri: ${e.getMessage}", strategy))
          case e: Exception =>
            logger.error(s"XML parsing error from $sourceUri: ${e.getMessage}")
            scala.util.Left(HarvestError(s"XML parsing failed for $sourceUri: ${e.getMessage}", strategy))
        }

        xmlOrError match {
          case scala.util.Left(error) => error
          case scala.util.Right(xml) =>
            val tokenNode = xml \ "ListRecords" \ "resumptionToken"
            val newToken = if (tokenNode.nonEmpty && tokenNode.text.trim.nonEmpty) {
              val completeListSize = tagToInt(tokenNode, "@completeListSize")
              val cursor = tagToInt(tokenNode, "@cursor", 1)
              Some(PMHResumptionToken(
                value = tokenNode.text,
                currentRecord = cursor,
                totalRecords = completeListSize,
                prefix = metadataPrefix
              ))
            }
            else {
              None
            }
            val total =
              if (newToken.isDefined) newToken.get.totalRecords
              else if (resumption.isDefined) resumption.get.totalRecords
              else 0

            // Detect deleted records (status="deleted" in OAI-PMH header)
            val allRecords = xml \ "ListRecords" \ "record"
            val deletedRecords = allRecords.filter { record =>
              (record \ "header" \ "@status").text == "deleted"
            }
            val deletedIds = deletedRecords.map { record =>
              (record \ "header" \ "identifier").text
            }.toList
            if (deletedIds.nonEmpty) {
              logger.info(s"Detected ${deletedIds.size} deleted records in OAI-PMH page")
            }

            val harvestPage = PMHHarvestPage(
              records = xml.toString(),
              url = url,
              set = set,
              metadataPrefix = metadataPrefix,
              totalRecords = total,
              strategy,
              resumptionToken = newToken,
              deletedIds = deletedIds,
              deletedCount = deletedIds.size
            )
            logger.debug(s"Return PMHHarvestPage: $newToken, $sourceUri")
            harvestPage
        }
      }
    }
  }

  // JSON path extraction using dot notation (e.g., "data.items" or "Items")
  def extractJsonPath(json: JsValue, path: String): JsValue = {
    if (path.isEmpty) json
    else path.split("\\.").foldLeft(json) { (js, key) =>
      (js \ key).getOrElse(JsNull)
    }
  }

  // Convert a JSON value to XML elements
  def jsonToXmlNodes(key: String, value: JsValue): Seq[Node] = {
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

  // Convert a JSON object to an XML record element with id attribute
  def jsonRecordToXml(json: JsValue, recordName: String, idPath: String): Elem = {
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

  // Wrap records in a root element
  def wrapRecordsInXml(records: Seq[Elem], rootName: String): String = {
    val root = Elem(null, rootName, scala.xml.Null, scala.xml.TopScope, false, records: _*)
    root.toString()
  }

  def fetchJsonPage(timeOut: Long, wsApi: WSClient, strategy: HarvestStrategy, url: String,
                    config: JsonHarvestConfig,
                    diagnosticOption: Option[JsonDiagnostic] = None,
                    page: Int = 1,
                    credentials: Option[(String, String)] = None,
                    apiKey: Option[(String, String)] = None)  // (paramName, keyValue)
                   (implicit harvestExecutionContext: ExecutionContext): Future[AnyRef] = {

    val cleanUrl = url.stripSuffix("?").stripSuffix("/")
    val baseRequest = wsApi.url(cleanUrl).withRequestTimeout(timeOut.milliseconds)

    // Add pagination parameters
    val withPagination = baseRequest.withQueryStringParameters(
      config.pageParam -> page.toString,
      config.pageSizeParam -> config.pageSize.toString
    )

    // Add API key if provided
    val withApiKey = apiKey match {
      case Some((paramName, keyValue)) if paramName.nonEmpty && keyValue.nonEmpty =>
        withPagination.withQueryStringParameters(paramName -> keyValue)
      case _ => withPagination
    }

    // Add Basic Auth header if credentials provided
    val request = credentials match {
      case Some((username, password)) if username.nonEmpty =>
        val encoded = Base64.getEncoder.encodeToString(s"$username:$password".getBytes(StandardCharsets.UTF_8))
        withApiKey.withHttpHeaders("Authorization" -> s"Basic $encoded")
      case _ =>
        withApiKey
    }

    logger.info(s"JSON harvest url: ${request.uri}")

    // Define success condition for retry
    implicit val success = new retry.Success[WSResponse](r => !((500 to 599) contains r.status))

    // Retry with backoff
    val wsResponseFuture = retry.Backoff(6, 1.seconds).apply { () =>
      request.get()
    }

    wsResponseFuture.map { response =>
      val sourceUrl = request.uri.toString

      if (response.status != 200) {
        logger.error(s"HTTP error from $sourceUrl: ${response.statusText}")
        HarvestError(s"HTTP Response: ${response.statusText} from $sourceUrl", strategy)
      } else {
        try {
          val json = response.json

          // Extract items array
          val items = extractJsonPath(json, config.itemsPath) match {
            case JsArray(arr) => arr.toSeq
            case _ =>
              logger.warn(s"Items path '${config.itemsPath}' did not return an array from $sourceUrl")
              Seq.empty
          }

          // Extract total count if configured
          val totalItems: Option[Int] = config.totalPath.flatMap { path =>
            extractJsonPath(json, path) match {
              case JsNumber(n) => Some(n.toInt)
              case JsString(s) => scala.util.Try(s.toInt).toOption
              case _ => None
            }
          }

          // Calculate current position
          val currentPosition = diagnosticOption.map(d => d.current + d.pageItems).getOrElse(0)

          val diagnostic = JsonDiagnostic(
            totalItems = totalItems,
            current = currentPosition,
            pageItems = items.size
          )

          if (items.isEmpty) {
            logger.debug(s"No JSON records returned from $sourceUrl (page $page)")
            if (page > 1 && totalItems.isEmpty) {
              // If we're past page 1 and no total, assume we've reached the end
              JsonHarvestPage(
                records = wrapRecordsInXml(Seq.empty, config.xmlRoot),
                url = cleanUrl,
                strategy = strategy,
                config = config,
                diagnostic = diagnostic.copy(totalItems = Some(currentPosition)),
                errorOpt = None
              )
            } else if (page == 1) {
              NoRecordsMatch("No records found", strategy)
            } else {
              JsonHarvestPage(
                records = wrapRecordsInXml(Seq.empty, config.xmlRoot),
                url = cleanUrl,
                strategy = strategy,
                config = config,
                diagnostic = diagnostic,
                errorOpt = None
              )
            }
          } else {
            // Transform JSON records to XML
            val xmlRecords = items.map { item =>
              jsonRecordToXml(item, config.xmlRecord, config.idPath)
            }

            val xmlString = wrapRecordsInXml(xmlRecords, config.xmlRoot)

            JsonHarvestPage(
              records = xmlString,
              url = cleanUrl,
              strategy = strategy,
              config = config,
              diagnostic = diagnostic,
              errorOpt = None
            )
          }
        } catch {
          case e: JsResultException =>
            logger.error(s"JSON parsing error from $sourceUrl: ${e.getMessage}")
            HarvestError(s"JSON parsing failed for $sourceUrl: ${e.getMessage}", strategy)
          case e: Exception =>
            logger.error(s"Error processing JSON from $sourceUrl: ${e.getMessage}")
            HarvestError(s"Error processing JSON from $sourceUrl: ${e.getMessage}", strategy)
        }
      }
    }
  }

}
