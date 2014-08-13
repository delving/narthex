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

import java.io.{BufferedReader, File, FileReader}
import java.util.UUID

import actors._
import org.apache.commons.io.FileUtils._
import org.joda.time.DateTime
import org.joda.time.format.ISODateTimeFormat
import play.api.Play.current
import play.api.cache.Cache
import play.api.libs.concurrent.Akka
import play.api.libs.json.{JsArray, JsObject, JsValue, Json}
import services.Repo.State._
import services.Repo._

import scala.collection.mutable
import scala.concurrent.duration._
import scala.io.Source
import scala.language.postfixOps
import scala.util.Random
import scala.xml.NodeSeq

object Repo {
  val SUFFIXES = List(".xml.gz", ".xml")
  val UPLOADED_DIR = "uploaded"
  val ANALYZED_DIR = "analyzed"
  val SIP_ZIP = "sip-zip"
  val USER_HOME = new File(System.getProperty("user.home"))
  val NARTHEX_FILES = new File(USER_HOME, "NarthexFiles")

  object State {
    val FRESH = "0:fresh"
    val SPLITTING = "1:splitting"
    val ANALYZING = "2:analyzing"
    val ANALYZED = "3:analyzed"
    val SAVING = "4:saving"
    val SAVED = "5:saved"
    val PUBLISHED = "6:published"
  }

  lazy val repo = new Repo(NARTHEX_FILES, NarthexConfig.ORG_ID)

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

class Repo(root: File, val orgId: String) {

  val orgRoot = new File(root, orgId)
  val uploaded = new File(orgRoot, UPLOADED_DIR)
  val analyzed = new File(orgRoot, ANALYZED_DIR)
  val sipZip = new File(orgRoot, SIP_ZIP)

  def create(password: String) = {
    orgRoot.mkdirs()
  }

