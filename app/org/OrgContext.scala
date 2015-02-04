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

import dataset.DsInfo.Character
import dataset.{DatasetContext, DsInfo, Sip, SipFactory}
import mapping.{CategoriesRepo, SkosInfo, SkosMappingStore}
import org.ActorStore.NXActor
import org.OrgActor.DatasetsCountCategories
import org.joda.time.DateTime
import services.FileHandling.clearDir
import services.NarthexConfig._
import services._
import triplestore.GraphProperties.categoriesInclude
import triplestore.TripleStore

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.language.postfixOps

object OrgContext {

  val SIP_EXTENSION = ".sip.zip"

  lazy val orgContext = new OrgContext(NarthexConfig.USER_HOME, NarthexConfig.ORG_ID)

  def pathToDirectory(path: String) = path.replace(":", "_").replace("@", "_")

  case class AvailableSip(file: File) {
    val n = file.getName
    if (!n.endsWith(SIP_EXTENSION)) throw new RuntimeException(s"Strange file name $file")
    val datasetName = n.substring(0, n.indexOf("__"))
    val dateTime = new DateTime(file.lastModified())
  }

}

class OrgContext(userHome: String, val orgId: String) {

  import org.OrgContext._

  val root = new File(userHome, "NarthexFiles")
  val orgRoot = new File(root, orgId)
  val factoryDir = new File(orgRoot, "factory")
  val categoriesDir = new File(orgRoot, "categories")
  val datasetsDir = new File(orgRoot, "datasets")
  val rawDir = new File(orgRoot, "raw")
  val sipsDir = new File(orgRoot, "sips")

  val categoriesRepo = new CategoriesRepo(categoriesDir)
  val sipFactory = new SipFactory(factoryDir)
  val ts = new TripleStore(TRIPLE_STORE_URL)
  val us = new ActorStore(ts)

  orgRoot.mkdirs()
  factoryDir.mkdirs()
  datasetsDir.mkdir()
  rawDir.mkdirs()
  sipsDir.mkdirs()

  def clear() = {
    clearDir(datasetsDir)
    clearDir(sipsDir)
    clearDir(rawDir)
    // todo: categories too when they are no longer defined there
  }

  def createDatasetRepo(owner: NXActor, spec: String, characterString: String, prefix: String) = {
    val character: Option[Character] = DsInfo.getCharacter(characterString)
    character.map(c => DsInfo.create(owner, spec, c, prefix, ts))
  }

  def datasetContext(spec: String): DatasetContext = datasetContextOption(spec).getOrElse(
    throw new RuntimeException(s"Expected $spec dataset to exist")
  )

  def datasetContextOption(spec: String): Option[DatasetContext] = {
    val futureInfoOpt = DsInfo.check(spec, ts)
    val infoOpt = Await.result(futureInfoOpt, 5.seconds)
    infoOpt.map(info => new DatasetContext(this, info).mkdirs)
  }

  def skosMappingStore(specA: String, specB: String): SkosMappingStore = {
    val futureStore = for {
      skosInfoA <- SkosInfo.check(specA, ts)
      skosInfoB <- SkosInfo.check(specB, ts)
    } yield (skosInfoA, skosInfoB) match {
      case (Some(a), Some(b)) => new SkosMappingStore(a, b, ts)
      case _ => throw new RuntimeException(s"No SKOS mapping found for $specA, $specB")
    }
    Await.result(futureStore, 5.seconds)
  }

  def availableSips: Seq[AvailableSip] = sipsDir.listFiles.toSeq.filter(
    _.getName.endsWith(SIP_EXTENSION)
  ).map(AvailableSip).sortBy(_.dateTime.getMillis).reverse

  def uploadedSips: Future[Seq[Sip]] = {
    DsInfo.listDsInfo(ts).map { list =>
      list.flatMap { dsi =>
        val datasetContext = new DatasetContext(this, dsi)
        datasetContext.sipRepo.latestSipOpt
      }
    }
  }

  def startCategoryCounts() = {
    val categoryDatasets = DsInfo.listDsInfo(ts).map { list =>
      list.flatMap { dsi =>
        if (dsi.getBooleanProp(categoriesInclude)) Some(dsi) else None
      }
    }
    categoryDatasets.map { dsList =>
      OrgActor.actor ! DatasetsCountCategories(dsList.map(_.spec))
    }
  }

//  def thesaurusDb(conceptSchemeA: String, conceptSchemeB: String) =
//    if (conceptSchemeA > conceptSchemeB)
//      new ThesaurusDb(conceptSchemeB, conceptSchemeA)
//    else
//      new ThesaurusDb(conceptSchemeA, conceptSchemeB)

}