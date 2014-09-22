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

import play.api.Logger
import play.api.libs.ws.WS
import services.Harvesting._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

object Harvesting {

  val PMH_RECORD_ROOT = "/OAI-PMH/ListRecords/record"
  val PMH_UNIQUE_ID = "/OAI-PMH/ListRecords/record/header/identifier"

  val ADLIB_RECORD_ROOT = "/adlibXML/recordList/record"
  val ADLIB_UNIQUE_ID = "/adlibXML/recordList/record/@priref"

  case class AdLibDiagnostic(totalItems: Int, current: Int, pageItems: Int) {
    def isLast = current + pageItems >= totalItems

    def percentComplete: Int = {
      val pc = (100 * current) / totalItems
      if (pc < 1) 1 else pc
    }
  }

  case class AdLibHarvestPage(records: String, url: String, database: String, diagnostic: AdLibDiagnostic)

}

trait Harvesting extends BaseXTools {

  def fetchAdLibPage(url: String, database: String, diagnostic: Option[AdLibDiagnostic] = None): Future[AdLibHarvestPage] = {
    val startFrom = diagnostic.map(d => d.current + d.pageItems).getOrElse(1)
    WS.url(url).withQueryString(
      "database" -> database,
      "search" -> "all",
      "xmltype" -> "grouped",
      "limit" -> "50",
      "startFrom" -> startFrom.toString
    ).get().map {
      response =>
        val diagnostic = response.xml \ "diagnostic"
        val hits = (diagnostic \ "hits").text
        val firstItem = (diagnostic \ "first_item").text
        val hitsOnDisplay = (diagnostic \ "hits_on_display").text
        AdLibHarvestPage(
          response.xml.toString(),
          url,
          database,
          AdLibDiagnostic(
            totalItems = hits.toInt,
            current = firstItem.toInt,
            pageItems = hitsOnDisplay.toInt
          )
        )
    }
  }

  def fetchPMHPage(url: String, set: String, metadataPrefix: String, resumption: Option[PMHResumptionToken] = None) = {
    val requestUrl = WS.url(url)
    val request = resumption match {
      case None =>
        if (set.isEmpty) {
          requestUrl.withQueryString(
            "verb" -> "ListRecords",
            "metadataPrefix" -> metadataPrefix
          )
        }
        else {
          requestUrl.withQueryString(
            "verb" -> "ListRecords",
            "set" -> set,
            "metadataPrefix" -> metadataPrefix
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
        val errorNode = response.xml \ "error"
        if (errorNode.isEmpty) {
          val tokenNode = response.xml \ "ListRecords" \ "resumptionToken"
          val newToken = if (tokenNode.nonEmpty && tokenNode.text.nonEmpty) {
            val completeListSize = (tokenNode \ "@completeListSize").text.toInt
            val cursor = (tokenNode \ "@cursor").text.toInt
            Some(PMHResumptionToken(
              value = tokenNode.text,
              currentRecord = cursor,
              totalRecords = completeListSize
            ))
          }
          else {
            None
          }
          val total = if (newToken.isDefined) newToken.get.totalRecords else resumption.get.totalRecords
          PMHHarvestPage(
            records = response.xml.toString(),
            url = url,
            set = set,
            metadataPrefix = metadataPrefix,
            totalRecords = total,
            error = None,
            resumptionToken = newToken
          )
        } else {
          Logger.info(s"response:\n${response.xml}")
          PMHHarvestPage(
            records = response.xml.toString(),
            url = url,
            set = set,
            metadataPrefix = metadataPrefix,
            totalRecords = 0,
            error = Some(errorNode.text),
            resumptionToken = None
          )
        }
    }
  }

}
