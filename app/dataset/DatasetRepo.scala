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
import dataset.DatasetActor._
import dataset.DsInfo.{DsMetadata, DsState}
import dataset.Sip.SipMapper
import dataset.SourceRepo._
import harvest.Harvesting.HarvestType._
import harvest.Harvesting._
import mapping.{CategoryDb, TermDb}
import org.apache.commons.io.FileUtils.deleteQuietly
import org.{OrgActor, OrgRepo}
import play.Logger
import record.SourceProcessor.{AdoptSource, GenerateSipZip}
import services.FileHandling.clearDir
import services.NarthexConfig.NAVE_DOMAIN
import services.Temporal._

class DatasetRepo(val orgRepo: OrgRepo, val dsInfo: DsInfo) {

  val DATE_FORMAT = new SimpleDateFormat("yyyy_MM_dd_HH_mm")
  val rootDir = new File(orgRepo.datasetsDir, dsInfo.spec)
  val dbBaseName = s"narthex_${orgRepo.orgId}___$dsInfo"

  val rawDir = new File(rootDir, "raw")
  val sipsDir = new File(rootDir, "sips")
  val sourceDir = new File(rootDir, "source")
  val treeDir = new File(rootDir, "tree")
  val processedDir = new File(rootDir, "processed")

  // todo: maybe not put it in raw
  val pocketFile = new File(orgRepo.rawDir, s"$dsInfo.xml")

  def createSipFile = new File(orgRepo.sipsDir, s"${dsInfo}__${DATE_FORMAT.format(new Date())}.sip.zip")

  def sipFiles = orgRepo.sipsDir.listFiles.filter(file => file.getName.startsWith(s"${dsInfo}__")).sortBy(_.getName).reverse

  val treeRoot = new NodeRepo(this, treeDir)

  lazy val termDb = new TermDb(dbBaseName)
  lazy val categoryDb = new CategoryDb(dbBaseName)
  lazy val sipRepo = new SipRepo(sipsDir, dsInfo.spec, NAVE_DOMAIN)

  // todo: this has to come from a DatasetInfo thing
  lazy val processedRepo = new ProcessedRepo(processedDir, dsInfo.spec)

  def sipMapperOpt: Option[SipMapper] = sipRepo.latestSipOpt.flatMap(_.createSipMapper)

  def mkdirs = {
    rootDir.mkdirs()
    this
  }

  def createRawFile(fileName: String): File = new File(clearDir(rawDir), fileName)

  def setRawDelimiters(recordRoot: String, uniqueId: String) = rawFile.map { raw =>
    createSourceRepo(SourceFacts("from-raw", recordRoot, uniqueId, None))
    dropTree()
    OrgActor.actor ! dsInfo.createMessage(AdoptSource(raw))
  }

  def createSourceRepo(sourceFacts: SourceFacts): SourceRepo = SourceRepo.createClean(sourceDir, sourceFacts)

  def sourceRepoOpt: Option[SourceRepo] = if (sourceDir.exists()) Some(SourceRepo(sourceDir)) else None

  def acceptUpload(fileName: String, setTargetFile: File => File): Option[String] = {
    if (fileName.endsWith(".xml.gz") || fileName.endsWith(".xml")) {
      setTargetFile(createRawFile(fileName))
      dsInfo.setState(DsState.RAW)
      startAnalysis()
      None
    }
    else if (fileName.endsWith(".sip.zip")) {
      val sipZipFile = setTargetFile(sipRepo.createSipZipFile(fileName))
      sipRepo.latestSipOpt.map { sip =>
        dsInfo.setMetadata(DsMetadata(
          name = sip.fact("name").getOrElse(""),
          description = "",
          owner = sip.fact("dataProvider").getOrElse(""),
          language = sip.fact("language").getOrElse(""),
          rights = sip.fact("rights").getOrElse("")
        ))
        dsInfo.setState(DsState.PROCESSABLE)
        sip.sipMappingOpt.map { sipMapping =>
          // must add RDF since the mapping output uses it
          //          val namespaces = sipMapping.namespaces + (RDF_PREFIX -> RDF_URI)
          //          db.setNamespaceMap(namespaces)
          sip.harvestUrl.map { harvestUrl =>
            // the harvest information is in the Sip, but no source
            val harvestType = if (sip.sipMappingOpt.exists(_.extendWithRecord)) PMH_REC else PMH
            firstHarvest(harvestType, harvestUrl, sip.harvestSpec.getOrElse(""), sip.harvestPrefix.getOrElse(""))
            None
          } getOrElse {
            // there is no harvest information so there may be source
            if (sip.pockets.isDefined) None
            else {
              // if it's not pockets, there should be source, otherwise we don't expect it
              createSourceRepo(DELVING_SIP_SOURCE)
              sip.copySourceToTempFile.map { sourceFile =>
                OrgActor.actor ! dsInfo.createMessage(AdoptSource(sourceFile))
                None
              } getOrElse {
                dropSourceRepo()
                Some(s"No source found in $sipZipFile for $dsInfo")
              }
            }
          }
        } getOrElse {
          deleteQuietly(sipZipFile)
          Some(s"No mapping found in $sipZipFile for $dsInfo")
        }
      } getOrElse {
        deleteQuietly(sipZipFile)
        Some(s"Unable to use $sipZipFile.getName for $dsInfo")
      }
    }
    else {
      Some(s"Unrecognized file suffix: $fileName")
    }
  }

