package services

import java.io.File
import play.Logger
import play.api.Play.current
import play.api.libs.concurrent.Akka
import akka.actor.Props

class FileRepository(root: File, val email: String) {

  val SUFFIXES = List(".xml.gz", ".tgz", ".zip")
  val repo = new File(root, email.replaceAll("@", "_"))
  val uploaded = new File(repo, "uploaded")
  val analyzed = new File(repo, "analyzed")

  def uploadedFile(fileName: String) = new File(uploaded, fileName)

  def analyzedDir(dirName: String) = new File(analyzed, dirName)

  def analyzedFile(fileName: String) = new File(analyzedDir(fileName), FileRepository.analysisFileName)

  def statusFile(fileName: String) = new File(analyzedDir(fileName), FileRepository.statusFileName)

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

  val analysisFileName = "analysis.json"
  val statusFileName = "status.json"
  val home = new File(System.getProperty("user.home"))
  val root = new File(home, "XML-RAY")

  val boss = Akka.system.actorOf(Props[Boss])

  def apply(email: String) = new FileRepository(root, email)

}