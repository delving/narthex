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

import dataset.{DatasetRepo, DsInfo, Sip, SipFactory}
import mapping.{CategoriesRepo, ConceptRepo}
import org.OrgActor.DatasetsCountCategories
import org.joda.time.DateTime
import services.NarthexConfig._
import services._
import thesaurus.ThesaurusDb
import triplestore.TripleStore

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
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
  val sipsDir = new File(orgRoot, "sips")
  val categoriesRepo = new CategoriesRepo(new File(orgRoot, "categories"))
  val skosRepo = new ConceptRepo(new File(orgRoot, "skos"))
  val factoryDir = new File(orgRoot, "factory")
  val sipFactory = new SipFactory(factoryDir)
  val ts = new TripleStore(TRIPLE_STORE_URL)
  val us = new UserStore(ts)

  orgRoot.mkdirs()
  factoryDir.mkdirs()
  datasetsDir.mkdir()
  rawDir.mkdirs()
  sipsDir.mkdirs()

  def thesaurusDb(conceptSchemeA: String, conceptSchemeB: String) =
    if (conceptSchemeA > conceptSchemeB)
      new ThesaurusDb(conceptSchemeB, conceptSchemeA)
    else
      new ThesaurusDb(conceptSchemeA, conceptSchemeB)

  def datasetRepo(spec: String): DatasetRepo = datasetRepoOption(spec).getOrElse(
    throw new RuntimeException(s"Expected $spec dataset to exist")
  )

  def datasetRepoOption(spec: String): Option[DatasetRepo] = {
    val futureInfoOpt = DsInfo(spec, ts)
    val infoOpt = Await.result(futureInfoOpt, 5.seconds)
    infoOpt.map(info => new DatasetRepo(this, info).mkdirs)
  }

  def availableSips: Seq[AvailableSip] = sipsDir.listFiles.toSeq.filter(
    _.getName.endsWith(SIP_EXTENSION)
  ).map(AvailableSip).sortBy(_.dateTime.getMillis).reverse

  def uploadedSips: Future[Seq[Sip]] = {
    DsInfo.listDsInfo(ts).map { list =>
      list.flatMap { dsi =>
        val datasetRepo = new DatasetRepo(this, dsi)
        datasetRepo.sipRepo.latestSipOpt
      }
    }
  }

  def startCategoryCounts() = {
    val categoryDatasets = DsInfo.listDsInfo(ts).map { list =>
      list.flatMap { dsi =>
        if (dsi.getBooleanProp(DsInfo.categoriesInclude)) Some(dsi) else None
      }
    }
    categoryDatasets.map { dsList =>
      OrgActor.actor ! DatasetsCountCategories(dsList.map(_.spec))
    }
  }
}