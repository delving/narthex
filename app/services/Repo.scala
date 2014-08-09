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
import org.basex.core.BaseXException
import org.basex.server.ClientSession
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
import scala.util.Random
import scala.xml.{Elem, NodeSeq, XML}

object Repo {
  val SUFFIXES = List(".xml.gz", ".xml")
  val UPLOADED_DIR = "uploaded"
  val ANALYZED_DIR = "analyzed"
  val SIP_ZIP = "sip-zip"
  val userHome = new File(System.getProperty("user.home"))
  val root = new File(userHome, "NarthexFiles")
  val FORMATTER = ISODateTimeFormat.dateTime()

  object State {
    val SPLITTING = "1:splitting"
    val ANALYZING = "2:analyzing"
    val ANALYZED = "3:analyzed"
    val SAVING = "4:saving"
    val SAVED = "5:saved"
    val PUBLISHED = "6:published"
  }

  lazy val baseX: BaseX = new BaseX("localhost", 1984, "admin", "admin")

  lazy val repo = new Repo(root, NarthexConfig.ORG_ID)

  def tagToDirectory(tag: String) = tag.replace(":", "_").replace("@", "_")

  def acceptableFile(fileName: String, contentType: Option[String]) = {
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

  def quote(value: String) = {
    value match {
      case "" => "''"
      case string =>
        "'" + string.replace("'", "\'\'") + "'"
    }
  }

  case class TermMapping(source: String, target: String, vocabulary: String, prefLabel: String)

  def toXSDString(dateTime: DateTime) = FORMATTER.print(dateTime)

  def fromXSDDateTime(dateString: String) = FORMATTER.parseDateTime(dateString)


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

  def sipZipFile(fileName: String) = new File(sipZip, fileName)

  def listSipZip = {
    if (sipZip.exists()) {
      val fileList = sipZip.listFiles.filter(file => file.isFile && file.getName.endsWith(".zip")).toList
      val ordered = fileList.sortBy {
        f =>
          val n = f.getName
          val parts = n.split("__")
          if (parts.length == 2) parts(1) else parts(0)
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
          (file, facts)
      }
    }
    else {
      Seq.empty
    }

  }

  def analyzedDir(fileName: String) = new File(analyzed, stripSuffix(fileName))

  def listUploadedFiles = listFiles(uploaded)

  def listFileRepos = listUploadedFiles.map(file => stripSuffix(file.getName))

  def uploadedOnly() = listUploadedFiles.filter(file => !analyzedDir(file.getName).exists())

  def scanForAnalysisWork() = {
    val files = uploadedOnly()
    val dirs = files.map(file => analyzedDir(file.getName))
    val pairs = files.zip(dirs)
    val fileAnalysisDirs = pairs.map(pair => new FileRepo(this, pair._2.getName, pair._1, pair._2).mkdirs)
    val jobs = files.zip(fileAnalysisDirs)
    jobs.foreach {
      job =>
        val uploadedFile = job._1
        val fileRepo = job._2
        fileRepo.setStatus(SPLITTING, percent = 1)
        val analyzer = Akka.system.actorOf(Analyzer.props(fileRepo), uploadedFile.getName)
        analyzer ! Analyzer.Analyze(job._1)
    }
    files
  }

  def fileRepo(fileName: String): FileRepo = {
    new FileRepo(this, stripSuffix(fileName), uploadedFile(fileName), analyzedDir(fileName))
  }

  def fileRepoOption(fileName: String): Option[FileRepo] = {
    if (analyzedDir(fileName).exists()) Some(fileRepo(fileName)) else None
  }

  private def listFiles(directory: File): List[File] = {
    if (directory.exists()) {
      directory.listFiles.filter(file =>
        file.isFile && SUFFIXES.filter(suffix => file.getName.endsWith(suffix)).nonEmpty
      ).toList
    }
    else {
      List.empty
    }
  }

  def getDataSets: Seq[RepoDataSet] = {
    val FileName = "(.*)__(.*)".r
    baseX.withSession {
      session =>
        val ENDING = ".xml.gz"
        val properSets = listFileRepos.filter(_.contains("__"))
        properSets.flatMap {
          name =>
            val fr = fileRepo(name)
            val FileName(spec, prefix) = name
            val dataset = fr.getDatasetInfo
            val state = (dataset \ "status" \ "state").text
            val totalRecords = (dataset \ "delimit" \ "recordCount").text
            val shouldBe_PUBLISHED = SAVED
            if (state == shouldBe_PUBLISHED) Some(RepoDataSet(spec, prefix, "name", "dataProvider", totalRecords.toInt)) else None
        }
    }
  }

  def getHarvest(resumptionToken: String): (Option[NodeSeq], Option[String]) = {
    Cache.getAs[Harvest](resumptionToken).map { harvest =>
      val pageSize = NarthexConfig.OAI_PMH_PAGE_SIZE
      val start = 1 + (harvest.page - 1) * pageSize
      val fr = fileRepo(harvest.repoName)
      def records = Some(fr.recordsPmh(harvest.from, harvest.until, start, pageSize, harvest.headersOnly))
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


}

object FileRepo {

}

class FileRepo(val orgRepo: Repo, val name: String, val sourceFile: File, val dir: File) {

  val dbName = s"narthex_${orgRepo.orgId}___$name"
  val recordDb = s"${dbName}_records"
  val termDb = s"${dbName}_terminology"
  val root = new NodeRepo(this, dir)

  def mkdirs = {
    dir.mkdirs()
    this
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

  def withDatasetSession[T](block: ClientSession => T): T = {
    try {
      baseX.withDbSession[T](dbName)(block)
    }
    catch {
      case be: BaseXException =>
        if (be.getMessage.contains("not found")) {
          baseX.createDatabase(dbName,
            """
              | <narthex-dataset>
              |   <status/>
              |   <delimit/>
              |   <namespaces/>
              | </narthex-dataset>
              | """.stripMargin
          )
          baseX.withDbSession[T](dbName)(block)
        }
        else {
          throw be
        }
    }
  }

  def getDatasetInfo = {
    withDatasetSession {
      session =>
        // try the following without doc() sometime, since the db is open
        val statusQuery = s"doc('$dbName/$dbName.xml')/narthex-dataset"
        val answer = session.query(statusQuery).execute()
        XML.loadString(answer)
    }
  }

  def setStatus(state: String, percent: Int = 0, workers: Int = 0) = {
    withDatasetSession {
      session =>
        val update =
          s"""
             |
             | let $$statusBlock := doc('$dbName/$dbName.xml')/narthex-dataset/status
             | let $$replacement :=
             |   <status>
             |     <state>$state</state>
             |     <percent>$percent</percent>
             |     <workers>$workers</workers>
             |   </status>
             | return replace node $$statusBlock with $$replacement
             |
           """.stripMargin.trim
        //        println("updating:\n" + update)
        session.query(update).execute()
    }
  }

  def setRecordDelimiter(recordRoot: String = "", uniqueId: String = "", recordCount: Int = 0) = {
    withDatasetSession {
      session =>
        val update =
          s"""
             |
             | let $$delimitBlock := doc('$dbName/$dbName.xml')/narthex-dataset/delimit
             | let $$replacement :=
             |   <delimit>
             |     <recordRoot>$recordRoot</recordRoot>
             |     <uniqueId>$uniqueId</uniqueId>
             |     <recordCount>$recordCount</recordCount>
             |   </delimit>
             | return replace node $$delimitBlock with $$replacement
             |
           """.stripMargin.trim
        //        println("updating:\n" + update)
        session.query(update).execute()
    }
  }

  def setNamespaceMap(namespaceMap: Map[String, String]) = {
    withDatasetSession {
      session =>
        val namespaces = namespaceMap.map {
          entry =>
            s"    <namespace><prefix>${entry._1}</prefix><uri>${entry._2}</uri></namespace>"
        }.mkString("\n")
        val update =
          s"""
             |
             | let $$namespacesBlock := doc('$dbName/$dbName.xml')/narthex-dataset/namespaces
             | let $$replacement :=
             |   <namespaces>
             |$namespaces
             |   </namespaces>
             | return replace node $$namespacesBlock with $$replacement
             |
           """.stripMargin.trim
        println("updating:\n" + update)
        session.query(update).execute()
    }
  }

  def saveRecords() = {
    val dataset = getDatasetInfo
    withDatasetSession {
      session =>
        val delim = dataset \ "delimit"
        val recordRoot = (delim \ "recordRoot").text
        val uniqueId = (delim \ "uniqueId").text
        val recordCountText = (delim \ "recordCount").text
        val recordCount = if (recordCountText.isEmpty) 0 else recordCountText.toInt
        // set status now so it's done before the actor starts
        setStatus(SAVING, percent = 1)
        val saver = Akka.system.actorOf(Saver.props(this), name)
        saver ! SaveRecords(recordRoot, uniqueId, recordCount, name)
    }
  }

  def withNewRecordDatabase[T](block: ClientSession => T) = {
    baseX.createDatabase(recordDb) // overwrites
    baseX.withDbSession(recordDb)(block)
  }

  def withRecordDatabase[T](block: ClientSession => T) = {
    baseX.withDbSession(recordDb)(block)
  }

  private def namespaceDeclarations(dataset: Elem) = {
    val namespaces = dataset \ "namespaces" \ "namespace"
    namespaces.map {
      node =>
        s"""declare namespace ${(node \ "prefix").text} = "${(node \ "uri").text}";"""
    }.mkString("\n")
  }

  def recordsWithValue(path: String, value: String, start: Int = 1, max: Int = 10): String = {
    // fetching the recordRoot here because we need to chop the path string.  can that be avoided?
    val dataset = getDatasetInfo
    val delim = dataset \ "delimit"
    val recordRoot = (delim \ "recordRoot").text
    val prefix = recordRoot.substring(0, recordRoot.lastIndexOf("/"))
    if (!path.startsWith(prefix)) throw new RuntimeException(s"$path must start with $prefix!")
    val queryPathField = path.substring(prefix.length)
    val field = queryPathField.substring(queryPathField.lastIndexOf("/") + 1)
    val queryPath = queryPathField.substring(0, queryPathField.lastIndexOf("/"))
    nodeRepo(path) match {
      case None => "<records/>"
      case Some(nodeDirectory) =>
        withRecordDatabase {
          session =>
            val queryForRecords = s"""
              |
              | ${namespaceDeclarations(dataset)}
              | let $$recordsWithValue := collection('$recordDb')[/narthex$queryPath/$field=${quote(value)}]
              | let $$selected := subsequence($$recordsWithValue, $start, $max)
              | return <records>{ for $$rec in $$selected return $$rec/narthex/* }</records>
              |
              """.stripMargin.trim
            println("asking:\n" + queryForRecords)
            session.query(queryForRecords).execute()
        }
    }
  }

  def record(identifier: String): Elem = {
    withRecordDatabase {
      session =>
        val dataset = getDatasetInfo
        val queryForRecord = s"""
              |
              | ${namespaceDeclarations(dataset)}
              | let $$recordWithId := collection('$recordDb')[/narthex/@id=${quote(identifier)}]
              | return <record>{
              |   $$recordWithId
              | }</record>
              |
              """.stripMargin.trim
        println("asking:\n" + queryForRecord)
        XML.loadString(session.query(queryForRecord).execute())
    }
  }

  def getIds(since: String): String = withRecordDatabase {
    session =>
      val q = s"""
       |
       |let $$records := collection('$recordDb')/narthex
       |return
       |    <ids>{
       |       for $$narthex in $$records
       |       where $$narthex/@mod >= ${quote(since)}
       |       order by $$narthex/@mod descending
       |       return
       |          <id>{$$narthex/@mod}{string($$narthex/@id)}</id>
       |    }</ids>
       |
       |""".stripMargin.trim
      session.query(q).execute()
  }

  // terminology

  def withTermSession[T](block: ClientSession => T): T = {
    try {
      baseX.withDbSession[T](termDb)(block)
    }
    catch {
      case be: BaseXException =>
        if (be.getMessage.contains("not found")) {
          baseX.createDatabase(termDb, "<term-mappings/>")
          baseX.withDbSession[T](termDb)(block)
        }
        else {
          throw be
        }
    }
  }

  def setMapping(mapping: TermMapping) = withTermSession {
    session =>
      val upsert = s"""
      |
      | let $$freshMapping :=
      |   <term-mapping>
      |     <source>${mapping.source}</source>
      |     <target>${mapping.target}</target>
      |     <vocabulary>${mapping.vocabulary}</vocabulary>
      |     <prefLabel>${mapping.prefLabel}</prefLabel>
      |   </term-mapping>
      |
      | let $$termMappings := doc('$termDb/$termDb.xml')/term-mappings
      |
      | let $$termMapping := $$termMappings/term-mapping[source=${quote(mapping.source)}]
      |
      | return
      |   if (exists($$termMapping))
      |   then replace node $$termMapping with $$freshMapping
      |   else insert node $$freshMapping into $$termMappings
      |
      """.stripMargin

      session.query(upsert).execute()
  }

  def getMapping(source: String): String = withTermSession[String] {
    session =>
      val q = s"""
       |
       |let $$mapping := doc('$termDb/$termDb.xml')/term-mappings/term-mapping[source=${quote(source)}]
       |return $$mapping/target/text()
       |
       |""".stripMargin
      session.query(q).execute()
  }

  def getMappings: Seq[TermMapping] = withTermSession[Seq[TermMapping]] {
    session =>
      val mappings = session.query(s"doc('$termDb/$termDb.xml')/term-mappings").execute()
      val xml = XML.loadString(mappings)
      (xml \ "term-mapping").map { node =>
        TermMapping(
          (node \ "source").text,
          (node \ "target").text,
          (node \ "vocabulary").text,
          (node \ "prefLabel").text
        )
      }
  }

  def delete() = {
    setStatus(SPLITTING)
    setRecordDelimiter()
    baseX.dropDatabase(recordDb)
    deleteQuietly(sourceFile)
    deleteDirectory(dir)
  }

  def dateSelector(from: Option[DateTime], until: Option[DateTime]) = (from, until) match {
    case (Some(fromDate), Some(untilDate)) =>
      s"[@mod >= '${toXSDString(fromDate)}' and @mod < '${toXSDString(untilDate)}']"
    case (Some(fromDate), None) =>
      s"[@mod >= '${toXSDString(fromDate)}']"
    case (None, Some(untilDate)) =>
      s"[@mod < '${toXSDString(untilDate)}']"
    case (None, None) =>
      ""
  }

  def createHarvest(headersOnly: Boolean, from: Option[DateTime], until: Option[DateTime]): Option[String] = {
    val now = new DateTime()
    println(s"createHarvest: $name, $from, $until")
    val dataset = getDatasetInfo
    val countString = withRecordDatabase {
      session =>
        val queryForRecords = s"count(collection('$recordDb')/narthex${dateSelector(from, until)})"
        println("asking:\n" + queryForRecords)
        session.query(queryForRecords).execute()
    }
    val count = countString.toInt
    val pageSize = NarthexConfig.OAI_PMH_PAGE_SIZE
    val pages = if (count % pageSize == 0) count / pageSize else count / pageSize + 1
    val harvest = Harvest(name, headersOnly, from, until, pages)
    Cache.set(harvest.resumptionToken, harvest, 2 minutes)
    Some(harvest.resumptionToken)
  }

  def recordPmh(identifier: String): Elem = {
    withRecordDatabase {
      session =>
        val dataset = getDatasetInfo
        val queryForRecord = s"""
              |
              | ${namespaceDeclarations(dataset)}
              | let $$rec := collection('$recordDb')[/narthex/@id=${quote(identifier)}]
              | return
              |   <record>
              |     <header>
              |       <identifier>{$$rec/narthex/@id}</identifier>
              |       <datestamp>{$$rec/narthex/@mod}</datestamp>
              |       <setSpec>to-do!</setSpec>
              |     </header>
              |     <metadata>
              |      {$$rec/narthex/*}
              |     </metadata>
              |   </record>
              |
              """.stripMargin.trim
        XML.loadString(session.query(queryForRecord).execute())
    }
  }

  def recordsPmh(from: Option[DateTime], until: Option[DateTime], start: Int, pageSize: Int, headersOnly: Boolean = true): NodeSeq = {
    withRecordDatabase {
      session =>
        val metadata = if (headersOnly) "" else "<metadata>{$narthex/*}</metadata>"
        val dataset = getDatasetInfo
        val query = s"""
              |
              | ${namespaceDeclarations(dataset)}
              |
              | let $$selection := collection('$recordDb')/narthex${dateSelector(from, until)}
              |
              | let $$records :=
              |   for $$narthex in subsequence($$selection, $start, $pageSize)
              |   order by $$narthex/@mod descending
              |     return
              |       <record>
              |         <header>
              |           <identifier>{$$narthex/@id}</identifier>
              |           <datestamp>{$$narthex/@mod}</datestamp>
              |           <setSpec>to-do!</setSpec>
              |         </header>
              |         $metadata
              |       </record>
              |
              | return
              |   <records start="$start" pageSize="$pageSize">
              |     {$$records}
              |   </records>
              |
              """.stripMargin.trim
        val wrappedRecords: Elem = XML.loadString(session.query(query).execute())
        wrappedRecords \ "record"
    }
  }

}

case class RepoDataSet(spec: String, prefix: String, name: String, dataProvider: String, totalRecords: Int)

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

