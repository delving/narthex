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
import dataset.DsInfo.DsMetadata
import dataset.DsInfo.DsState._
import dataset.Sip.SipMapper
import dataset.SourceRepo._
import org.OrgContext._
import org.apache.commons.io.FileUtils.deleteQuietly
import org.{OrgActor, OrgContext}
import record.PocketParser
import record.SourceProcessor.{AdoptSource, GenerateSipZip}
import services.FileHandling.clearDir
import services.StringHandling.pathToDirectory

class DatasetContext(val orgContext: OrgContext, val dsInfo: DsInfo) {

  val DATE_FORMAT = new SimpleDateFormat("yyyy_MM_dd_HH_mm")
  val rootDir = new File(orgContext.datasetsDir, dsInfo.spec)

  val rawDir = new File(rootDir, "raw")
  val sipsDir = new File(rootDir, "sips")
  val sourceDir = new File(rootDir, "source")
  val treeDir = new File(rootDir, "tree")
  val processedDir = new File(rootDir, "processed")

  // todo: maybe not put it in raw
  val pocketFile = new File(orgContext.rawDir, s"$dsInfo.xml")

  def createSipFile = new File(orgContext.sipsDir, s"${dsInfo}__${DATE_FORMAT.format(new Date())}.sip.zip")

  def sipFiles = orgContext.sipsDir.listFiles.filter(file => file.getName.startsWith(s"${dsInfo}__")).sortBy(_.getName).reverse

  val treeRoot = new NodeRepo(this, treeDir)

  lazy val sipRepo = new SipRepo(sipsDir, dsInfo.spec, NAVE_DOMAIN)

  lazy val processedRepo = new ProcessedRepo(processedDir, dsInfo)

  def sipMapperOpt: Option[SipMapper] = sipRepo.latestSipOpt.flatMap(_.createSipMapper)

  def mkdirs = {
    rootDir.mkdirs()
    this
  }

  def createRawFile(fileName: String): File = new File(clearDir(rawDir), fileName)

  // todo: recordContainer instead perhaps
  def setRawDelimiters(recordRoot: String, uniqueId: String) = rawFile.map { raw =>
    createSourceRepo(SourceFacts("from-raw", recordRoot, uniqueId, None))
    dropTree()
    dsInfo.removeState(RAW_ANALYZED)
    OrgActor.actor ! dsInfo.createMessage(AdoptSource(raw))
  }

  def createSourceRepo(sourceFacts: SourceFacts): SourceRepo = SourceRepo.createClean(sourceDir, sourceFacts)

  def sourceRepoOpt: Option[SourceRepo] = if (sourceDir.exists()) Some(new SourceRepo(sourceDir)) else None

  def acceptUpload(fileName: String, setTargetFile: File => File): Option[String] = {
    if (fileName.endsWith(".xml.gz") || fileName.endsWith(".xml")) {
      setTargetFile(createRawFile(fileName))
      dsInfo.setState(RAW)
      dsInfo.removeState(RAW_ANALYZED)
      dsInfo.removeState(ANALYZED)
      dropTree()
      None
    }
    else if (fileName.endsWith(".sip.zip")) {
      val sipZipFile = setTargetFile(sipRepo.createSipZipFile(fileName))
      sipRepo.latestSipOpt.map { sip =>
        dsInfo.setMetadata(DsMetadata(
          name = sip.fact("name").getOrElse(""),
          description = "",
          aggregator = sip.fact("provider").getOrElse(""),
          owner = sip.fact("dataProvider").getOrElse(""),
          language = sip.fact("language").getOrElse(""),
          rights = sip.fact("rights").getOrElse("")
        ))
        // todo: should probably wait for the above future
        sip.sipMappingOpt.map { sipMapping =>
          if (sip.containsSource) {
            createSourceRepo(PocketParser.POCKET_SOURCE_FACTS)
            sip.copySourceToTempFile.map { sourceFile =>
              OrgActor.actor ! dsInfo.createMessage(AdoptSource(sourceFile))
              None
            } getOrElse {
              dropSourceRepo()
              Some(s"No source found in $sipZipFile for $dsInfo")
            }
          }
          else {
            None
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

  def dropSourceRepo() = {
    deleteQuietly(rawDir)
    dsInfo.removeState(RAW)
    deleteQuietly(sourceDir)
    dsInfo.removeState(SOURCED)
  }

  def startSipZipGeneration() = OrgActor.actor ! dsInfo.createMessage(GenerateSipZip)

  def dropProcessedRepo() = {
    deleteQuietly(processedDir)
    dsInfo.removeState(PROCESSED)
  }

  def dropTree() = {
    deleteQuietly(treeDir)
    dsInfo.removeState(ANALYZED)
  }

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

  //  def histograms(path: String, size: Int): Option[File] = nodeRepo(path).map { repo =>
  //    val fileList = repo.histogramJson.filter(pair => pair._1 == size)
  //    fileList.headOption.map(_._2)
  //  } getOrElse None
  //
  def histogram(path: String, size: Int): Option[File] = nodeRepo(path).map { repo =>
    val fileList = repo.histogramJson.filter(pair => pair._1 == size)
    fileList.headOption.map(_._2)
  } getOrElse None

  def uriText(path: String): Option[File] = nodeRepo(path).map(_.uriText)

  def uniqueText(path: String): Option[File] = nodeRepo(path).map(_.uniqueText)

  def histogramText(path: String): Option[File] = nodeRepo(path).map(_.histogramText)

  override def toString = dsInfo.toString


}
