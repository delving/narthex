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

package controllers

import play.api.mvc._
import play.api.Logger
import services._
import play.api.libs.json._
import controllers.Application.OkFile
import org.apache.commons.io.FileUtils._
import scala.Some

object Dashboard extends Controller with Security with TreeHandling {

  def upload = Secure(parse.multipartFormData) {
    token => email => implicit request => {
      request.body.file("file") match {
        case Some(file) =>
          Logger.info(s"upload ${file.filename} (${file.contentType}) to $email")
          if (Repo.acceptableFile(file.filename, file.contentType)) {
            println(s"Acceptable ${file.filename}")
            val repo = Repo(email)
            file.ref.moveTo(repo.uploadedFile(file.filename), replace = true)
            Ok(file.filename)
          }
          else {
            println(s"NOT Acceptable ${file.filename}")
            NotAcceptable(Json.obj("problem" -> "File must be .xml or .xml.gz"))
          }
        case None =>
          NotAcceptable(Json.obj("problem" -> "Missing file"))
      }
    }
  }

  def list = Secure() {
    token => email => implicit request => {
      val repo = Repo(email)
      repo.scanForAnalysisWork()
      val stringList = repo.listFileRepos
      Ok(Json.toJson(stringList.map(string => Json.obj("name" -> string))))
    }
  }

  def work = Secure() {
    token => email => implicit request => {
      val repo = Repo(email)
      repo.scanForAnalysisWork()
      Ok
    }
  }

  def status(fileName: String) = Secure() {
    token => email => implicit request => {
      val repo = Repo(email)
      OkFile(repo.fileRepo(fileName).status)
    }
  }

  def zap(fileName: String) = Secure() {
    token => email => implicit request => {
      val repo = Repo(email)
      deleteQuietly(repo.uploadedFile(fileName))
      deleteDirectory(repo.fileRepo(fileName).dir)
      Ok
    }
  }

  def index(fileName: String) = Secure() {
    token => email => implicit request => {
      val repo = Repo(email)
      OkFile(repo.fileRepo(fileName).index)
    }
  }

  def nodeStatus(fileName: String, path: String) = Secure() {
    token => email => implicit request => {
      val repo = Repo(email)
      repo.fileRepo(fileName).status(path) match {
        case None => NotFound(Json.obj("path" -> path))
        case Some(file) => OkFile(file)
      }
    }
  }

  def sample(fileName: String, path: String, size: Int) = Secure() {
    token => email => implicit request => {
      val repo = Repo(email)
      repo.fileRepo(fileName).sample(path, size) match {
        case None => NotFound(Json.obj("path" -> path, "size" -> size))
        case Some(file) => OkFile(file)
      }
    }
  }

  def histogram(fileName: String, path: String, size: Int) = Secure() {
    token => email => implicit request => {
      val repo = Repo(email)
      repo.fileRepo(fileName).histogram(path, size) match {
        case None => NotFound(Json.obj("path" -> path, "size" -> size))
        case Some(file) => OkFile(file)
      }
    }
  }

  def recordDelimiter(fileName: String) = Secure() {
    token => email => implicit request => {
      val repo = Repo(email)
      val fileRepo = repo.fileRepo(fileName)
      val recordDelimiter = fileRepo.recordDelimiter
      if (recordDelimiter.exists()) {
        OkFile(recordDelimiter)
      }
      else {
        Ok(Json.obj("message" -> "Not Delimited"))
      }
    }
  }

  def setRecordDelimiter(fileName: String) = Secure(parse.json) {
    token => email => implicit request => {
      var recordRoot = (request.body \ "recordRoot").as[String]
      var uniqueId = (request.body \ "uniqueId").as[String]
      var recordCount = (request.body \ "recordCount").as[Int]
      val repo = Repo(email)
      val fileRepo = repo.fileRepo(fileName)
      fileRepo.setRecordDelimiter(recordRoot, uniqueId, recordCount)
      println(s"store recordRoot=$recordRoot uniqueId=$uniqueId recordCount=$recordCount")
//      fileRepo.saveRecords(recordRoot, uniqueId, recordCount)
      Ok
    }
  }

  def canSaveRecords(fileName: String) = Secure() {
    token => email => implicit request => {
      val repo = Repo(email)
      val fileRepo = repo.fileRepo(fileName)
      Ok(Json.obj("canSave" -> fileRepo.canSaveRecords))
    }
  }

  def saveRecords(fileName: String) = Secure() {
    token => email => implicit request => {
      val repo = Repo(email)
      val fileRepo = repo.fileRepo(fileName)
      fileRepo.saveRecords()
      Ok
    }
  }

  def queryRecords(fileName: String) = Secure() {
    token => email => implicit request => {
      val repo = Repo(email)
      val fileRepo = repo.fileRepo(fileName)
      val result: String = fileRepo.queryDatabase()
      Logger.info(result)
      Ok(result)
    }
  }
}
