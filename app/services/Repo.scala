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
import org.basex.server.ClientSession
import org.mindrot.jbcrypt.BCrypt
import play.api.Logger
import play.api.Play.current
import play.api.libs.concurrent.Akka
import play.api.libs.json.{JsArray, JsObject, JsValue, Json}

import scala.collection.mutable.ArrayBuffer

object Repo {
  val SUFFIXES = List(".xml.gz", ".xml")
  val home = new File(System.getProperty("user.home"))
  val root = new File(home, "NARTHEX")
  var baseXDir = new File(root, "basex")

  lazy val baseX: BaseX = new BaseX("localhost", 6789, 6788, "admin", "admin")

  def apply(email: String) = new Repo(root, email)

  def startBaseX() = {
    baseX.startServer(Some(baseXDir))
  }

  def stopBaseX() = {
    baseX.stopServer()
  }

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
}

class Repo(root: File, val email: String) {

  val personalRootName: String = email.replaceAll("[@.]", "_")
  val personalRoot = new File(root, personalRootName)
  val user = new File(personalRoot, "user.json")
  val uploaded = new File(personalRoot, "uploaded")
  val analyzed = new File(personalRoot, "analyzed")

  def create(password: String) = {
    personalRoot.mkdirs()
    val passwordHash = BCrypt.hashpw(password, BCrypt.gensalt())
    writeStringToFile(user, Json.prettyPrint(Json.obj("passwordHash" -> passwordHash)))
  }

  def authenticate(password: String) = {
    if (user.exists()) {
      val userObject = Json.parse(readFileToString(user))
      val passwordHash = (userObject \ "passwordHash").as[String]
      BCrypt.checkpw(password, passwordHash)
    }
    else {
      false
    }
  }

  def uploadedFile(fileName: String) = {
    val suffix = Repo.getSuffix(fileName)

    if (suffix.isEmpty) {
      val fileNameDot = s"$fileName."
      var matchingFiles = uploaded.listFiles().filter(file => file.getName.startsWith(fileNameDot))
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

  def analyzedDir(fileName: String) = new File(analyzed, Repo.stripSuffix(fileName))

  def listUploadedFiles = listFiles(uploaded)

  def listFileRepos = listUploadedFiles.map(file => Repo.stripSuffix(file.getName))

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
        fileRepo.setStatus(fileRepo.SPLITTING, 1, 0)
        val analyzer = Akka.system.actorOf(Analyzer.props(fileRepo), uploadedFile.getName)
        analyzer ! Analyzer.Analyze(job._1)
    }
    files
  }

  def fileRepo(fileName: String) = new FileRepo(this, Repo.stripSuffix(fileName), uploadedFile(fileName), analyzedDir(fileName))

  private def listFiles(directory: File): List[File] = {
    if (directory.exists()) {
      directory.listFiles.filter(file =>
        file.isFile && Repo.SUFFIXES.filter(suffix => file.getName.endsWith(suffix)).nonEmpty
      ).toList
    }
    else {
      List.empty
    }
  }

}

class FileRepo(val personalRepo: Repo, val name: String, val sourceFile: File, val dir: File) {

  val dbName = s"${personalRepo.personalRootName}___$name"
  val recordDatabase = s"${dbName}_records"
  val terminologyDatabase = s"${dbName}_terminology"

  def mkdirs = {
    dir.mkdirs()
    this
  }

  def index = new File(dir, "index.json")

  def status = new File(dir, "status.json")

  def recordDelimiter = new File(dir, "record-delimiter.json")

  val SPLITTING = "1:splitting"
  val ANALYZING = "2:analyzing"
  val ANALYZED = "3:analyzed"
  val SAVING = "4:saving"
  val SAVED = "5:saved"

  def setStatus(state: String, percent: Int, workers: Int) = Repo.createJson(status, Json.obj(
    "state" -> state,
    "percent" -> percent,
    "workers" -> workers
  ))

