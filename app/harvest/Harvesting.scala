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
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.xml.{NodeSeq, XML}
import play.api.Logger
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

    val ALL_TYPES = List(PMH, ADLIB, DOWNLOAD)

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
    resumptionToken: Option[PMHResumptionToken])

  case class AdLibHarvestPage
  (
    records: String,
    url: String,
    database: String,
    search: String,
    strategy: HarvestStrategy,
    diagnostic: AdLibDiagnostic)

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
                     diagnosticOption: Option[AdLibDiagnostic] = None)
                    (implicit harvestExecutionContext: ExecutionContext): Future[AnyRef] = {
    val startFrom = diagnosticOption.map(d => d.current + d.pageItems).getOrElse(1)
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
    val request = requestUrl.withQueryString(
      "database" -> database,
      "search" -> searchModified,
      "xmltype" -> "grouped",
      "limit" -> "50",
      "startFrom" -> startFrom.toString
    )
    logger.info(s"harvest url: ${request.uri}")
    // define your success condition
    implicit val success = new retry.Success[WSResponse](r => !((500 to 599) contains r.status))
    // retry 4 times with a delay of 1 second which will be multipled
    // by 2 on every attempt
    val wsResponseFuture = retry.Backoff(6, 1.seconds).apply { () =>
      request.get()
    }

    wsResponseFuture.map { response =>
      val diagnostic = response.xml \ "diagnostic"
      val errorNode = diagnostic \ "error"
      val error: Option[HarvestError] = if (errorNode.isEmpty) None
      else {
        val errorInfo = (errorNode \ "info").text
        val errorMessage = (errorNode \ "message").text
        Some(HarvestError(s"Error: $errorInfo, '$errorMessage'", strategy))
      }
      error getOrElse {
        AdLibHarvestPage(
          response.xml.toString(),
          cleanUrl,
          database,
          search,
          strategy,
          AdLibDiagnostic(
            totalItems = tagToInt(diagnostic, "hits"),
            current = tagToInt(diagnostic, "first_item"),
            pageItems = tagToInt(diagnostic, "hits_on_display")
          )
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
      logger.debug(s"start get for: \n ${response.underlying[NettyResponse].getUri}")
      val error: Option[HarvestError] = if (response.status != 200) {
        logger.debug(s"error response: ${response.underlying[NettyResponse].getResponseBody}")
        Some(HarvestError(s"HTTP Response: ${response.statusText}", strategy))
      }
      else {
        val netty = response.underlying[NettyResponse]
        var body = netty.getResponseBody(StandardCharsets.UTF_8)
        if (body.indexOf('\uFEFF') == 0) {
          body = body.substring(1)
        }

        logger.trace(s"OAI-PMH Response: \n ''''${body}''''")
        val xml = XML.loadString(body.replace("ï»¿", "").replace("\u0239\u0187\u0191", "")) // reader old
        val errorNode = xml \ "error"

        val records = xml \ verb \ "record"
        // todo: if there is a resumptionToken end the list size is greater than the cursor but zero records are returned through an error.
        val tokenNode = xml \ verb \ "resumptionToken"
        val faultyEmptyResponse: String = if (tokenNode.nonEmpty && tokenNode.text.trim.nonEmpty) {
          val completeListSize = tagToInt(tokenNode, "@completeListSize")
          val cursor = tagToInt(tokenNode, "@cursor", 1)
          if (completeListSize > cursor && records.isEmpty) {
            s"""For set ${set} with <a href="${netty.getUri}" target="_blank">url</a>, the completeLisSize was reported to be ${completeListSize} but at cursor ${cursor} 0 records were returned"""
          } else ""
        } else ""
        if (errorNode.nonEmpty || records.isEmpty || faultyEmptyResponse.nonEmpty) {
          val errorCode = if (errorNode.nonEmpty) (errorNode \ "@code").text else "noRecordsMatch"
          if (faultyEmptyResponse.nonEmpty) {
            Some(HarvestError(faultyEmptyResponse, strategy))
          }
          else if ("noRecordsMatch" == errorCode) {
            logger.debug("No PMH Records returned")
            if (pageNumber > 1) {
              Some(HarvestError("noRecordsMatchRecoverable", strategy))
            } else {
              Some(HarvestError("noRecordsMatch", strategy))
            }
          }
          else {
            Some(HarvestError(errorNode.text, strategy))
          }
        }
        else {
          None
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
        var body = netty.getResponseBody(StandardCharsets.UTF_8)
        if (body.indexOf('\uFEFF') == 0) {
          body = body.substring(1)
        }
        val xml = XML.loadString(body.replace("ï»¿", "").replace("\u0239\u0187\u0191", ""))
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
        val harvestPage = PMHHarvestPage(
          records = xml.toString(),
          url = url,
          set = set,
          metadataPrefix = metadataPrefix,
          totalRecords = total,
          strategy,
          resumptionToken = newToken
        )
        logger.debug(s"Return PMHHarvestPage: $newToken, ${netty.getUri}")
        harvestPage
      }
    }
  }

}
