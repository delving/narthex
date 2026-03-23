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
import java.text.SimpleDateFormat
import java.util.Date

import analysis.NodeRepo
import dataset.DatasetActor.Command
import dataset.DsInfo.DsState._
import dataset.Sip.SipMapper
import dataset.SourceRepo._
import harvest.Harvesting.HarvestType
import organization.OrgContext
import organization.OrgActor.DatasetMessage
import org.apache.commons.io.FileUtils.deleteQuietly
import play.api.Logger
import record.PocketParser
import record.SourceProcessor.{AdoptSource, GenerateSipZip}
import services.FileHandling.clearDir
import services.GlobalDsInfoService
import services.StringHandling.pathToDirectory
import services.{FileHandling, StringHandling}
import mapping.DatasetMappingRepo
import triplestore.GraphProperties

import scala.util.{Failure, Success, Try}

class DatasetContext(val orgContext: OrgContext, val spec: String, val dsInfo: DsInfo) {

  private val logger = Logger(getClass)

  val DATE_FORMAT = new SimpleDateFormat("yyyy_MM_dd_HH_mm")
  val rootDir = new File(orgContext.datasetsDir, spec)

  val rawDir = new File(rootDir, "raw")
  val sipsDir = new File(rootDir, "sips")
  val sourceDir = new File(rootDir, "source")
  val treeDir = new File(rootDir, "tree")
  val sourceTreeDir = new File(rootDir, "sourceTree")

  // Violations index files for linking violations to records
  def violationsIndex = new File(treeDir, "violations.jsonl")
  def sourceViolationsIndex = new File(sourceTreeDir, "violations.jsonl")
  val processedDir = new File(rootDir, "processed")
  val harvestLogger = new File(rootDir, "harvesting_log.txt")
  val activityLog = new File(rootDir, "activity.jsonl")
  val trendsLog = new File(rootDir, "trends.jsonl")
  val trendsDailyLog = new File(rootDir, "trends-daily.jsonl")

  // todo: maybe not put it in raw
  val pocketFile = new File(orgContext.rawDir, s"$spec.xml")

  def createSipFile = new File(orgContext.sipsDir, s"${spec}__${DATE_FORMAT.format(new Date())}.sip.zip")

  def sipFiles = orgContext.sipsDir.listFiles.filter(file => file.getName.startsWith(s"${spec}__")).sortBy(_.getName).reverse

  val treeRoot = new NodeRepo(this, treeDir)
  val sourceTreeRoot = new NodeRepo(this, sourceTreeDir)

  // Use org-level sipsDir to match where createSipFile writes
  lazy val sipRepo = new SipRepo(orgContext.sipsDir, dsInfo.spec, orgContext.appConfig.rdfBaseUrl)

  lazy val processedRepo = new ProcessedRepo(processedDir, dsInfo)

  lazy val datasetMappingRepo = new DatasetMappingRepo(rootDir)

  def sipMapperOpt: Option[SipMapper] = sipRepo.latestSipOpt.flatMap(_.createSipMapper)

  def mkdirs = {
    rootDir.mkdirs()
    this
  }

  def createRawFile(fileName: String): File = new File(clearDir(rawDir), fileName)

  // todo: recordContainer instead perhaps
  def setDelimiters(harvestTypeOpt: Option[HarvestType], recordRoot: String, uniqueId: String): Unit = rawXmlFile.foreach { raw =>
    harvestTypeOpt match {
      case Some(harvestType) =>
        dropRaw()
        createSourceRepo(SourceFacts(harvestType.toString, recordRoot, uniqueId, None))
      case None =>
        dropTree()
        createSourceRepo(SourceFacts("raw", recordRoot, uniqueId, None))
        orgContext.orgActor ! DatasetMessage(spec, AdoptSource(raw, orgContext))
    }
  }

  def createSourceRepo(sourceFacts: SourceFacts): SourceRepo = SourceRepo.createClean(sourceDir, sourceFacts, orgContext)

  def sourceRepoOpt: Option[SourceRepo] = if (sourceDir.exists()) Some(new SourceRepo(sourceDir, orgContext)) else None

