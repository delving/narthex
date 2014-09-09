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

package services

import java.io.File

import org.apache.commons.io.FileUtils._
import org.joda.time.DateTime
import org.joda.time.format.ISODateTimeFormat
import play.api.Play.current
import play.api.cache.Cache
import play.api.libs.json.{JsObject, JsValue, Json}
import services.DatasetState._
import services.RepoUtil._

import scala.concurrent.duration._
import scala.io.Source
import scala.language.postfixOps
import scala.util.Random
import scala.xml.NodeSeq

object Repo {
  lazy val repo = new Repo(NarthexConfig.USER_HOME, NarthexConfig.ORG_ID)
}

case class DatasetState(name: String) {
  override def toString = name

  def matches(otherName: String) = name == otherName
}

object DatasetState {
  val DELETED = DatasetState("state-deleted")
  val EMPTY = DatasetState("state-empty")
  val HARVESTING = DatasetState("state-harvesting")
  val READY = DatasetState("state-ready")
  val SPLITTING = DatasetState("state-splitting")
  val ANALYZING = DatasetState("state-analyzing")
  val ANALYZED = DatasetState("state-analyzed")
  val SAVING = DatasetState("state-saving")
  val SAVED = DatasetState("state-saved")
  val PUBLISHED = DatasetState("state-published")
  val ERROR = DatasetState("state-error")

  val ALL_STATES = List(DELETED, EMPTY, HARVESTING, READY, SPLITTING, ANALYZING, ANALYZED, SAVING, SAVED, PUBLISHED, ERROR)

  def fromString(string: String): Option[DatasetState] = {
    ALL_STATES.find(s => s.matches(string))
  }
}

object RepoUtil {
  val SUFFIXES = List(".xml.gz", ".xml")
  val UPLOADED_DIR = "uploaded"
  val ANALYZED_DIR = "analyzed"
  val SIP_ZIP = "sip-zip"

  def tagToDirectory(tag: String) = tag.replace(":", "_").replace("@", "_")

  def acceptableFile(fileName: String, contentType: Option[String]) = {
    // todo: be very careful about files matching a regex, so that they have spec__prefix form
    // todo: something with content-type
    println("content type " + contentType)
    SUFFIXES.filter(suffix => fileName.endsWith(suffix)).nonEmpty
  }

  def readJson(file: File) = Json.parse(readFileToString(file))

  def createJson(file: File, content: JsObject) = writeStringToFile(file, Json.prettyPrint(content), "UTF-8")

  def updateJson(file: File)(xform: JsValue => JsObject) = {
    if (file.exists()) {
      val value = readJson(file)
      val tempFile = new File(file.getParentFile, s"${file.getName}.temp")
      createJson(tempFile, xform(value))
      deleteQuietly(file)
      moveFile(tempFile, file)
    }
    else {
      writeStringToFile(file, Json.prettyPrint(xform(Json.obj())), "UTF-8")
    }
  }

  def getSuffix(fileName: String) = {
    val suffix = SUFFIXES.filter(suf => fileName.endsWith(suf))
    if (suffix.isEmpty) "" else suffix.head
  }

  def stripSuffix(fileName: String) = {
    val suffix = getSuffix(fileName)
    fileName.substring(0, fileName.length - suffix.length)
  }

}

class Repo(userHome: String, val orgId: String) {
  val root = new File(userHome, "NarthexFiles")
  val orgRoot = new File(root, orgId)
  val uploaded = new File(orgRoot, UPLOADED_DIR)
  val analyzed = new File(orgRoot, ANALYZED_DIR)
  val sipZip = new File(orgRoot, SIP_ZIP)
  val repoDb = new RepoDb(orgId)

  orgRoot.mkdirs()

  def uploadedFile(fileName: String) = {
    val suffix = getSuffix(fileName)
    if (suffix.isEmpty) {
      val fileNameDot = s"$fileName."
      val listFiles = uploaded.listFiles()
      val uploadedFiles = if (listFiles == null) List.empty[File] else listFiles.toList
      val matchingFiles = uploadedFiles.filter(file => file.getName.startsWith(fileNameDot))
      if (matchingFiles.isEmpty) {
        new File(uploaded, s"$fileName.zip")
      }
      else {
        matchingFiles.head
      }
    }
    else {
      new File(uploaded, fileName)
    }
  }

  def analyzedDir(fileName: String) = new File(analyzed, stripSuffix(fileName))

  private def listFiles(directory: File): List[File] = {
    if (!directory.exists()) return List.empty
    directory.listFiles.filter(f => f.isFile && SUFFIXES.filter(end => f.getName.endsWith(end)).nonEmpty).toList
  }