  def root = new NodeRepo(this, dir)

  def status(path: String): Option[File] = {
    nodeRepo(path) match {
      case None => None
      case Some(nodeDirectory) => Some(nodeDirectory.status)
    }
  }

  def sample(path: String, size: Int): Option[File] = {
    nodeRepo(path) match {
      case None => None
      case Some(repo) =>
        val fileList = repo.sampleJson.filter(pair => pair._1 == size)
        if (fileList.isEmpty) None else Some(fileList.head._2)
    }
  }

  def histogram(path: String, size: Int): Option[File] = {
    nodeRepo(path) match {
      case None => None
      case Some(repo) =>
        val fileList = repo.histogramJson.filter(pair => pair._1 == size)
        if (fileList.isEmpty) None else Some(fileList.head._2)
    }
  }

  def indexText(path: String): Option[File] = {
    nodeRepo(path) match {
      case None => None
      case Some(repo) => Some(repo.indexText)
    }
  }

  def uniqueText(path: String): Option[File] = {
    nodeRepo(path) match {
      case None => None
      case Some(repo) => Some(repo.uniqueText)
    }
  }

  def histogramText(path: String): Option[File] = {
    nodeRepo(path) match {
      case None => None
      case Some(repo) => Some(repo.histogramText)
    }
  }

  def nodeRepo(path: String): Option[NodeRepo] = {
    val nodeDir = path.split('/').toList.foldLeft(dir)((file, tag) => new File(file, Repo.tagToDirectory(tag)))
    if (nodeDir.exists()) Some(new NodeRepo(this, nodeDir)) else None
  }

  def canSaveRecords = {
    val delim = recordDelimiter
    if (!delim.exists()) {
      false
    }
    else {
      val statusData = Repo.readJson(status)
      val state = (statusData \ "state").as[String]
      Logger.info(s"state is $state")
      if (state == SAVED) {
        Logger.info(s"saved state ${delim.lastModified()} greater than ${status.lastModified()}")
        delim.lastModified() > status.lastModified() // delim has been reset
      }
      else {
        state < SAVING // hasn't saved yet
      }
    }
  }

  def setRecordDelimiter(recordRoot: String, uniqueId: String, recordCount: Int) = Repo.createJson(recordDelimiter, Json.obj(
    "recordRoot" -> recordRoot,
    "uniqueId" -> uniqueId,
    "recordCount" -> recordCount
  ))

  def saveRecords() = {
    val delim = Repo.readJson(recordDelimiter)
    var recordRoot = (delim \ "recordRoot").as[String]
    var uniqueId = (delim \ "uniqueId").as[String]
    var recordCount = (delim \ "recordCount").as[Int]
    setStatus(SAVING, 1, 0) // do it now, so it's done before the actor starts
    val saver = Akka.system.actorOf(Saver.props(this), name)
    saver ! SaveRecords(recordRoot, uniqueId, recordCount, name)
  }

  def withNewRecordDatabase[T](block: ClientSession => T) = {
    Repo.baseX.createDatabase(recordDatabase) // overwrites
    Repo.baseX.withDbSession(recordDatabase)(block)
  }

  def queryRecords(queryString: String) = Repo.baseX.query(recordDatabase, queryString)

  def withTerminologySessionCreate[T](block: ClientSession => T) = {
    Repo.baseX.createDatabase(terminologyDatabase)
    Repo.baseX.withDbSession(terminologyDatabase)(block)
  }

  def withTerminologySession[T](block: ClientSession => T) = {
    Repo.baseX.withDbSession(terminologyDatabase)(block)
  }
}

object NodeRepo {
  def apply(parent: FileRepo, parentDir: File, tag: String) = {
    val dir = if (tag == null) parentDir else new File(parentDir, Repo.tagToDirectory(tag))
    dir.mkdirs()
    new NodeRepo(parent, dir)
  }
}

case class TermMapping(sourceTerm: String, targetTerm: String)

