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

import org.joda.time.DateTime
import play.api.Logger
import play.api.libs.ws.WS
import services.{BaseXTools, NarthexConfig}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.Random
import scala.xml.{Elem, NodeSeq}

object Harvesting extends BaseXTools {

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
    val ADLIB = HarvestType(
      name = "adlib",
      recordRoot = "/adlibXML/recordList/record",
      uniqueId = "/adlibXML/recordList/record/@priref",
      deepRecordContainer = None
    )

    val ALL_TYPES = List(PMH, ADLIB)

    def fromString(string: String): Option[HarvestType] = ALL_TYPES.find(s => s.matches(string))

    def fromInfo(info: NodeSeq) = fromString((info \ "harvest" \ "harvestType").text)
  }

  case class AdLibDiagnostic(totalItems: Int, current: Int, pageItems: Int) {
    def isLast = current + pageItems >= totalItems

    def percentComplete: Int = {
      val pc = (100 * current) / totalItems
      if (pc < 1) 1 else pc
    }
  }

  case class PMHResumptionToken(value: String, currentRecord: Int, totalRecords: Int) {

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
    resumptionToken: Option[PMHResumptionToken])

  case class AdLibHarvestPage
  (
    records: String,
    url: String,
    database: String,
    modifiedAfter: Option[DateTime],
    diagnostic: AdLibDiagnostic)

  case class RepoMetadataFormat(prefix: String, namespace: String = "unknown")

  case class PublishedDataset
  (
    spec: String, prefix: String, name: String, description: String,
    dataProvider: String, totalRecords: Int,
    metadataFormat: RepoMetadataFormat)


  case class Harvest
  (
    repoName: String,
    headersOnly: Boolean,
    from: Option[DateTime],
    until: Option[DateTime],
    totalPages: Int,
    totalRecords: Int,
    pageSize: Int,
    random: String = Random.alphanumeric.take(10).mkString(""),
    currentPage: Int = 1) {

    def resumptionToken: PMHResumptionToken = PMHResumptionToken(
      value = s"$random-$totalPages-$currentPage",
      currentRecord = currentPage * NarthexConfig.OAI_PMH_PAGE_SIZE,
      totalRecords = totalRecords
    )

    def next = {
      if (currentPage >= totalPages)
        None
      else
        Some(this.copy(currentPage = currentPage + 1))
    }
  }

  case class DelayUnit(name: String, millis: Long) {
    override def toString = name

    def matches(otherName: String) = name == otherName

    def after(previous: DateTime, delay: Int) = {
      val nonzeroDelay = if (delay <= 0) 1 else delay
      new DateTime(previous.getMillis + millis * nonzeroDelay)
    }
  }

  object DelayUnit {
    val MINUTES = DelayUnit("minutes", 1000 * 60)
    val HOURS = DelayUnit("hours", MINUTES.millis * 60)
    val DAYS = DelayUnit("days", HOURS.millis * 24)
    val WEEKS = DelayUnit("weeks", DAYS.millis * 7)

    val ALL_UNITS = List(WEEKS, DAYS, HOURS, MINUTES)

    def fromString(string: String): Option[DelayUnit] = ALL_UNITS.find(s => s.matches(string))
  }

  case class HarvestCron(previous: DateTime, delay: Int, unit: DelayUnit) {

    def now = HarvestCron(new DateTime(), delay, unit)

    def next = HarvestCron(unit.after(previous, delay), delay, unit)

    def timeToWork = unit.after(previous, delay).isBeforeNow
  }

  def harvestCron(previousString: String, delayString: String, unitString: String): HarvestCron = {
    val previous = if (previousString.nonEmpty) fromUTCDateTime(previousString) else new DateTime()
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

trait Harvesting extends BaseXTools {

  import harvest.Harvesting._

  def tagToInt(nodeSeq: NodeSeq, tag: String, default: Int = 0) = try {
    (nodeSeq \ tag).text.toInt
  }
  catch {
    case e: Exception =>
      Logger.warn(s"$tag: $e")
      default
  }

  def fetchAdLibPage(url: String, database: String, modifiedAfter: Option[DateTime],
                     diagnosticOption: Option[AdLibDiagnostic] = None): Future[AnyRef] = {
    val startFrom = diagnosticOption.map(d => d.current + d.pageItems).getOrElse(1)
    val requestUrl = WS.url(url).withRequestTimeout(NarthexConfig.HARVEST_TIMEOUT)
    // UMU 2014-10-16T15:00
    val search = modifiedAfter.map(after => s"modification greater '${toBasicString(after)}'").getOrElse("all")
    val request = requestUrl.withQueryString(
      "database" -> database,
      "search" -> search,
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

  def fetchPMHPage(url: String, set: String, metadataPrefix: String, modifiedAfter: Option[DateTime],
                   resumption: Option[PMHResumptionToken] = None): Future[AnyRef] = {
    val requestUrl = WS.url(url).withRequestTimeout(NarthexConfig.HARVEST_TIMEOUT)
    // Teylers 2014-09-15
    val from = modifiedAfter.map(toBasicString).getOrElse(toBasicString(new DateTime(0L)))
    val request = resumption match {
      case None =>
        if (set.isEmpty) {
          requestUrl.withQueryString(
            "verb" -> "ListRecords",
            "metadataPrefix" -> metadataPrefix,
            "from" -> from
          )
        }
        else {
          requestUrl.withQueryString(
            "verb" -> "ListRecords",
            "set" -> set,
            "metadataPrefix" -> metadataPrefix,
            "from" -> from
          )
        }
      case Some(token) =>
        requestUrl.withQueryString(
          "verb" -> "ListRecords",
          "resumptionToken" -> token.value
        )
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
        val tokenNode = response.xml \ "ListRecords" \ "resumptionToken"
        val newToken = if (tokenNode.nonEmpty && tokenNode.text.trim.nonEmpty) {
          val completeListSize = tagToInt(tokenNode, "@completeListSize")
          val cursor = tagToInt(tokenNode, "@cursor", 1)
          Some(PMHResumptionToken(
            value = tokenNode.text,
            currentRecord = cursor,
            totalRecords = completeListSize
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
          records = response.xml.toString(),
          url = url,
          set = set,
          metadataPrefix = metadataPrefix,
          totalRecords = total,
          modifiedAfter = modifiedAfter,
          resumptionToken = newToken
        )
      }
    }
  }

}