  def rawFile: Option[File] = if (rawDir.exists()) rawDir.listFiles.headOption else None

  def singleHarvestZip: Option[File] = {
    val allZip = sourceDir.listFiles.filter(_.getName.endsWith("zip"))
    if (allZip.size > 1) throw new RuntimeException(s"Multiple zip files where one was expected: $allZip")
    allZip.headOption
  }

  def firstHarvest(harvestType: HarvestType, url: String, dataset: String, prefix: String): Unit = {
    createSourceRepo(SourceFacts(harvestType))
    dsInfo.setHarvestInfo(harvestType, url, dataset, prefix)
    dsInfo.setHarvestCron(dsInfo.harvestCron)
    OrgActor.actor ! dsInfo.createMessage(StartHarvest(None, justDate = true))
  }

  def nextHarvest() = {
    val kickoffOpt = dsInfo.getLiteralProp(DsInfo.stateSource).map { sourceTime =>
      val harvestCron = dsInfo.harvestCron
      if (harvestCron.timeToWork) {
        val nextHarvestCron = harvestCron.next
        // if the next is also to take place immediately, force the harvest cron to now
        dsInfo.setHarvestCron(if (nextHarvestCron.timeToWork) harvestCron.now else nextHarvestCron)
        val justDate = harvestCron.unit == DelayUnit.WEEKS
        Some(StartHarvest(Some(harvestCron.previous), justDate))
      }
      else {
        Logger.info(s"No re-harvest of $dsInfo with cron $harvestCron because it's not time $harvestCron")
        None
      }
    }
    kickoffOpt.map { kickoff =>
      OrgActor.actor ! dsInfo.createMessage(kickoff)
    }
  }

  def dropSourceRepo() = {
    deleteQuietly(rawDir)
    dsInfo.removeState(DsState.RAW)
    deleteQuietly(sourceDir)
    dsInfo.removeState(DsState.SOURCED)
  }

  def startSipZipGeneration() = OrgActor.actor ! dsInfo.createMessage(GenerateSipZip)

  def startProcessing() = OrgActor.actor ! dsInfo.createMessage(StartProcessing(None))

  def dropProcessedRepo() = {
    deleteQuietly(processedDir)
    dsInfo.removeState(DsState.PROCESSED)
  }

  def startAnalysis() = {
    dropTree()
    // todo: tell it what to analyze, either raw or processed
    OrgActor.actor ! dsInfo.createMessage(StartAnalysis)
  }

  def dropTree() = {
    deleteQuietly(treeDir)
    dsInfo.removeState(DsState.ANALYZED)
  }

  def startCategoryCounts() = OrgActor.actor ! dsInfo.createMessage(StartCategoryCounting)

  // ==================================================

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

  //  def histograms(path: String, size: Int): Option[File] = nodeRepo(path).map { repo =>
  //    val fileList = repo.histogramJson.filter(pair => pair._1 == size)
  //    fileList.headOption.map(_._2)
  //  } getOrElse None
  //
  def histogram(path: String, size: Int): Option[File] = nodeRepo(path).map { repo =>
    val fileList = repo.histogramJson.filter(pair => pair._1 == size)
    fileList.headOption.map(_._2)
  } getOrElse None

  def indexText(path: String): Option[File] = nodeRepo(path).map(_.indexText)

  def uniqueText(path: String): Option[File] = nodeRepo(path).map(_.uniqueText)

  def histogramText(path: String): Option[File] = nodeRepo(path).map(_.histogramText)

  override def toString = dsInfo.toString


}
