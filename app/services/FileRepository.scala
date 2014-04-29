package services

import java.io.File
import play.api.Play.current
import play.api.libs.concurrent.Akka
import akka.actor.Props
import java.util.UUID

object FileRepository {

  val home = new File(System.getProperty("user.home"))
  val root = new File(home, "XML-RAY")

  lazy val boss = Akka.system.actorOf(Props[Boss], "boss")

  def apply(email: String) = new PersonalRepository(root, email)

}

class PersonalRepository(root: File, val email: String) {

  val SUFFIXES = List(".xml.gz", ".tgz", ".zip")
  val repo = new File(root, email.replaceAll("@", "_"))
  val uploaded = new File(repo, "uploaded")
  val analyzed = new File(repo, "analyzed")

  def uploadedFile(fileName: String) = new File(uploaded, fileName)

  def analyzedDir(dirName: String) = new File(analyzed, dirName)

  def listUploadedFiles = listFiles(uploaded)

  def uploadedOnly() = listUploadedFiles.filter(file => !analyzedDir(file.getName).exists())

  def scanForWork() = {
    val filesToAnalyze = uploadedOnly()
    val analyzedDirs = filesToAnalyze.map(file => analyzedDir(file.getName))
    analyzedDirs.foreach(_.mkdirs())
    FileRepository.boss ! AnalyzeThese(filesToAnalyze.zip(analyzedDirs))
  }

  def analysis(fileName: String) = new FileAnalysisDirectory(analyzedDir(fileName))

  private def listFiles(directory: File): List[File] = {
    if (directory.exists()) {
      directory.listFiles.filter(file =>
        file.isFile && !SUFFIXES.filter(suffix => file.getName.endsWith(suffix)).isEmpty
      ).toList
    }
    else {
      List.empty
    }
  }

}

class FileAnalysisDirectory(val directory: File) {

  def treeFile = new File(directory, "tree.json")

  def statusFile = new File(directory, "status.json")

  def root = new NodeDirectory(directory, null)

}

class NodeDirectory(val parentDirectory: File, tag: String) {

  val directory: File = {
    val dir = if (tag == null) parentDirectory else {
      val directoryName = tag.replace(":", "_").replace("@", "_")
      new File(parentDirectory, directoryName)
    }
    dir.mkdirs()
    dir
  }

  def child(childTag: String) = new NodeDirectory(directory, childTag)

  def treeFile = new File(directory, "tree.json")

  def statusFile = new File(directory, "status.json")

  def valuesFile = new File(directory, "values.txt")

  def tempSortFile = new File(directory, s"sorting-${UUID.randomUUID()}.txt")

  def sortedFile = new File(directory, "sorted.txt")

  def countedFile = new File(directory, "counted.txt")

  def uniqueFile = new File(directory, "unique.txt")

  def histogramTextFile = new File(directory, "histogram.txt")

  def histogramJsonFiles = List(10, 100, 1000).map(size => (size, new File(directory, s"histogram-$size.json")))

  def sampleJsonFiles = List(100, 1000).map(size => (size, new File(directory, s"sample-$size.json")))

}