  def acceptUpload(fileName: String, setTargetFile: File => File): Option[String] = {

    def sendRefresh() = orgContext.orgActor ! DatasetMessage(spec, Command("refresh"))

    def cleanupWorkflowStates(): Unit = GlobalDsInfoService.get().foreach(_.clearWorkflowStates(spec))

    if (fileName.endsWith(".csv")) {
      val csvFile = setTargetFile(createRawFile(fileName))
      val reader = FileHandling.createReader(csvFile)
      val baseName = fileName.substring(0, fileName.lastIndexOf("."))
      val xmlFile = new File(csvFile.getParentFile, s"$baseName.xml")
      val writer = FileHandling.createWriter(xmlFile)
      val tryConvert = Try(StringHandling.csvToXML(reader, writer))
      reader.close()
      writer.close()
      tryConvert match {
        case Success(_) => GlobalDsInfoService.get().foreach(_.setState(spec, "RAW"))
        case Failure(e) => GlobalDsInfoService.get().foreach(_.removeState(spec, "RAW"))
      }
      cleanupWorkflowStates()
      dropTree()
      sendRefresh()
      None
    }
    else if (fileName.endsWith(".xml.gz") || fileName.endsWith(".xml")) {
      setTargetFile(createRawFile(fileName))
      GlobalDsInfoService.get().foreach(_.setState(spec, "RAW"))
      cleanupWorkflowStates()
      dsInfo.removeLiteralProp(GraphProperties.harvestType)
      dropTree()
      sendRefresh()
      None
    }
    else if (fileName.endsWith(".sip.zip")) {
      val sipZipFile = setTargetFile(sipRepo.createSipZipFile(fileName))
      sipRepo.latestSipOpt.map { sip =>
        GlobalDsInfoService.get().foreach { svc =>
          svc.setMetadata(
            spec = spec,
            name = sip.name.getOrElse(""),
            description = "",
            aggregator = sip.provider.getOrElse(""),
            owner = sip.dataProvider.getOrElse(""),
            dataProviderURL = sip.dataProviderURL.getOrElse(""),
            language = sip.language.getOrElse(""),
            rights = sip.rights.getOrElse(""),
            dataType = sip.dataType.getOrElse(""),
            edmType = sip.edmType.getOrElse("")
          )
          svc.upsertHarvestConfig(
            spec = spec,
            harvestType = sip.harvestType.flatMap(HarvestType.harvestTypeFromString).map(_.toString),
            harvestUrl = sip.harvestUrl,
            harvestDataset = sip.harvestSpec,
            harvestPrefix = sip.harvestPrefix
          )
        }
        // todo: should probably wait for the above future
        sip.sipMappingOpt.map { sipMapping =>
          if (sip.containsSource) {
            createSourceRepo(PocketParser.POCKET_SOURCE_FACTS)
            sip.copySourceToTempFile.map { sourceFile =>
              orgContext.orgActor ! DatasetMessage(spec, AdoptSource(sourceFile, orgContext))
              sendRefresh()
              None
            } getOrElse {
              dropSourceRepo()
              Some(s"No source found in $sipZipFile for $spec")
            }
          }
          else {
            sendRefresh()
            None
          }
        } getOrElse {
          logger.error(s"No mapping found in SIP file $sipZipFile for dataset $spec")
          deleteQuietly(sipZipFile)
          Some(s"No mapping found in $sipZipFile for $spec")
        }
      } getOrElse {
        logger.error(s"Unable to read SIP file ${sipZipFile.getName} for dataset $spec - sipRepo.latestSipOpt returned None")
        deleteQuietly(sipZipFile)
        Some(s"Unable to use ${sipZipFile.getName} for $spec")
      }
    }
    else {
      Some(s"Unrecognized file suffix: $fileName")
    }
  }

  def rawXmlFile: Option[File] = if (rawDir.exists()) rawDir.listFiles.find { file =>
    file.getName.endsWith(".xml") || file.getName.endsWith(".xml.gz")
  } else None

  def singleHarvestZip: Option[File] = {
    val allZip = sourceDir.listFiles.filter(_.getName.endsWith("zip"))
    if (allZip.length > 1) throw new RuntimeException(s"Multiple zip files where one was expected: $allZip")
    allZip.headOption
  }

  def dropRaw() = {
    dropSourceRepo()
    deleteQuietly(rawDir)
    GlobalDsInfoService.get().foreach(_.removeState(spec, "RAW"))
  }

