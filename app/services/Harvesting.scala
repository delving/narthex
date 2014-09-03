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

  case class AdLibDiagnostic(totalItems: Int, firstItem: Int, itemCount: Int) {
    def isLast = firstItem + itemCount >= totalItems
  }

  case class AdLibHarvestPage(records: String, diagnostic: AdLibDiagnostic)

  def fetchAdLibPage(url: String, database: String, diagnostic: Option[AdLibDiagnostic] = None): Future[AdLibHarvestPage] = {
    val startFrom = diagnostic.map(d => d.firstItem + d.itemCount).getOrElse(1)
    WS.url(url).withQueryString(
      "database" -> database,
      "search" -> "all",
      "xmltype" -> "grouped",
      "limit" -> "2",
      "startFrom" -> startFrom.toString
    ).get().map {
      response =>
        val recordList = response.xml \ "recordList" \ "record"
        val diagnostic = response.xml \ "diagnostic"
        val hits = (diagnostic \ "hits").text
        val firstItem = (diagnostic \ "first_item").text
        val hitsOnDisplay = (diagnostic \ "hits_on_display").text
        AdLibHarvestPage(
          recordList.toString(),
          AdLibDiagnostic(
            totalItems = hits.toInt,
            firstItem = firstItem.toInt,
            itemCount = hitsOnDisplay.toInt
          )
        )
    }
  }

  case class PMHResumptionToken(value: String)

  case class PMHHarvestPage(records: String, resumptionToken: Option[PMHResumptionToken])

  def fetchPMHPage(url: String, set: String, metadataPrefix: String, resumption: Option[PMHResumptionToken]) = {
    val holder = WS.url(url)
    val responseFuture = resumption match {
      case None =>
        holder.withQueryString(
          "verb" -> "ListRecords",
          "set" -> set,
          "metadataPrefix" -> metadataPrefix
        ).get()
      case Some(token) =>
        holder.withQueryString(
          "verb" -> "ListRecords",
          "resumptionToken" -> token.value
        ).get()
    }
    responseFuture.map {
      response =>
        val recordList = response.xml \ "ListRecords" \ "record"
        val resumptionToken = response.xml \ "ListRecords" \ "resumptionToken"
        // could maybe use resumptionToken \ "@completeListSize", resumptionToken \ "@cursor"
        val resumption = if (resumptionToken.nonEmpty && resumptionToken.text.nonEmpty) Some(PMHResumptionToken(resumptionToken.text)) else None
        PMHHarvestPage(recordList.toString(), resumption)
    }
  }

}
