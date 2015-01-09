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

package org

import java.io.File

import dataset.{DatasetRepo, Sip, SipFactory}
import mapping.{CategoriesRepo, ConceptRepo}
import org.OrgActor.DatasetsCountCategories
import org.OrgDb.Dataset
import org.joda.time.DateTime
import services._
import thesaurus.ThesaurusDb

import scala.language.postfixOps

object OrgRepo {

  val SIP_EXTENSION = ".sip.zip"

  lazy val repo = new OrgRepo(NarthexConfig.USER_HOME, NarthexConfig.ORG_ID)

  def pathToDirectory(path: String) = path.replace(":", "_").replace("@", "_")

  case class AvailableSip(file: File) {
    val n = file.getName
    if (!n.endsWith(SIP_EXTENSION)) throw new RuntimeException(s"Strange file name $file")
    val datasetName = n.substring(0, n.indexOf("__"))
    val dateTime = new DateTime(file.lastModified())
  }

}

class OrgRepo(userHome: String, val orgId: String) {

  import org.OrgRepo._

  val root = new File(userHome, "NarthexFiles")
  val orgRoot = new File(root, orgId)
  val datasetsDir = new File(orgRoot, "datasets")
  val rawDir = new File(orgRoot, "raw")
  val mappedDir = new File(orgRoot, "mapped")
  val sipsDir = new File(orgRoot, "sips")
  val categoriesRepo = new CategoriesRepo(new File(orgRoot, "categories"))
  val skosRepo = new ConceptRepo(new File(orgRoot, "skos"))
  val factoryDir = new File(orgRoot, "factory")
  val sipFactory = new SipFactory(factoryDir)
  val orgDb = new OrgDb(orgId)

  orgRoot.mkdirs()
  factoryDir.mkdirs()
  datasetsDir.mkdir()
  rawDir.mkdirs()
  mappedDir.mkdirs()
  sipsDir.mkdirs()

  def thesaurusDb(conceptSchemeA: String, conceptSchemeB: String) =
    if (conceptSchemeA > conceptSchemeB)
      new ThesaurusDb(conceptSchemeB, conceptSchemeA)
    else
      new ThesaurusDb(conceptSchemeA, conceptSchemeB)

  def datasetRepo(datasetName: String): DatasetRepo = {
    val dr = new DatasetRepo(this, datasetName)
    dr.mkdirs
    dr
  }

  def datasetRepoOption(datasetName: String): Option[DatasetRepo] = {
    val dr = datasetRepo(datasetName)
    if (dr.datasetDb.infoOpt.isDefined) Some(dr) else None
  }

  def availableSips: Seq[AvailableSip] = sipsDir.listFiles.toSeq.filter(
    _.getName.endsWith(SIP_EXTENSION)
  ).map(AvailableSip).sortBy(_.dateTime.getMillis).reverse

  def uploadedSips: Seq[Sip] = orgDb.listDatasets.flatMap(dataset => datasetRepo(dataset.datasetName).sipRepo.latestSipOpt)

  def startCategoryCounts() = {
    val categoryDatasets: Seq[Dataset] = orgDb.listDatasets.flatMap { dataset =>
      val included = (dataset.info \ "categories" \ "included").text
      if (included == "true") Some(dataset) else None
    }
    val datasets = categoryDatasets.map(_.datasetName)
    OrgActor.actor ! DatasetsCountCategories(datasets)
  }
}