package services

import java.io.File
import play.api.Play.current
import play.api.libs.concurrent.Akka
import akka.actor.Props
import java.util.UUID

class FileRepository(root: File, val email: String) {

  val SUFFIXES = List(".xml.gz", ".tgz", ".zip")
  val repo = new File(root, email.replaceAll("@", "_"))
  val uploaded = new File(repo, "uploaded")
  val analyzed = new File(repo, "analyzed")

  def uploadedFile(fileName: String) = new File(uploaded, fileName)

  def analyzedDir(dirName: String) = new File(analyzed, dirName)

  def treeFile(fileName: String) = FileRepository.treeFile(analyzedDir(fileName))

  def statusFile(fileName: String) = FileRepository.statusFile(analyzedDir(fileName))

  def listUploadedFiles = listFiles(uploaded)

  def uploadedOnly() = listUploadedFiles.filter(file => !analyzedDir(file.getName).exists())

  def scanForWork() = {
    val filesToAnalyze = uploadedOnly()
    val analyzedDirs = filesToAnalyze.map(file => analyzedDir(file.getName))
    analyzedDirs.foreach(_.mkdirs())
    FileRepository.boss ! AnalyzeThese(filesToAnalyze.zip(analyzedDirs))
  }

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

object FileRepository {

  def treeFile(directory: File): File = new File(directory, "tree.json")

  def statusFile(directory: File): File = new File(directory, "status.json")

  def valuesFile(directory: File): File = new File(directory, "values.txt")

  def random(directory: File, size: Int): File = new File(directory, s"random-$size.txt")

  def tempSortFile(directory: File): File = new File(directory, s"sorting-${UUID.randomUUID()}.txt")

  def sortedFile(directory: File): File = new File(directory, "sorted.txt")

  def countedFile(directory: File): File = new File(directory, "counted.txt")

  def histogramTextFile(directory: File): File = new File(directory, "histogram.txt")

  def histogramJsonFile(directory: File): File = new File(directory, "histogram.json")

  val home = new File(System.getProperty("user.home"))
  val root = new File(home, "XML-RAY")

  lazy val boss = Akka.system.actorOf(Props[Boss], "boss")

  def apply(email: String) = new FileRepository(root, email)

}