  def datasetRepo(fileName: String): DatasetRepo = new DatasetRepo(
    this, stripSuffix(fileName), uploadedFile(fileName), analyzedDir(fileName)
  )

  def datasetRepoOption(fileName: String): Option[DatasetRepo] = {
    val dr = datasetRepo(fileName)
    if (dr.datasetDb.getDatasetInfoOption.isDefined) Some(dr) else None
  }

  // todo: whenever there are enriched datasets, add new spec s"${spec}_enriched" for them

  def getPublishedDatasets: Seq[PublishedDataset] = {
    val FileName = "(.*)__(.*)".r
    BaseX.withSession {
      session =>
        repoDb.listDatasets.flatMap {
          dataset =>
            val fr = datasetRepo(dataset.name)
            val FileName(spec, prefix) = dataset.name
            val state = (dataset.info \ "status" \ "state").text
            val totalRecords = (dataset.info \ "delimit" \ "recordCount").text
            val namespace = (dataset.info \ "namespaces" \ "namespace").find(n => (n \ "prefix").text == prefix)
            val metadataFormat = namespace match {
              case Some(ns) => RepoMetadataFormat(prefix, (ns \ "uri").text)
              case None => RepoMetadataFormat(prefix)
            }
            if (PUBLISHED.matches(state)) {
              Some(PublishedDataset(dataset.name, prefix, "name", "dataProvider", totalRecords.toInt, metadataFormat))
            }
            else {
              None
            }
        }
    }
  }

  def getMetadataFormats: Seq[RepoMetadataFormat] = {
    getPublishedDatasets.sortBy(_.metadataFormat.namespace).map(d => (d.prefix, d.metadataFormat)).toMap.values.toSeq
  }

  def getHarvest(resumptionToken: String): (Option[NodeSeq], Option[String]) = {
    Cache.getAs[Harvest](resumptionToken).map { harvest =>
      val pageSize = NarthexConfig.OAI_PMH_PAGE_SIZE
      val start = 1 + (harvest.page - 1) * pageSize
      val recordRepo = datasetRepo(harvest.repoName).recordRepo
      def records = Some(recordRepo.recordsPmh(harvest.from, harvest.until, start, pageSize, harvest.headersOnly))
      harvest.next.map { next =>
        Cache.set(next.resumptionToken, next, 2 minutes)
        (records, Some(next.resumptionToken))
      } getOrElse {
        (records, None)
      }
    } getOrElse {
      (None, None)
    }
  }

  // === sip-zip

  def sipZipFile(fileName: String) = new File(sipZip, fileName)

  val SipZipName = "sip_(.+)__(\\d+)_(\\d+)_(\\d+)_(\\d+)_(\\d+)__(.*).zip".r
  val FORMATTER = ISODateTimeFormat.dateTime()

  // todo: add hints, to get record count
  def listSipZip: Seq[(File, Map[String, String])] = {
    if (!sipZip.exists()) return Seq.empty
    val fileList = sipZip.listFiles.filter(file => file.isFile && file.getName.endsWith(".zip")).toList
    val ordered = fileList.sortBy {
      f =>
        val n = f.getName
        val parts = n.split("__")
        if (parts.length >= 2) parts(1) else parts(0)
    }
    ordered.reverse.map {
      file =>
        val factsFile = new File(file.getParentFile, s"${file.getName}.facts")
        val lines = Source.fromFile(factsFile).getLines()
        val facts = lines.map {
          line =>
            val equals = line.indexOf("=")
            (line.substring(0, equals).trim, line.substring(equals + 1).trim)
        }.toMap
        val SipZipName(spec, year, month, day, hour, minute, uploadedBy) = file.getName
        val dateTime = new DateTime(year.toInt, month.toInt, day.toInt, hour.toInt, minute.toInt)
        val factsPlus = facts + ("uploadedBy" -> uploadedBy) + ("uploadedOn" -> FORMATTER.print(dateTime))
        (file, factsPlus)
    }
  }
}

case class RepoMetadataFormat(prefix: String, namespace: String = "unknown")

case class PublishedDataset
(
  spec: String, prefix: String, name: String, dataProvider: String, totalRecords: Int,
  metadataFormat: RepoMetadataFormat)


case class Harvest
(
  repoName: String,
  headersOnly: Boolean,
  from: Option[DateTime],
  until: Option[DateTime],
  totalPages: Int,
  token: String = Random.alphanumeric.take(10).mkString(""),
  page: Int = 1) {

  def resumptionToken = s"$token-$totalPages-$page"

  def next = if (page >= totalPages) None else Some(this.copy(page = page + 1))
}

