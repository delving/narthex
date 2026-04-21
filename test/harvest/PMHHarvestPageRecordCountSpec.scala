//===========================================================================
//    Copyright 2026 Delving B.V.
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

import scala.xml.XML
import org.scalatest.flatspec._
import org.scalatest.matchers._

import harvest.Harvesting.PMHHarvestPage

class PMHHarvestPageRecordCountSpec extends AnyFlatSpec with should.Matchers {

  private val pageWithoutResumption =
    <OAI-PMH>
      <ListRecords>
        <record><header><identifier>id-1</identifier></header></record>
        <record><header><identifier>id-2</identifier></header></record>
        <record><header><identifier>id-3</identifier></header></record>
        <record><header><identifier>id-4</identifier></header></record>
        <record><header><identifier>id-5</identifier></header></record>
      </ListRecords>
    </OAI-PMH>

  private val pageWithResumption =
    <OAI-PMH>
      <ListRecords>
        <record><header><identifier>id-1</identifier></header></record>
        <record><header><identifier>id-2</identifier></header></record>
        <record><header><identifier>id-3</identifier></header></record>
        <record><header><identifier>id-4</identifier></header></record>
        <record><header><identifier>id-5</identifier></header></record>
        <resumptionToken completeListSize="100" cursor="1">opaque</resumptionToken>
      </ListRecords>
    </OAI-PMH>

  "PMHHarvestPage" should "accept a recordCount field that reflects the actual record count" in {
    val page = PMHHarvestPage(
      records = pageWithoutResumption.toString,
      url = "https://example.org/oai",
      set = "",
      metadataPrefix = "edm",
      totalRecords = 0,
      strategy = dataset.DatasetActor.Sample,
      resumptionToken = None,
      deletedIds = List.empty,
      deletedCount = 0,
      recordCount = (pageWithoutResumption \ "ListRecords" \ "record").size
    )
    page.recordCount shouldBe 5
    page.totalRecords shouldBe 0
  }

  it should "accept a PMHHarvestPage where totalRecords reflects completeListSize and recordCount is the page size" in {
    val page = PMHHarvestPage(
      records = pageWithResumption.toString,
      url = "https://example.org/oai",
      set = "",
      metadataPrefix = "edm",
      totalRecords = 100,
      strategy = dataset.DatasetActor.Sample,
      resumptionToken = None,
      deletedIds = List.empty,
      deletedCount = 0,
      recordCount = (pageWithResumption \ "ListRecords" \ "record").size
    )
    page.recordCount shouldBe 5
    page.totalRecords shouldBe 100
  }
}
