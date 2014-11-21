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
package dataset

import java.io.File
import java.util.concurrent.TimeUnit

import akka.pattern.ask
import akka.util.Timeout
import analysis.NodeRepo
import dataset.DatasetActor.{StartAnalysis, StartCategoryCounting, StartHarvest, StartSaving}
import dataset.DatasetOrigin._
import dataset.DatasetState._
import dataset.ProgressState._
import dataset.StagingRepo.StagingFacts
import harvest.Harvesting
import harvest.Harvesting._
import mapping.{CategoryDb, TermDb}
import org.OrgActor.{DatasetMessage, InterruptDataset}
import org.apache.commons.io.FileUtils
import org.{OrgActor, OrgRepo}
import play.Logger
import play.api.Play.current
import play.api.cache.Cache
import record.EnrichmentParser._
import record.PocketParser._
import record.{EnrichmentParser, RecordDb}
import services.FileHandling.clearDir
import services.Temporal._
import services._

import scala.concurrent.Await

// todo: use the actor's execution context?

class DatasetRepo(val orgRepo: OrgRepo, val datasetName: String) {

  private val rootDir = new File(orgRepo.datasetsDir, datasetName)

  private val treeDir = new File(rootDir, "tree")
  private val stagingDir = new File(rootDir, "staging")
  private val sipsDir = new File(rootDir, "sips")
  private val rawDir = new File(rootDir, "raw")

  val sourceFile = new File(orgRepo.sourceDir, s"$datasetName.xml")
  val treeRoot = new NodeRepo(this, treeDir)

  lazy val datasetDb = new DatasetDb(orgRepo.repoDb, datasetName)
  val dbBaseName = s"narthex_${orgRepo.orgId}___$datasetName"
  lazy val termDb = new TermDb(dbBaseName)
  lazy val categoryDb = new CategoryDb(dbBaseName)
  lazy val sipRepo = SipRepo(sipsDir)
  lazy val stagingRepo = StagingRepo(stagingDir)
  lazy val recordDbOpt = datasetDb.prefixOpt.map(prefix => new RecordDb(this, dbBaseName, prefix))

  override def toString = datasetName

  def mkdirs = {
    rootDir.mkdirs()
    treeDir.mkdir()
    this
  }

  def clearTreeDir() = clearDir(treeDir)

  def createPocketPath(pocket: Pocket) = {
    val h = pocket.hash
    s"$datasetName/${h(0)}/${h(1)}/${h(2)}/$h.xml"
  }

  def createStagingRepo(stagingFacts: StagingFacts): StagingRepo = StagingRepo.createClean(stagingDir, stagingFacts)

  def createRawFile(fileName: String): File = new File(clearDir(rawDir), fileName)

  def rawFile: Option[File] = {
    if (rawDir.exists()) {
      rawDir.listFiles.headOption
    }
    else {
      None
    }
  }

  def singleHarvestZip: Option[File] = {
    val allZip = stagingDir.listFiles.filter(_.getName.endsWith("zip"))
    if (allZip.size > 1) throw new RuntimeException(s"Multiple zip files where one was expected: $allZip")
    allZip.headOption
  }

  def firstHarvest(harvestType: HarvestType, url: String, dataset: String, prefix: String) = datasetDb.infoOpt map { info =>
    val state = DatasetState.datasetStateFromInfo(info)
    if (state == EMPTY) {
      clearDir(stagingDir) // it's a first harvest
      clearDir(treeDir) // just in case
      datasetDb.startProgress(HARVESTING)
      if (originFromInfo(info).isEmpty) {
        datasetDb.setOrigin(HARVEST, prefix)
        datasetDb.setHarvestInfo(harvestType, url, dataset, prefix)
        datasetDb.setRecordDelimiter(harvestType.recordRoot, harvestType.uniqueId)
      }
      StagingRepo.createClean(stagingDir, StagingFacts(harvestType))
      datasetDb.setHarvestCron(Harvesting.harvestCron(info)) // a clean one
      Logger.info(s"First Harvest $datasetName")
      OrgActor.actor ! DatasetMessage(datasetName, StartHarvest(None, justDate = true))
    }
    else {
      Logger.warn(s"Harvest can only be started in $EMPTY, not $state")
    }
  }

  def nextHarvest() = datasetDb.infoOpt.map { info =>
    val recordsTimeOption = nodeSeqToTime(info \ "records" \ "time")
    recordsTimeOption.map { recordsTime =>
      val harvestCron = Harvesting.harvestCron(info)
      if (harvestCron.timeToWork) {
        val nextHarvestCron = harvestCron.next
        // if the next is also to take place immediately, force the harvest cron to now
        datasetDb.setHarvestCron(if (nextHarvestCron.timeToWork) harvestCron.now else nextHarvestCron)
        datasetDb.startProgress(HARVESTING)
        val justDate = harvestCron.unit == DelayUnit.WEEKS
        OrgActor.actor ! DatasetMessage(datasetName, StartHarvest(Some(harvestCron.previous), justDate))
      }
      else {
        Logger.info(s"No re-harvest of $datasetName with cron $harvestCron because it's not time $harvestCron")
      }
    } getOrElse {
      Logger.warn(s"Incremental harvest of $datasetName can only be started when there are saved records")
      val harvestCron = Harvesting.harvestCron(info)
      datasetDb.setHarvestCron(harvestCron.now)
    }
  }

