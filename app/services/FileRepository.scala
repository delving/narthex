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

  def tagToDirectory(tag: String) = tag.replace(":", "_").replace("@", "_")

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
    FileRepository.boss ! Actors.AnalyzeThese(filesToAnalyze.zip(analyzedDirs))
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

  def indexFile = new File(directory, "index.json")

  def statusFile = new File(directory, "status.json")

  def root = new NodeDirectory(this, directory)

  def sampleFile(path: String, size: Int): Option[File] = {
    nodeDirectory(path) match {
      case None => None
      case Some(nodeDirectory) =>
        val fileList = nodeDirectory.sampleJsonFiles.filter(pair => pair._1 == size)
        if (fileList.isEmpty) None else Some(fileList.head._2)
    }
  }

  def histogramFile(path: String, size: Int): Option[File] = {
    nodeDirectory(path) match {
      case None => None
      case Some(nodeDirectory) =>
        val fileList = nodeDirectory.histogramJsonFiles.filter(pair => pair._1 == size)
        if (fileList.isEmpty) None else Some(fileList.head._2)
    }
  }

  def nodeDirectory(path: String): Option[NodeDirectory] = {
    val dir = path.split('/').toList.foldLeft(directory)((file, tag) => new File(file, FileRepository.tagToDirectory(tag)))
    if (dir.exists()) Some(new NodeDirectory(this, dir)) else None
  }

}

object NodeDirectory {
  def apply(fileAnalysisDirectory: FileAnalysisDirectory, parentDirectory: File, tag: String) = {
    val dir = if (tag == null) parentDirectory else new File(parentDirectory, FileRepository.tagToDirectory(tag))
    dir.mkdirs()
    new NodeDirectory(fileAnalysisDirectory, dir)
  }
}

class NodeDirectory(val fileAnalysisDirectory: FileAnalysisDirectory, val directory: File) {

  def child(childTag: String) = NodeDirectory(fileAnalysisDirectory, directory, childTag)

  def statusFile = new File(directory, "status.json")

  def valuesFile = new File(directory, "values.txt")

  def tempSortFile = new File(directory, s"sorting-${UUID.randomUUID()}.txt")

  def sortedFile = new File(directory, "sorted.txt")

  def countedFile = new File(directory, "counted.txt")

  def uniqueFile = new File(directory, "unique.txt")

  def histogramTextFile = new File(directory, "histogram.txt")

  val sizeFactor = 10 // relates to the lists below

  def histogramJsonFiles = List(10, 100, 1000).map(size => (size, new File(directory, s"histogram-$size.json")))

  def sampleJsonFiles = List(100, 1000).map(size => (size, new File(directory, s"sample-$size.json")))
}

