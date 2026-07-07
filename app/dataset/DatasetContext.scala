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
import dataset.DsInfo.DsMetadata
import dataset.DsInfo.DsState._
import dataset.Sip.SipMapper
import dataset.SourceRepo._
import harvest.Harvesting.HarvestType
import organization.OrgContext
import org.apache.commons.io.FileUtils.deleteQuietly
import play.api.Logger
import record.PocketParser
import record.SourceProcessor.{AdoptSource, GenerateSipZip}
import services.FileHandling.clearDir
import services.StringHandling.pathToDirectory
import services.{FileHandling, StringHandling}
import mapping.DatasetMappingRepo
import triplestore.GraphProperties

import scala.util.{Failure, Success, Try}

class DatasetContext(val orgContext: OrgContext, val dsInfo: DsInfo) {

  private val logger = Logger(getClass)

  val DATE_FORMAT = new SimpleDateFormat("yyyy_MM_dd_HH_mm")
  val rootDir = new File(orgContext.datasetsDir, dsInfo.spec)

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
  val pocketFile = new File(orgContext.rawDir, s"$dsInfo.xml")

  def createSipFile = new File(orgContext.sipsDir, s"${dsInfo}__${DATE_FORMAT.format(new Date())}.sip.zip")

  // Match both timestamped Narthex-generated SIPs (`${spec}__YYYY_..sip.zip`) and
  // bare uploaded SIPs (`${spec}.sip.zip`) — direct file drops and old SIP-Creator
  // pushes use the bare form. Missing the bare form caused old uploads to never get
  // cleaned and to show up as duplicates in the sip-app listing.
  def sipFiles = orgContext.sipsDir.listFiles.filter { file =>
    val name = file.getName
    name.endsWith(".sip.zip") && (name.startsWith(s"${dsInfo}__") || name == s"${dsInfo}.sip.zip")
  }.sortBy(_.getName).reverse

  val treeRoot = new NodeRepo(this, treeDir)
  val sourceTreeRoot = new NodeRepo(this, sourceTreeDir)

  // Use org-level sipsDir to match where createSipFile writes
  lazy val sipRepo = new SipRepo(orgContext.sipsDir, dsInfo.spec, orgContext.appConfig.rdfBaseUrl)

  lazy val processedRepo = new ProcessedRepo(processedDir, dsInfo)

  lazy val datasetMappingRepo = new DatasetMappingRepo(rootDir)

  // Phase A4a single-owner: with narthex.mapping.repoBacked the mapper comes
  // from the DatasetMappingRepo + RecDefRepo; the newest SIP zip is only the
  // legacy fallback during rollout. Without the flag, zip-backed as before.
  def sipMapperOpt: Option[SipMapper] =
    if (orgContext.narthexConfig.mappingRepoBacked)
      mapping.RepoSipMapper.build(this).orElse(zipBackedSipMapperOpt)
    else zipBackedSipMapperOpt