  def uploadedFile(fileName: String) = {
    val suffix = getSuffix(fileName)
    if (suffix.isEmpty) {
      val fileNameDot = s"$fileName."
      val matchingFiles = uploaded.listFiles().filter(file => file.getName.startsWith(fileNameDot))
      if (matchingFiles.isEmpty) {
        new File(uploaded, "nonexistent")
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

  def listUploadedFiles = listFiles(uploaded)

  def listFileRepos = listUploadedFiles.map(file => stripSuffix(file.getName))

  def fileRepo(fileName: String): FileRepo = {
    new FileRepo(this, stripSuffix(fileName), uploadedFile(fileName), analyzedDir(fileName))
  }

  def fileRepoOption(fileName: String): Option[FileRepo] = {
    if (analyzedDir(fileName).exists()) Some(fileRepo(fileName)) else None
  }

  def scanForAnalysisWork() = {
    val files = listUploadedFiles.filter(file => !analyzedDir(file.getName).exists())
    val dirs = files.map(file => analyzedDir(file.getName))
    val pairs = files.zip(dirs)
    val fileRepos = pairs.map(pair => new FileRepo(this, pair._2.getName, pair._1, pair._2).mkdirs)
    fileRepos.foreach(_.startAnalysis())
    files
  }

  private def listFiles(directory: File): List[File] = {
    if (!directory.exists()) return List.empty
    directory.listFiles.filter(f => f.isFile && SUFFIXES.filter(end => f.getName.endsWith(end)).nonEmpty).toList
  }

  // todo: whenever there are enriched datasets, add new spec s"${spec}_enriched" for them

  def getDataSets: Seq[RepoDataSet] = {
    val FileName = "(.*)__(.*)".r
    BaseX.withSession {
      session =>
        val ENDING = ".xml.gz"
        val properSets = listFileRepos.filter(_.contains("__"))
        properSets.flatMap {
          name =>
            val fr = fileRepo(name)
            val FileName(spec, prefix) = name
            val dataset = fr.datasetDb.getDatasetInfo
            val state = (dataset \ "status" \ "state").text
            val totalRecords = (dataset \ "delimit" \ "recordCount").text
            val namespace = (dataset \ "namespaces" \ "namespace").find(n => (n \ "prefix").text == prefix)
            val metadataFormat = namespace match {
              case Some(ns) => RepoMetadataFormat(prefix, (ns \ "uri").text)
              case None => RepoMetadataFormat(prefix)
            }
            if (state == PUBLISHED) {
              Some(RepoDataSet(name, prefix, "name", "dataProvider", totalRecords.toInt, metadataFormat))
            }
            else
              None
        }
    }
  }

  def getMetadataFormats: Seq[RepoMetadataFormat] = {
    getDataSets.sortBy(_.metadataFormat.namespace).map(d => (d.prefix, d.metadataFormat)).toMap.values.toSeq
  }

  def getHarvest(resumptionToken: String): (Option[NodeSeq], Option[String]) = {
    Cache.getAs[Harvest](resumptionToken).map { harvest =>
      val pageSize = NarthexConfig.OAI_PMH_PAGE_SIZE
      val start = 1 + (harvest.page - 1) * pageSize
      val recordRepo = fileRepo(harvest.repoName).recordRepo
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

class FileRepo(val orgRepo: Repo, val name: String, val sourceFile: File, val dir: File) {

  val root = new NodeRepo(this, dir)
  val dbName = s"narthex_${orgRepo.orgId}___$name"
  lazy val datasetDb = new DatasetDb(dbName)
  lazy val termRepo = new TermDb(dbName)
  lazy val recordRepo = new RecordDb(this, dbName)

  def mkdirs = {
    dir.mkdirs()
    this
  }

  def startAnalysis() = {
    datasetDb.setStatus(SPLITTING, percent = 1)
    val analyzer = Akka.system.actorOf(Analyzer.props(this), sourceFile.getName)
    analyzer ! Analyzer.Analyze(sourceFile)
  }

  def index = new File(dir, "index.json")

  def nodeRepo(path: String): Option[NodeRepo] = {
    val nodeDir = path.split('/').toList.foldLeft(dir)((file, tag) => new File(file, tagToDirectory(tag)))
    if (nodeDir.exists()) Some(new NodeRepo(this, nodeDir)) else None
  }

  def status(path: String): Option[File] = {
    nodeRepo(path) match {
      case None => None
      case Some(nodeDirectory) => Some(nodeDirectory.status)
    }
  }

  def sample(path: String, size: Int): Option[File] = {
    nodeRepo(path) match {
      case None => None
      case Some(nodeRepo) =>
        val fileList = nodeRepo.sampleJson.filter(pair => pair._1 == size)
        if (fileList.isEmpty) None else Some(fileList.head._2)
    }
  }

  def histogram(path: String, size: Int): Option[File] = {
    nodeRepo(path) match {
      case None => None
      case Some(nodeRepo) =>
        val fileList = nodeRepo.histogramJson.filter(pair => pair._1 == size)
        if (fileList.isEmpty) None else Some(fileList.head._2)
    }
  }

  def indexText(path: String): Option[File] = {
    nodeRepo(path) match {
      case None => None
      case Some(nodeRepo) => Some(nodeRepo.indexText)
    }
  }

  def uniqueText(path: String): Option[File] = {
    nodeRepo(path) match {
      case None => None
      case Some(nodeRepo) => Some(nodeRepo.uniqueText)
    }
  }

  def histogramText(path: String): Option[File] = {
    nodeRepo(path) match {
      case None => None
      case Some(nodeRepo) => Some(nodeRepo.histogramText)
    }
  }

  def delete() = {
    datasetDb.setStatus(FRESH)
    recordRepo.dropDatabase()
    deleteQuietly(sourceFile)
    deleteDirectory(dir)
  }
}

case class RepoMetadataFormat(prefix: String, namespace: String = "unknown")

case class RepoDataSet
(
  spec: String, prefix: String, name: String, dataProvider: String, totalRecords: Int,
  metadataFormat: RepoMetadataFormat)

object NodeRepo {
  def apply(parent: FileRepo, parentDir: File, tag: String) = {
    val dir = if (tag == null) parentDir else new File(parentDir, tagToDirectory(tag))
    dir.mkdirs()
    new NodeRepo(parent, dir)
  }
}

class NodeRepo(val parent: FileRepo, val dir: File) {

  def child(childTag: String) = NodeRepo(parent, dir, childTag)

  def f(name: String) = new File(dir, name)

  def status = f("status.json")

  def setStatus(content: JsObject) = createJson(status, content)

  def values = f("values.txt")

  def tempSort = f(s"sorting-${UUID.randomUUID()}.txt")

  def sorted = f("sorted.txt")

  def counted = f("counted.txt")

  val sizeFactor = 5 // relates to the lists below

  def histogramJson = List(100, 500, 2500, 12500).map(size => (size, f(s"histogram-$size.json")))

  def sampleJson = List(100, 500, 2500).map(size => (size, f(s"sample-$size.json")))

  def indexText = f("index.txt")

  def uniqueText = f("unique.txt")

  def histogramText = f("histogram.txt")

  def writeHistograms(uniqueCount: Int) = {

    val LINE = """^ *(\d*) (.*)$""".r
    val input = new BufferedReader(new FileReader(histogramText))

    def lineOption = {
      val string = input.readLine()
      if (string != null) Some(string) else None
    }

    def createFile(maximum: Int, entries: mutable.ArrayBuffer[JsArray], histogramFile: File) = {
      createJson(histogramFile, Json.obj(
        "uniqueCount" -> uniqueCount,
        "entries" -> entries.size,
        "maximum" -> maximum,
        "complete" -> (entries.size == uniqueCount),
        "histogram" -> entries
      ))
    }

    var activeCounters = histogramJson.map(pair => (pair._1, new mutable.ArrayBuffer[JsArray], pair._2))
    activeCounters = activeCounters.filter(pair => pair._1 == activeCounters.head._1 || uniqueCount > pair._1 / sizeFactor)
    val counters = activeCounters
    var line = lineOption
    var count = 1
    while (line.isDefined && activeCounters.nonEmpty) {
      val lineMatch = LINE.findFirstMatchIn(line.get)
      activeCounters = activeCounters.filter {
        triple =>
          lineMatch.map(groups => triple._2 += Json.arr(groups.group(1), groups.group(2)))
          val keep = count < triple._1
          if (!keep) createFile(triple._1, triple._2, triple._3) // side effect
          keep
      }
      line = lineOption
      count += 1
    }
    activeCounters.foreach(triple => createFile(triple._1, triple._2, triple._3))
    counters.map(triple => triple._1)

  }
}

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

