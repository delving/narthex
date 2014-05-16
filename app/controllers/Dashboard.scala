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
import java.io.File
import play.api.Logger
import services._
import play.api.libs.json._
import controllers.Application.OkFile
import org.apache.commons.io.FileUtils._
import scala.Some

object Dashboard extends Controller with Security with XRay {

  def upload = Secure(parse.multipartFormData) {
    token => email => implicit request => {
      request.body.file("file") match {
        case Some(file) =>
          Logger.info(s"upload ${file.filename} (${file.contentType}) to $email")
          if (Repository.acceptable(file.filename, file.contentType)) {
            println(s"Acceptable ${file.filename}")
            val repo = Repository(email)
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
      val repo = Repository(email)
      repo.scanForWork()
      val fileList: Seq[File] = repo.listUploadedFiles
      val stringList = fileList.map(file => s"${file.getName}")
      Ok(Json.toJson(stringList.map(string => Json.obj("name" -> string))))
    }
  }

  def work = Secure() {
    token => email => implicit request => {
      val repo = Repository(email)
      repo.scanForWork()
      Ok
    }
  }

  def status(fileName: String) = Secure() {
    token => email => implicit request => {
      val repo = Repository(email)
      OkFile(repo.fileRepo(fileName).status)
    }
  }

  def zap(fileName: String) = Secure() {
    token => email => implicit request => {
      val repo = Repository(email)
      deleteQuietly(repo.uploadedFile(fileName))
      deleteDirectory(repo.fileRepo(fileName).dir)
      Ok
    }
  }

  def index(fileName: String) = Secure() {
    token => email => implicit request => {
      val repo = Repository(email)
      OkFile(repo.fileRepo(fileName).index)
    }
  }

  def nodeStatus(fileName: String, path: String) = Secure() {
    token => email => implicit request => {
      val repo = Repository(email)
      repo.fileRepo(fileName).status(path) match {
        case None => NotFound(Json.obj("path" -> path))
        case Some(file) => OkFile(file)
      }
    }
  }

  def sample(fileName: String, path: String, size: Int) = Secure() {
    token => email => implicit request => {
      val repo = Repository(email)
      repo.fileRepo(fileName).sample(path, size) match {
        case None => NotFound(Json.obj("path" -> path, "size" -> size))
        case Some(file) => OkFile(file)
      }
    }
  }

  def histogram(fileName: String, path: String, size: Int) = Secure() {
    token => email => implicit request => {
      val repo = Repository(email)
      repo.fileRepo(fileName).histogram(path, size) match {
        case None => NotFound(Json.obj("path" -> path, "size" -> size))
        case Some(file) => OkFile(file)
      }
    }
  }

  def storeRecords(fileName: String) = Secure(parse.json) {
    token => email => implicit request => {
      var recordRoot = (request.body \ "recordRoot").as[String]
      var uniqueId = (request.body \ "uniqueId").as[String]
      println(s"store recordRoot=$recordRoot uniqueId=$uniqueId")
      Ok(Json.obj("message" -> "Not yet implemented"))
    }
  }

}
