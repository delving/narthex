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
import java.util

import dataset.DsInfo.DsCharacter
import dataset.SipRepo.{AvailableSip, SIP_EXTENSION}
import dataset._
import harvest.PeriodicHarvest
import harvest.PeriodicHarvest.ScanForHarvests
import mapping.Skosifier.ScanForWork
import mapping._
import org.ActorStore.NXActor
import org.OrgActor.DatasetsCountCategories
import play.api.Play
import play.libs.Akka._
import services.FileHandling.clearDir
import triplestore.GraphProperties.categoriesInclude
import triplestore.TripleStore

import scala.collection.JavaConversions._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.language.postfixOps

object OrgContext {
  val config = Play.current.configuration

  def configFlag(name: String): Boolean = config.getBoolean(name).getOrElse(false)
  def configString(name: String) = config.getString(name).getOrElse(
    throw new RuntimeException(s"Missing config string: $name")
  )

  def configStringNoSlash(name: String) = configString(name).replaceAll("\\/$", "")

  def configInt(name: String) = config.getInt(name).getOrElse(
    throw new RuntimeException(s"Missing config int: $name")
  )
  def secretList(name: String): util.List[String] = config.getStringList(name).getOrElse(List("secret"))

  val USER_HOME = System.getProperty("user.home")
  val NARTHEX = new File(USER_HOME, "NarthexFiles")

  lazy val API_ACCESS_KEYS = secretList("api.accessKeys")

  lazy val HARVEST_TIMEOUT = config.getInt("harvest.timeout").getOrElse(3 * 60 * 1000)

  def apiKeyFits(accessKey: String) = API_ACCESS_KEYS.contains(accessKey)

  val ORG_ID = configString("orgId")
  val NARTHEX_DOMAIN = configStringNoSlash("domains.narthex")
  val NAVE_DOMAIN = configStringNoSlash("domains.nave")

  val SHOW_CATEGORIES = configFlag("categories")

  val TRIPLE_STORE_URL = configString("triple-store")

  val NX_URI_PREFIX = s"$NAVE_DOMAIN/resource"

  val ts = new TripleStore(TRIPLE_STORE_URL, configFlag("triple-store-log"))
  val periodicHarvest = system.actorOf(PeriodicHarvest.props(), "PeriodicHarvest")
  val harvestTicker = system.scheduler.schedule(5.seconds, 5.minutes, periodicHarvest, ScanForHarvests)
  val skosifier = system.actorOf(Skosifier.props(ts), "Skosifier")
  val skosifierTicker = system.scheduler.schedule(5.seconds, 20.seconds, skosifier, ScanForWork)
  val orgContext = new OrgContext(USER_HOME, ORG_ID, ts)
}

class OrgContext(userHome: String, val orgId: String, ts: TripleStore) {

  val root = new File(userHome, "NarthexFiles")
  val orgRoot = new File(root, orgId)
  val factoryDir = new File(orgRoot, "factory")
  val categoriesDir = new File(orgRoot, "categories")
  val datasetsDir = new File(orgRoot, "datasets")
  val rawDir = new File(orgRoot, "raw")
  val sipsDir = new File(orgRoot, "sips")

  val categoriesRepo = new CategoriesRepo(categoriesDir)
  val sipFactory = new SipFactory(factoryDir)
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
    val character: Option[DsCharacter] = DsInfo.getCharacter(characterString)
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

  def vocabMappingStore(specA: String, specB: String): VocabMappingStore = {
    val futureStore = for {
      infoA <- VocabInfo.check(specA, ts)
      infoB <- VocabInfo.check(specB, ts)
    } yield (infoA, infoB) match {
      case (Some(a), Some(b)) => new VocabMappingStore(a, b, ts)
      case _ => throw new RuntimeException(s"No vocabulary mapping found for $specA, $specB")
    }
    Await.result(futureStore, 15.seconds)
  }

  def termMappingStore(dsSpec: String): TermMappingStore = {
    val futureStore = DsInfo.check(dsSpec, ts).map { dsInfoOpt =>
      dsInfoOpt.map( info =>new TermMappingStore(info, ts)).getOrElse(
        throw new RuntimeException(s"No term mapping found for $dsSpec")
      )
    }
    Await.result(futureStore, 15.seconds)
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