  private def zipBackedSipMapperOpt: Option[SipMapper] =
    sipRepo.latestSipOpt.flatMap(_.createSipMapper)

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
        orgContext.orgActor ! dsInfo.createMessage(AdoptSource(raw, orgContext))
    }
  }

  def createSourceRepo(sourceFacts: SourceFacts): SourceRepo = SourceRepo.createClean(sourceDir, sourceFacts, orgContext)

  def sourceRepoOpt: Option[SourceRepo] = {
    if (sourceDir.exists()) {
      if (SourceRepo.sourceFactsFile(sourceDir).exists()) {
        Some(new SourceRepo(sourceDir, orgContext))
      } else {
        logger.warn(
          s"source/ directory exists for ${dsInfo.spec} but ${SourceRepo.SOURCE_FACTS_FILE} is missing — " +
            s"SourceRepo unavailable. Re-run analysis (set record root / unique ID) or upload to repair."
        )
        None
      }
    } else None
  }

  def acceptUpload(fileName: String, setTargetFile: File => File): Option[String] = {

    def sendRefresh() = orgContext.orgActor ! dsInfo.createMessage(Command("refresh"))

    // New raw data invalidates the record root / unique id choice; the
    // lifecycle states themselves are projected from disk (Phase A4b).
    def cleanupWorkflowStates(): Unit = dsInfo.removeLiteralProp(GraphProperties.delimitersSet)

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
        case Success(_) => ()
        case Failure(e) =>
          // A partial converted file must not project as RAW
          logger.warn(s"CSV conversion failed for $dsInfo: ${e.getMessage}")
          deleteQuietly(xmlFile)
      }
      cleanupWorkflowStates()
      dropTree()
      sendRefresh()
      None
    }
    else if (fileName.endsWith(".xml.gz") || fileName.endsWith(".xml")) {
      setTargetFile(createRawFile(fileName))
      cleanupWorkflowStates()
      dsInfo.removeLiteralProp(GraphProperties.harvestType)
      dropTree()
      sendRefresh()
      None
    }
    else if (fileName.endsWith(".sip.zip")) {
      val sipZipFile = setTargetFile(sipRepo.createSipZipFile(fileName))
      sipRepo.latestSipOpt.map { sip =>
        dsInfo.setMetadata(DsMetadata(
          name = sip.name.getOrElse(""),
          description = "",
          aggregator = sip.provider.getOrElse(""),
          owner = sip.dataProvider.getOrElse(""),
          dataProviderURL = sip.dataProviderURL.getOrElse(""),
          language = sip.language.getOrElse(""),
          rights = sip.rights.getOrElse(""),
          dataType = sip.dataType.getOrElse(""),
          edmType = sip.edmType.getOrElse("")
        ))
        dsInfo.setHarvestInfo(
          harvestTypeEnum = sip.harvestType.flatMap(HarvestType.harvestTypeFromString).getOrElse(HarvestType.PMH),
          url = sip.harvestUrl.getOrElse(""),
          dataset = sip.harvestSpec.getOrElse(""),
          recordId = sip.harvestSpec.getOrElse(""),
          prefix = sip.harvestPrefix.getOrElse("")
        )
        // Single-owner ingest (A4a): every SIP upload lands its mapping in
        // the DatasetMappingRepo — previously only the SIP-Creator endpoint
        // did, so web uploads left the repo stale and the next generate-sip
        // overwrote the freshly uploaded mapping with the old repo-current.
        sip.rawMappingXmlOpt.foreach { case (prefix, xml) =>
          datasetMappingRepo.saveFromSipUpload(xml, prefix, Some("Uploaded via web UI (.sip.zip)"))
        }

        // todo: should probably wait for the above future
        sip.sipMappingOpt.map { sipMapping =>
          if (sip.containsSource) {
            createSourceRepo(PocketParser.POCKET_SOURCE_FACTS)
            sip.copySourceToTempFile.map { sourceFile =>
              orgContext.orgActor ! dsInfo.createMessage(AdoptSource(sourceFile, orgContext))
              sendRefresh()
              None
            } getOrElse {
              dropSourceRepo()
              Some(s"No source found in $sipZipFile for $dsInfo")
            }
          }
          else {
            sendRefresh()
            None
          }
        } getOrElse {
          logger.error(s"No mapping found in SIP file $sipZipFile for dataset $dsInfo")
          deleteQuietly(sipZipFile)
          Some(s"No mapping found in $sipZipFile for $dsInfo")
        }
      } getOrElse {
        logger.error(s"Unable to read SIP file ${sipZipFile.getName} for dataset $dsInfo - sipRepo.latestSipOpt returned None")
        deleteQuietly(sipZipFile)
        Some(s"Unable to use ${sipZipFile.getName} for $dsInfo")
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

  // Phase A4b: the drops below only remove artifacts — the projector
  // derives the states from their absence.

  def dropRaw() = {
    dropSourceRepo()
    deleteQuietly(rawDir)
  }

  def dropSourceRepo() = {
    dropProcessedRepo()
    // todo: note that we lose the delimiters this way:
    deleteQuietly(sourceDir)
    sipFiles.foreach(deleteQuietly)
  }

  def dropProcessedRepo() = {
    dropTree()
    deleteQuietly(processedDir)
    // Clear externally-processed marker so SipZipGenerationComplete does not
    // re-stamp PROCESSED for a dataset whose processed/ tree is gone.
    dsInfo.removeLiteralProp(GraphProperties.processedExternally)
  }

  def dropTree() = {
    deleteQuietly(treeDir)
    logger.debug("Dropping analysis tree")
  }

  def dropSourceTree() = {
    deleteQuietly(sourceTreeDir)
    logger.debug("Dropping source analysis tree")
  }

  def dropRecords = {
    dsInfo.dropDatasetRecords
  }

  def dropIndex = {
    dsInfo.dropDatasetIndex
    dsInfo.dropDatasetRecords
  }

  def disableDataSet = {
    dsInfo.setState(DISABLED)
    dsInfo.dropDatasetIndex
    dsInfo.dropDatasetRecords
  }

  def startSipZipGeneration() = orgContext.orgActor ! dsInfo.createMessage(GenerateSipZip)

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

  override def toString = dsInfo.toString


}