class NodeRepo(val parent: FileRepo, val dir: File) {

  lazy val terminologyFile = s"${dir.getAbsolutePath.substring(parent.dir.getAbsolutePath.length).replaceAll("/", "__")}.xml"

  def child(childTag: String) = NodeRepo(parent, dir, childTag)

  def f(name: String) = new File(dir, name)

  def status = f("status.json")

  def setStatus(content: JsObject) = Repo.createJson(status, content)

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

    def createFile(maximum: Int, entries: ArrayBuffer[JsArray], histogramFile: File) = {
      Repo.createJson(histogramFile, Json.obj(
        "uniqueCount" -> uniqueCount,
        "entries" -> entries.size,
        "maximum" -> maximum,
        "complete" -> (entries.size == uniqueCount),
        "histogram" -> entries
      ))
    }

    var activeCounters = histogramJson.map(pair => (pair._1, new ArrayBuffer[JsArray], pair._2))
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

  def initMappings() = {
    parent.withTerminologySessionCreate {
      session =>
        val upsert = s"""
            | return
            |    if (doc-available('${parent.terminologyDatabase}/$terminologyFile.xml'))
            |    then ()
            |    else db:add('${parent.terminologyDatabase}', <term-mappings/>, '/$terminologyFile.xml')
          """.stripMargin

        session.execute(upsert)
    }
  }

  def addMapping(mapping: TermMapping) = {


    parent.withTerminologySession {
      session =>

        def quote(value: String) = {
          value match {
            case "" => "''"
            case string =>
              "'" + string.replace("'", "\'\'") + "'"
          }
        }


        val upsert = s"""
            |
            | let $$freshMapping :=
            |   <term-mapping>
            |     <source-term>${mapping.sourceTerm}</source-term>
            |     <target-term>${mapping.targetTerm}</target-term>
            |   </term-mapping>
            |
            | let $$termMappings := doc('${parent.terminologyDatabase}/$terminologyFile.xml')
            |
            | let $$termMapping := $$termMappings/term-mappings/term-mapping[source-term=${quote(mapping.sourceTerm)}]
            |
            | return
            |   if (db:exists($$termMapping))
            |   then replace node $$termMapping with $$freshMapping
            |   else insert node $$termMapping into $$termMappings
            |
          """.stripMargin


        println("We ask this:")
        val lines = upsert.split("\n").zipWithIndex.map(line => s"${line._2 + 1}: ${line._1}")
        println(lines.mkString("\n"))

        val answer = session.query(upsert).execute()

        println("The answer is:")
        println(answer);

      /*

        add to /term-mapping/__something.xml <term-mappings/>

        for $tm in doc('test_narthex_delving_org___pretend-file/term-mapping/__something.xml')
        return insert node <term-mapping/> into $tm/term-mappings

        def vocabDocument(vocabName: String) = s"/vocabulary/$vocabName.xml"
        def vocabPath(vocabName:String) = s"doc('$database${vocabDocument(vocabName)}')"
        def vocabExists(vocabName: String) = s"db:exists('$database','${vocabDocument(vocabName)}')"
        def vocabAdd(vocabName: String, xml:String) = s"db:add('$database', $xml,'${vocabDocument(vocabName)}')"

        s.update('add vocab entry',
            [
                'if (' + s.vocabExists(vocabName) + ')',
                'then insert node (' + entryXml + ') into ' + s.vocabPath(vocabName) + '/Entries',
                'else '+ s.vocabAdd(vocabName, '<Entries>'+entryXml+'</Entries>')
            ],
            function (result) {
                if (result) {
                    receiver(entryXml); // use the result?
                }
                else {
                    receiver(null);
                }
            }
        );

       */
      //
      //        val hash = hashString(record)
      //        val inputStream = new ByteArrayInputStream(record.getBytes("UTF-8"))
      //        session.add(s"$collection/$hash.xml", inputStream)
    }

  }

}

