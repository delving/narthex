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

package organization

import java.io.File
import javax.inject._

import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.actor.Props
import dataset.DsInfo.withDsInfo
import dataset.SipRepo.{AvailableSip, SIP_EXTENSION}
import dataset._
import init.NarthexConfig
import mapping._
import organization.OrgActor.DatasetsCountCategories
import play.api.cache.SyncCacheApi
import play.api.libs.ws.WSClient
import services.MailService
import triplestore.GraphProperties.categoriesInclude
import triplestore.TripleStore

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.language.postfixOps

/**
  * Best way to describe this class is that it has functioned as some means of passing around globals.
  * It is obvious that we need to remove this class and only DI the specific values that a component requires,
  * allowing this class to be deleted
  */
 @Singleton
class OrgContext @Inject() (
  val narthexConfig: NarthexConfig,
  val cacheApi: SyncCacheApi,
  val wsApi: WSClient,
  val mailService: MailService,
  val actorSystem: ActorSystem
) (ec: ExecutionContext, implicit val ts: TripleStore) {

  val root = narthexConfig.narthexDataDir
  val orgRoot = new File(root, narthexConfig.orgId)
  val factoryDir = new File(orgRoot, "factory")
  val categoriesDir = new File(orgRoot, "categories")
  val datasetsDir = new File(orgRoot, "datasets")
  val rawDir = new File(orgRoot, "raw")
  val sipsDir = new File(orgRoot, "sips")
  val crunchWhiteSpace = narthexConfig.crunchWhiteSpace
  val semaphore = new Semaphore(narthexConfig.concurrencyLimit)
  val saveSemaphore = new Semaphore(narthexConfig.concurrencyLimit)

  lazy val categoriesRepo = new CategoriesRepo(categoriesDir, narthexConfig.orgId)
  lazy val sipFactory = new SipFactory(factoryDir, narthexConfig.rdfBaseUrl, wsApi, narthexConfig.orgId)

  orgRoot.mkdirs()
  factoryDir.mkdirs()
  datasetsDir.mkdir()
  rawDir.mkdirs()
  sipsDir.mkdirs()

  def createDsInfo(spec: String, characterString: String, prefix: String) = {
    val character = DsInfo.getCharacter(characterString).get
    DsInfo.createDsInfo(spec, character, prefix, this)
  }

  def datasetContext(spec: String): DatasetContext = withDsInfo(spec, this)(dsInfo => new DatasetContext(this, dsInfo))

  def vocabMappingStore(specA: String, specB: String): VocabMappingStore = {
    val futureStore = for {
      infoA <- VocabInfo.freshVocabInfo(specA, this)
      infoB <- VocabInfo.freshVocabInfo(specB, this)
    } yield (infoA, infoB) match {
        case (Some(a), Some(b)) => new VocabMappingStore(a, b, this)
        case _ => throw new RuntimeException(s"No vocabulary mapping found for $specA, $specB")
      }
    Await.result(futureStore, 15.seconds)
  }

  def termMappingStore(spec: String): TermMappingStore = {
    withDsInfo(spec, this)(dsInfo => new TermMappingStore(dsInfo, this, this.wsApi))
  }

  def availableSips: Seq[AvailableSip] = sipsDir.listFiles.toSeq.filter(
    _.getName.endsWith(SIP_EXTENSION)
  ).map(AvailableSip).sortBy(_.dateTime.getMillis).reverse

  def uploadedSips: Future[Seq[Sip]] = {
    DsInfo.listDsInfo(this).map { list =>
      list.flatMap { dsi =>
        val datasetContext = new DatasetContext(this, dsi)
        datasetContext.sipRepo.latestSipOpt
      }
    }
  }

  lazy val orgActorInst = new OrgActor(this, actorSystem)

  lazy val orgActorRef: ActorRef = actorSystem.actorOf(Props(orgActorInst), narthexConfig.orgId)

  def orgActor: ActorRef = orgActorRef

  def startCategoryCounts() = {
    val catDatasets = DsInfo.listDsInfo(this).map(_.filter(_.getBooleanProp(categoriesInclude)))
    catDatasets.map { dsList =>
      orgActorRef ! DatasetsCountCategories(dsList.map(_.spec))
    }
  }

  def appConfig: NarthexConfig = narthexConfig

}
