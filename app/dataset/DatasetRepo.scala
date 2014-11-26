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
import dataset.DatasetState._
import dataset.ProgressState._
import dataset.Sip.{RDF_PREFIX, RDF_URI, SipMapper}
import dataset.StagingRepo._
import harvest.Harvesting
import harvest.Harvesting.HarvestType._
import harvest.Harvesting._
import mapping.{CategoryDb, TermDb}
import org.OrgActor.{DatasetMessage, InterruptDataset}
import org.apache.commons.io.FileUtils.deleteQuietly
import org.{OrgActor, OrgRepo}
import play.Logger
import play.api.Play.current
import play.api.cache.Cache
import record.EnrichmentParser._
import record.Saver.AdoptSource
import record.{EnrichmentParser, RecordDb}
import services.FileHandling.clearDir
import services.Temporal._
import services._

import scala.concurrent._

class DatasetRepo(val orgRepo: OrgRepo, val datasetName: String) {

  private val rootDir = new File(orgRepo.datasetsDir, datasetName)
  private val dbBaseName = s"narthex_${orgRepo.orgId}___$datasetName"

  private val treeDir = new File(rootDir, "tree")
  private val stagingDir = new File(rootDir, "staging")
  private val sipsDir = new File(rootDir, "sips")
  private val rawDir = new File(rootDir, "raw")

  val pocketFile = new File(orgRepo.sipsDir, s"$datasetName-pockets.xml")
  val sipFile = new File(orgRepo.sipsDir, s"${datasetName}_sip.zip")
  val mappedFile = new File(orgRepo.sourceDir, s"$datasetName.xml")

  val treeRoot = new NodeRepo(this, treeDir)

  lazy val datasetDb = new DatasetDb(orgRepo.repoDb, datasetName)
  lazy val termDb = new TermDb(dbBaseName)
  lazy val categoryDb = new CategoryDb(dbBaseName)
  lazy val sipRepo = SipRepo(datasetName, NarthexConfig.RDF_ABOUT_PREFIX, sipsDir)
  lazy val recordDbOpt = datasetDb.prefixOpt.map(prefix => new RecordDb(this, dbBaseName, prefix))

  def sipMapperOpt: Option[SipMapper] = sipRepo.latestSipOpt.flatMap(_.createSipMapper)

  override def toString = datasetName

  def mkdirs = {
    rootDir.mkdirs()
    this
  }

  def receiveCommand(command: String): String = {
    command match {

      case "delete" =>
        datasetDb.dropDataset()
        deleteQuietly(rootDir)
        "deleted"

      case "interrupt" =>
        if (interruptProgress) "interrupted" else "killed"

      case "remove source" =>
        deleteQuietly(pocketFile)
        deleteQuietly(mappedFile)
        deleteQuietly(rawDir)
        deleteQuietly(stagingDir)
        datasetDb.setStatus(EMPTY)
        datasetDb.endProgress(None)
        "source removed"

      case "remove tree" =>
        deleteQuietly(treeDir)
        datasetDb.setTree(ready = false)
        datasetDb.endProgress(None)
        "tree removed"

      case "remove records" =>
        recordDbOpt.map(_.dropDb())
        datasetDb.setRecords(ready = false, 0)
        datasetDb.endProgress(None)
        "records removed"

      case _ =>
        Logger.warn(s"$this sent unrecognized command $command")
        "unrecognized"
    }
  }

  def createStagingRepo(stagingFacts: StagingFacts): StagingRepo = StagingRepo.createClean(stagingDir, stagingFacts)

  def dropStagingRepo() = {
    deleteQuietly(stagingDir)
    datasetDb.setStatus(EMPTY)
    datasetDb.setSource(ready = false, 0)
  }

  def dropTree() = {
    deleteQuietly(treeDir)
    datasetDb.setTree(ready = false)
  }

  def dropRecords() = {
    recordDbOpt.map(_.dropDb())
    datasetDb.setRecords(ready = false, 0)
  }

  def stagingRepoOpt: Option[StagingRepo] = if (stagingDir.exists()) Some(StagingRepo(stagingDir)) else None

  def createRawFile(fileName: String): File = new File(clearDir(rawDir), fileName)