  def dropSourceRepo() = {
    dropProcessedRepo()
    // todo: note that we lose the delimiters this way:
    deleteQuietly(sourceDir)
    GlobalDsInfoService.get().foreach(_.removeState(spec, "SOURCED"))
    sipFiles.foreach(deleteQuietly)
    GlobalDsInfoService.get().foreach { svc =>
      svc.removeState(spec, "MAPPABLE")
      svc.removeState(spec, "PROCESSABLE")
    }
  }

  def dropProcessedRepo() = {
    dropTree()
    deleteQuietly(processedDir)
    GlobalDsInfoService.get().foreach(_.removeState(spec, "PROCESSED"))
  }

  def dropTree() = {
    // dropRecords should not be called here
    // dropRecords
    deleteQuietly(treeDir)
    GlobalDsInfoService.get().foreach { svc =>
      svc.removeState(spec, "RAW_ANALYZED")
      svc.removeState(spec, "ANALYZED")
    }
    logger.debug("Dropping analysis tree")
  }

  def dropSourceTree() = {
    deleteQuietly(sourceTreeDir)
    GlobalDsInfoService.get().foreach(_.removeState(spec, "SOURCE_ANALYZED"))
    logger.debug("Dropping source analysis tree")
  }

  def dropRecords = {
    GlobalDsInfoService.get().foreach(_.removeState(spec, "SAVED"))
    dsInfo.dropDatasetRecords
  }

  def dropIndex = {
    GlobalDsInfoService.get().foreach(_.removeState(spec, "SAVED"))
    dsInfo.dropDatasetIndex
    dsInfo.dropDatasetRecords
  }

  def disableDataSet = {
    GlobalDsInfoService.get().foreach { svc =>
      svc.setState(spec, "DISABLED")
    }
    dsInfo.dropDatasetIndex
    dsInfo.dropDatasetRecords
  }

  def startSipZipGeneration() = orgContext.orgActor ! DatasetMessage(spec, GenerateSipZip)

  // ==================================================

  def index = new File(treeDir, "index.json")

  def nodeRepo(path: String): Option[NodeRepo] = {
    val nodeDir = path.split('/').toList.foldLeft(treeDir)((file, tag) => new File(file, pathToDirectory(tag)))
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

  def histogram(path: String, size: Int): Option[File] = nodeRepo(path).map { repo =>
    val fileList = repo.histogramJson.filter(pair => pair._1 == size)
    fileList.headOption.map(_._2)
  } getOrElse None

  def uriText(path: String): Option[File] = nodeRepo(path).map(_.uriText)

  def uniqueText(path: String): Option[File] = nodeRepo(path).map(_.uniqueText)

  def histogramText(path: String): Option[File] = nodeRepo(path).map(_.histogramText)

  // ==================================================
  // Source Analysis Methods

  def sourceIndex = new File(sourceTreeDir, "index.json")

  def sourceNodeRepo(path: String): Option[NodeRepo] = {
    val nodeDir = path.split('/').toList.foldLeft(sourceTreeDir)((file, tag) => new File(file, pathToDirectory(tag)))
    if (nodeDir.exists()) Some(new NodeRepo(this, nodeDir)) else None
  }

  def sourceStatus(path: String): Option[File] = sourceNodeRepo(path).map(_.status)

  def sourceSample(path: String, size: Int): Option[File] = {
    sourceNodeRepo(path) match {
      case None => None
      case Some(repo) =>
        val fileList = repo.sampleJson.filter(pair => pair._1 == size)
        if (fileList.isEmpty) None else Some(fileList.head._2)
    }
  }

  def sourceHistogram(path: String, size: Int): Option[File] = sourceNodeRepo(path).map { repo =>
    val fileList = repo.histogramJson.filter(pair => pair._1 == size)
    fileList.headOption.map(_._2)
  } getOrElse None

  def sourceUriText(path: String): Option[File] = sourceNodeRepo(path).map(_.uriText)

  def sourceUniqueText(path: String): Option[File] = sourceNodeRepo(path).map(_.uniqueText)

  def sourceHistogramText(path: String): Option[File] = sourceNodeRepo(path).map(_.histogramText)

  override def toString = spec


}
