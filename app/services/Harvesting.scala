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

import play.api.libs.ws.WS

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

trait Harvesting extends BaseXTools {

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

  val PMH_RECORD_ROOT = "/OAI-PMH/ListRecords/record"
  val PMH_UNIQUE_ID = "/OAI-PMH/ListRecords/record/header/identifier"

  case class PMHResumptionToken(value: String, current: Int, total:Int) {
    def percentComplete: Int = {
      val pc = (100 * current) / total
      if (pc < 1) 1 else pc
    }
  }

  case class PMHHarvestPage(records: String, url: String, set: String, metadataPrefix: String, total: Int, resumptionToken: Option[PMHResumptionToken])

  def fetchPMHPage(url: String, set: String, metadataPrefix: String, resumption: Option[PMHResumptionToken] = None) = {
    val requestUrl = WS.url(url)
    val request = resumption match {
      case None =>
        requestUrl.withQueryString(
          "verb" -> "ListRecords",
          "set" -> set,
          "metadataPrefix" -> metadataPrefix
        )
      case Some(token) =>
        requestUrl.withQueryString(
          "verb" -> "ListRecords",
          "resumptionToken" -> token.value
        )
    }
    request.get().map {
      response =>
        val tokenNode = response.xml \ "ListRecords" \ "resumptionToken"
        val newToken = if (tokenNode.nonEmpty && tokenNode.text.nonEmpty) {
          val completeListSize = tokenNode \ "@completeListSize"
          val cursor = tokenNode \ "@cursor"
          Some(PMHResumptionToken(tokenNode.text, cursor.text.toInt, completeListSize.text.toInt))
        }
        else {
          None
        }
        val total = if (newToken.isDefined) newToken.get.total else resumption.get.total
        PMHHarvestPage(response.xml.toString(), url, set, metadataPrefix, total, newToken)
    }
  }

}