  def startAnalysis() = datasetDb.infoOpt.map { info =>
    val state = DatasetState.datasetStateFromInfo(info)
    if (state == SOURCED) {
      clearDir(treeDir)
      datasetDb.startProgress(SPLITTING)
      // todo: maybe for prefixes?
      OrgActor.actor ! DatasetMessage(datasetName, StartAnalysis())
    }
    else {
      Logger.warn(s"Analyzing $datasetName can only be started when the state is sourced, but it's $state")
    }
  }

  def firstSaveRecords() = datasetDb.infoOpt.map { info =>
    val delimited = datasetDb.isDelimited(Some(info))
    val state = DatasetState.datasetStateFromInfo(info)
    if (state == SOURCED && delimited) {
      datasetDb.startProgress(SAVING)
      val sipMapperOpt = sipRepo.latestSipFile.flatMap { sipFile =>
        val mapperOpt = sipFile.createSipMapper
        mapperOpt.foreach(mapper => recordDbOpt.get.createDb())
        mapperOpt
      }
      val fileOpt = None
      // todo:
      //      // for a sip-harvest we have a single zip file in the harvest dir
      //      val fileOpt: Option[File] = originFromInfo(info) match {
      //        case Some(SIP_HARVEST) => singleHarvestZip
      //        case Some(HARVEST) => singleHarvestZip
      //        case _ => getLatestSourceFile
      //      }
      fileOpt.map { file =>
        OrgActor.actor ! DatasetMessage(datasetName, StartSaving(None, file, sipMapperOpt))
      } getOrElse {
        Logger.warn(s"Cannot find file to save for $datasetName")
      }
    }
    else {
      Logger.warn(s"First save of $datasetName can only be started with state sourced/delimited, but it's $state/$delimited")
    }
  }

  def startCategoryCounts() = {
    datasetDb.startProgress(CATEGORIZING)
    OrgActor.actor ! DatasetMessage(datasetName, StartCategoryCounting())
  }

  def interruptProgress: Boolean = {
    implicit val timeout = Timeout(100, TimeUnit.MILLISECONDS)
    val answer = OrgActor.actor ? InterruptDataset(datasetName)
    val interrupted = Await.result(answer, timeout.duration).asInstanceOf[Boolean]
    if (!interrupted) datasetDb.endProgress(Some("Terminated processing"))
    interrupted
  }

  def revertState: Option[DatasetState] = {
    val currentState = datasetDb.infoOpt.map(DatasetState.datasetStateFromInfo(_))
    Logger.info(s"Revert state of $datasetName from $currentState")
    datasetDb.infoOpt.flatMap { info =>
      currentState match {
        case None =>
          datasetDb.createDataset
          Some(EMPTY)
        case Some(RAW) =>
          FileUtils.deleteQuietly(rawDir)
          datasetDb.dropDataset()
          None
        case Some(EMPTY) =>
          if (rawDir.exists()) {
            Some(RAW)
          }
          else {
            datasetDb.dropDataset()
            None
          }
        case Some(SOURCED) =>
          val treeTimeOption = nodeSeqToTime(info \ "tree" \ "time")
          val recordsTimeOption = nodeSeqToTime(info \ "records" \ "time")
          if (treeTimeOption.isEmpty && recordsTimeOption.isEmpty) {
            FileUtils.deleteQuietly(sourceFile)
            val nextStatus = if (rawDir.exists()) RAW else EMPTY
            datasetDb.setStatus(nextStatus)
            Some(nextStatus)
          }
          else {
            currentState
          }
        case _ =>
          currentState
      }
    }
  }

  def index = new File(treeDir, "index.json")

  def nodeRepo(path: String): Option[NodeRepo] = {
    val nodeDir = path.split('/').toList.foldLeft(treeDir)((file, tag) => new File(file, OrgRepo.pathToDirectory(tag)))
    if (nodeDir.exists()) Some(new NodeRepo(this, nodeDir)) else None
  }

  def status(path: String): Option[File] = nodeRepo(path).map(_.status)

  def sample(path: String, size: Int): Option[File] = {
    nodeRepo(path) match {
      case None => None
      case Some(nodeRepo) =>
        val fileList = nodeRepo.sampleJson.filter(pair => pair._1 == size)
        if (fileList.isEmpty) None else Some(fileList.head._2)
    }
  }

  def histograms(path: String, size: Int): Option[File] = nodeRepo(path).map { repo =>
    val fileList = repo.histogramJson.filter(pair => pair._1 == size)
    fileList.headOption.map(_._2)
  } getOrElse None

  def histogram(path: String, size: Int): Option[File] = nodeRepo(path).map { repo =>
    val fileList = repo.histogramJson.filter(pair => pair._1 == size)
    fileList.headOption.map(_._2)
  } getOrElse None

  def indexText(path: String): Option[File] = nodeRepo(path).map(_.indexText)

  def uniqueText(path: String): Option[File] = nodeRepo(path).map(_.uniqueText)

  def histogramText(path: String): Option[File] = nodeRepo(path).map(_.histogramText)

  def enrichRecords(storedRecords: String): List[StoredRecord] = {
    val mappings: Map[String, TargetConcept] = Cache.getOrElse[Map[String, TargetConcept]](datasetName) {
      termDb.getMappings.map(m => (m.source, TargetConcept(m.target, m.vocabulary, m.prefLabel))).toMap
    }
    val pathPrefix = s"${NarthexConfig.ORG_ID}/$datasetName"
    val parser = new EnrichmentParser(pathPrefix, mappings)
    parser.parse(storedRecords)
  }

  def invalidateEnrichmentCache() = Cache.remove(datasetName)
}
