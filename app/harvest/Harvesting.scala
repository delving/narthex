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

import com.ning.http.client.providers.netty.NettyResponse
import org.OrgContext._
import org.joda.time.DateTime
import play.api.Logger
import play.api.Play.current
import play.api.libs.ws.WS
import services.Temporal._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.xml.{Elem, NodeSeq, XML}

// todo: use the actor's execution context?

object Harvesting {

  case class HarvestType(name: String, recordRoot: String, uniqueId: String, deepRecordContainer: Option[String] = None) {
    override def toString = name

    def matches(otherName: String) = name == otherName
  }

  object HarvestType {
    val PMH = HarvestType(
      name = "pmh",
      recordRoot = "/OAI-PMH/ListRecords/record",
      uniqueId = "/OAI-PMH/ListRecords/record/header/identifier",
      deepRecordContainer = Some("/OAI-PMH/ListRecords/record/metadata")
    )
    val PMH_REC = HarvestType(
      name = "pmh-rec",
      recordRoot = "/OAI-PMH/ListRecords/record/metadata",
      uniqueId = "/OAI-PMH/ListRecords/record/header/identifier",
      deepRecordContainer = Some("/OAI-PMH/ListRecords/record/metadata")
    )
    val ADLIB = HarvestType(
      name = "adlib",
      recordRoot = "/adlibXML/recordList/record",
      uniqueId = "/adlibXML/recordList/record/@priref",
      deepRecordContainer = None
    )

    val ALL_TYPES = List(PMH, PMH_REC, ADLIB)

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

  case class HarvestError(error: String, modifiedAfter: Option[DateTime])

  case class PMHHarvestPage
  (
    records: String,
    url: String,
    set: String,
    metadataPrefix: String,
    totalRecords: Int,
    modifiedAfter: Option[DateTime],
    justDate: Boolean,
    resumptionToken: Option[PMHResumptionToken])

  case class AdLibHarvestPage
  (
    records: String,
    url: String,
    database: String,
    search: String,
    modifiedAfter: Option[DateTime],
    diagnostic: AdLibDiagnostic)

  case class HarvestCron(previous: DateTime, delay: Int, unit: DelayUnit) {

    def now = HarvestCron(new DateTime(), delay, unit)

    def next = HarvestCron(unit.after(previous, delay), delay, unit)

    def timeToWork = unit.after(previous, delay).isBeforeNow
  }

  def harvestCron(previousString: String, delayString: String, unitString: String): HarvestCron = {
    val previous = if (previousString.nonEmpty) stringToTime(previousString) else new DateTime()
    val delay = if (delayString.nonEmpty) delayString.toInt else 1
    val unit = DelayUnit.fromString(unitString).getOrElse(DelayUnit.WEEKS)
    HarvestCron(previous, delay, unit)
  }

  def harvestCron(datasetInfo: Elem): HarvestCron = {
    val hc = datasetInfo \ "harvestCron"
    harvestCron(
      previousString = (hc \ "previous").text,
      delayString = (hc \ "delay").text,
      unitString = (hc \ "unit").text
    )
  }

}

trait Harvesting {

  import harvest.Harvesting._

  def tagToInt(nodeSeq: NodeSeq, tag: String, default: Int = 0) = try {
    (nodeSeq \ tag).text.toInt
  }
  catch {
    case e: Exception =>
      Logger.warn(s"$tag: $e")
      default
  }

  def fetchAdLibPage(url: String, database: String, search: String, modifiedAfter: Option[DateTime],
                     diagnosticOption: Option[AdLibDiagnostic] = None): Future[AnyRef] = {
    val startFrom = diagnosticOption.map(d => d.current + d.pageItems).getOrElse(1)
    val requestUrl = WS.url(url).withRequestTimeout(HARVEST_TIMEOUT)
    // UMU 2014-10-16T15:00
    val searchModified = modifiedAfter.map(after =>
      s"modification greater '${timeToLocalString(after)}'"
    ).getOrElse(if (search.isEmpty) "all" else search)
    val request = requestUrl.withQueryString(
      "database" -> database,
      "search" -> searchModified,
      "xmltype" -> "grouped",
      "limit" -> "50",
      "startFrom" -> startFrom.toString
    )
    request.get().map { response =>
      val diagnostic = response.xml \ "diagnostic"
      val errorNode = diagnostic \ "error"
      val error: Option[HarvestError] = if (errorNode.isEmpty) None
      else {
        val errorInfo = (errorNode \ "info").text
        val errorMessage = (errorNode \ "message").text
        Some(HarvestError(s"Error: $errorInfo, '$errorMessage'", modifiedAfter))
      }
      error getOrElse {
        AdLibHarvestPage(
          response.xml.toString(),
          url,
          database,
          search,
          modifiedAfter,
          AdLibDiagnostic(
            totalItems = tagToInt(diagnostic, "hits"),
            current = tagToInt(diagnostic, "first_item"),
            pageItems = tagToInt(diagnostic, "hits_on_display")
          )
        )
      }
    }
  }

  def fetchPMHPage(url: String, set: String, metadataPrefix: String, modifiedAfter: Option[DateTime], justDate: Boolean,
                   resumption: Option[PMHResumptionToken] = None): Future[AnyRef] = {

    // Teylers 2014-09-15
    val listRecords = WS.url(url)
      .withRequestTimeout(HARVEST_TIMEOUT)
      .withQueryString("verb" -> "ListRecords")
    val request = resumption match {
      case None =>
        val withPrefix = listRecords.withQueryString("metadataPrefix" -> metadataPrefix)
        val withSet = if (set.isEmpty) withPrefix else withPrefix.withQueryString("set" -> set)
        modifiedAfter.map(modified => withSet.withQueryString("from" -> {
          val dateTime = timeToUTCString(modified)
          if (justDate) dateTime.substring(0, dateTime.indexOf('T')) else dateTime
        })).getOrElse(withSet)
      case Some(token) =>
        listRecords.withQueryString("resumptionToken" -> token.value)
    }
    request.get().map { response =>
      val error: Option[HarvestError] = if (response.status != 200) {
        Logger.info(s"response: ${response.body}")
        Some(HarvestError(s"HTTP Response: ${response.statusText}", modifiedAfter))
      }
      else {
        val errorNode = response.xml \ "error"
        if (errorNode.nonEmpty) {
          val errorCode = (errorNode \ "@code").text
          if ("noRecordsMatch" == errorCode) {
            Logger.info("No PMH Records returned")
            None
          }
          else {
            Some(HarvestError(errorNode.text, modifiedAfter))
          }
        }
        else None
      }
      error getOrElse {
        val netty = response.underlying[NettyResponse]
        val body = netty.getResponseBody("UTF-8")
        val xml = XML.loadString(body)
        val tokenNode = xml \ "ListRecords" \ "resumptionToken"
        val newToken = if (tokenNode.text.trim.nonEmpty) {
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
        PMHHarvestPage(
          records = xml.toString(),
          url = url,
          set = set,
          metadataPrefix = metadataPrefix,
          totalRecords = total,
          modifiedAfter = modifiedAfter,
          justDate = justDate,
          resumptionToken = newToken
        )
      }
    }
  }

}