  // doing this with futures?
  //  def setRawDelimiters(recordRoot: String, uniqueId: String) = {
  //    rawFile.map { raw =>
  //      val stagingRepo = createStagingRepo(StagingFacts("from-raw", recordRoot, uniqueId, None))
  //      dropTree()
  //      val adoptFile = future {
  //        val progressReporter = ProgressReporter(ADOPTING, datasetDb)
  //        //          progress = Some(progressReporter)
  //        stagingRepo.acceptFile(raw, progressReporter).map { acceptedFile =>
  //          val progressReporter = ProgressReporter(GENERATING, datasetDb)
  //          //          progress = Some(progressReporter)
  //          val sipMapperOpt = sipRepo.latestSipOpt.flatMap(_.createSipMapper)
  //          val recordCount = stagingRepo.generateSource(sourceFile, sipMapperOpt, datasetDb.setNamespaceMap, progressReporter)
  //          if (recordCount > 0) datasetDb.setStatus(SOURCED)
  //          datasetDb.setSource(recordCount > 0, recordCount)
  //        }
  //      }
  //      adoptFile.onSuccess {
  //        case b =>
  //          startAnalysis()
  //      }
  //    }
  //  }

  def setRawDelimiters(recordRoot: String, uniqueId: String) = {
    rawFile.map { raw =>
      createStagingRepo(StagingFacts("from-raw", recordRoot, uniqueId, None))
      dropTree()
      OrgActor.actor ! DatasetMessage(datasetName, AdoptSource(raw))
    }
  }

  def acceptUpload(fileName: String, prefix: String, setTargetFile: File => File): Option[String] = {
    val db = datasetDb
    if (fileName.endsWith(".xml.gz") || fileName.endsWith(".xml")) {
      db.setMetadataPrefix(prefix)
      db.setStatus(RAW)
      setTargetFile(createRawFile(fileName))
      startAnalysis()
      None
    }
    else if (fileName.endsWith(".sip.zip")) {
      val sipZipFile = setTargetFile(sipRepo.createSipZipFile(fileName))
      sipRepo.latestSipOpt.map { sip =>
        db.setSipFacts(sip.facts)
        db.setSipHints(sip.hints)
        db.setMetadataPrefix(prefix)
        sip.sipMappingOpt.map { sipMapping =>
          // must add RDF since the mapping output uses it
          val namespaces = sipMapping.namespaces + (RDF_PREFIX -> RDF_URI)
          db.setNamespaceMap(namespaces)
          sip.harvestUrl.map { harvestUrl =>
            // the harvest information is in the Sip, but no source
            firstHarvest(PMH, harvestUrl, sip.harvestSpec.getOrElse(""), sip.harvestPrefix.getOrElse(""))
          } getOrElse {
            // there is no harvest information so there must be source
            val stagingRepo = createStagingRepo(DELVING_SIP_SOURCE)
            sip.copySourceToTempFile.map { sourceFile =>
              OrgActor.actor ! DatasetMessage(datasetName, AdoptSource(sourceFile))
              None
            } getOrElse {
              dropStagingRepo()
              Some(s"No source found in $sipZipFile for $datasetName")
            }
          }
        } getOrElse {
          deleteQuietly(sipZipFile)
          Some(s"No mapping found in $sipZipFile for $datasetName")
        }
      } getOrElse {
        deleteQuietly(sipZipFile)
        Some(s"Unable to use $sipZipFile.getName for $datasetName")
      }
    }
    else {
      Some(s"Unrecognized file suffix: $fileName")
    }
  }

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

  def firstHarvest(harvestType: HarvestType, url: String, dataset: String, prefix: String): Option[String] = {
    datasetDb.infoOpt.map { info =>
      val state = DatasetState.datasetStateFromInfo(info)
      if (state == EMPTY) {
        dropStagingRepo()
        dropTree()
        createStagingRepo(StagingFacts(harvestType))
        datasetDb.startProgress(HARVESTING)
        datasetDb.setMetadataPrefix(prefix)
        datasetDb.setHarvestInfo(harvestType, url, dataset, prefix)
        datasetDb.setHarvestCron(Harvesting.harvestCron(info)) // a clean one
        Logger.info(s"First Harvest $datasetName")
        OrgActor.actor ! DatasetMessage(datasetName, StartHarvest(None, justDate = true))
        None
      }
      else {
        Some(s"Harvest can only be started in $EMPTY, not $state")
      }
    } getOrElse Some("Dataset not found!")
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
    dropTree()
    datasetDb.startProgress(SPLITTING)
    OrgActor.actor ! DatasetMessage(datasetName, StartAnalysis())
  }

  def firstSaveRecords() = datasetDb.infoOpt.map { info =>
    val state = DatasetState.datasetStateFromInfo(info)
    if (state == SOURCED) {
      datasetDb.startProgress(SAVING)
      recordDbOpt.get.createDb()
      OrgActor.actor ! DatasetMessage(datasetName, StartSaving(None))
    }
    else {
      Logger.warn(s"First save of $datasetName can only be started with state sourced when there is a source file")
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
