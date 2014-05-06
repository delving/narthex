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
import play.api.Play.current
import play.api.libs.concurrent.Akka
import akka.actor.Props
import java.util.UUID
import play.api.libs.json.Json
import org.mindrot.jbcrypt.BCrypt
import org.apache.commons.io.FileUtils._
import scala.Some

object FileRepository {

  val home = new File(System.getProperty("user.home"))
  val root = new File(home, "XML-RAY")

  lazy val boss = Akka.system.actorOf(Props[Boss], "boss")

  def apply(email: String) = new PersonalRepository(root, email)

  def tagToDirectory(tag: String) = tag.replace(":", "_").replace("@", "_")

}

class PersonalRepository(root: File, val email: String) {

  val SUFFIXES = List(".xml.gz", ".xml.zip")
  val repo = new File(root, email.replaceAll("@", "_"))
  val user = new File(repo, "user.json")
  val uploaded = new File(repo, "uploaded")
  val analyzed = new File(repo, "analyzed")

  def create(password: String) = {
    repo.mkdirs()
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

  def statusFile(path: String): Option[File] = {
    nodeDirectory(path) match {
      case None => None
      case Some(nodeDirectory) => Some(nodeDirectory.statusFile)
    }
  }

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

  def uniqueTextFile(path: String): Option[File] = {
    nodeDirectory(path) match {
      case None => None
      case Some(nodeDirectory) => Some(nodeDirectory.uniqueTextFile)
    }
  }

  def histogramTextFile(path: String): Option[File] = {
    nodeDirectory(path) match {
      case None => None
      case Some(nodeDirectory) => Some(nodeDirectory.histogramTextFile)
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

  def file(name: String) = new File(directory, name)

  def statusFile = file("status.json")

  def valuesFile = file("values.txt")

  def tempSortFile = file(s"sorting-${UUID.randomUUID()}.txt")

  def sortedFile = file("sorted.txt")

  def countedFile = file("counted.txt")

  val sizeFactor = 5 // relates to the lists below

  def histogramJsonFiles = List(100, 500, 2500).map(size => (size, file(s"histogram-$size.json")))

  def sampleJsonFiles = List(100, 500, 2500).map(size => (size, file(s"sample-$size.json")))

  def uniqueTextFile = file("unique.txt")

  def histogramTextFile = file("histogram.txt")

}

