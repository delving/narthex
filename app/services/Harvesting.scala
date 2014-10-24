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

package services

import org.joda.time.DateTime
import play.api.Logger
import play.api.libs.ws.WS
import services.Harvesting._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
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

    val ALL_ORIGINS = List(PMH, ADLIB)

    def fromString(string: String): Option[HarvestType] = ALL_ORIGINS.find(s => s.matches(string))
  }

  case class AdLibDiagnostic(totalItems: Int, current: Int, pageItems: Int) {
    def isLast = current + pageItems >= totalItems

    def percentComplete: Int = {
      val pc = (100 * current) / totalItems
      if (pc < 1) 1 else pc
    }
  }

  case class AdLibHarvestPage(records: String, error: Option[String], url: String, database: String,
                              modifiedAfter: Option[DateTime], diagnostic: AdLibDiagnostic)

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

  def tagToInt(nodeSeq: NodeSeq, tag: String, default: Int = 0) = try {
    (nodeSeq \ tag).text.toInt
  }
  catch {
    case e: Exception =>
      Logger.warn(s"$tag: $e")
      default
  }

  def fetchAdLibPage(url: String, database: String, modifiedAfter: Option[DateTime], diagnosticOption: Option[AdLibDiagnostic] = None): Future[AdLibHarvestPage] = {
    val startFrom = diagnosticOption.map(d => d.current + d.pageItems).getOrElse(1)
    val requestUrl = WS.url(url).withRequestTimeout(NarthexConfig.HARVEST_TIMEOUT)
    // 2014-10-16T17:00
    val search = modifiedAfter.map(after => s"modification greater '${toBasicString(after)}'").getOrElse("all")
    requestUrl.withQueryString(
      "database" -> database,
      "search" -> search,
      "xmltype" -> "grouped",
      "limit" -> "50",
      "startFrom" -> startFrom.toString
    ).get().map {
      response =>
        val diagnostic = response.xml \ "diagnostic"
        val error = diagnostic \ "error"
        if (error.nonEmpty) {
          val errorInfo = (error \ "info").text
          val errorMessage = (error \ "message").text
          AdLibHarvestPage(
            response.xml.toString(),
            error = Some(s"Error: $errorInfo, '$errorMessage'"),
            url,
            database,
            modifiedAfter,
            AdLibDiagnostic(0, 0, 0)
          )
        }
        else {
          AdLibHarvestPage(
            response.xml.toString(),
            error = None,
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

  def fetchPMHPage(url: String, set: String, metadataPrefix: String, modifiedAfter: Option[DateTime], resumption: Option[PMHResumptionToken] = None) = {
    val requestUrl = WS.url(url).withRequestTimeout(NarthexConfig.HARVEST_TIMEOUT)
    // 2014-09-15
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
    request.get().map {
      response =>
        val errorNode = if (response.status != 200) {
          Logger.info(s"response: ${response.body}")
          <error>HTTP Response: {response.statusText}</error>
        }
        else {
          response.xml \ "error"
        }
        if (errorNode.isEmpty) {
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
            error = None,
            resumptionToken = newToken
          )
        } else {
          PMHHarvestPage(
            records = "",
            url = url,
            set = set,
            metadataPrefix = metadataPrefix,
            totalRecords = 0,
            modifiedAfter = None,
            error = Some(errorNode.text),
            resumptionToken = None
          )
        }
    }
  }

}
