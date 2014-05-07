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

object Dashboard extends Controller with Security with XRay {

  def upload = Secure(parse.multipartFormData) {
    token => email => implicit request => {
      request.body.file("file") match {
        case Some(file) =>
          Logger.info(s"upload ${file.filename} (${file.contentType}) to $email")
          val repo = FileRepository(email)
          file.ref.moveTo(repo.uploadedFile(file.filename))
          Ok(file.filename)
        case None =>
          Redirect(routes.Application.index()).flashing("error" -> "Missing file")
      }
    }
  }

  def list = Secure() {
    token => email => implicit request => {
      val repo = FileRepository(email)
      repo.scanForWork()
      val fileList: Seq[File] = repo.listUploadedFiles
      val stringList = fileList.map(file => s"${file.getName}")
      Ok(Json.toJson(stringList.map(string => Json.obj("name" -> string))))
    }
  }

  def work = Secure() {
    token => email => implicit request => {
      val repo = FileRepository(email)
      repo.scanForWork()
      Ok
    }
  }

  def status(fileName: String) = Secure() {
    token => email => implicit request => {
      val repo = FileRepository(email)
      OkFile(repo.analysis(fileName).statusFile)
    }
  }

  def index(fileName: String) = Secure() {
    token => email => implicit request => {
      val repo = FileRepository(email)
      OkFile(repo.analysis(fileName).indexFile)
    }
  }

  def nodeStatus(fileName: String, path: String) = Secure() {
    token => email => implicit request => {
      val repo = FileRepository(email)
      repo.analysis(fileName).statusFile(path) match {
        case None => NotFound(Json.obj("path" -> path))
        case Some(file) => OkFile(file)
      }
    }
  }

  def sample(fileName: String, path: String, size: Int) = Secure() {
    token => email => implicit request => {
      val repo = FileRepository(email)
      repo.analysis(fileName).sampleFile(path, size) match {
        case None => NotFound(Json.obj("path" -> path, "size" -> size))
        case Some(file) => OkFile(file)
      }
    }
  }

  def histogram(fileName: String, path: String, size: Int) = Secure() {
    token => email => implicit request => {
      val repo = FileRepository(email)
      repo.analysis(fileName).histogramFile(path, size) match {
        case None => NotFound(Json.obj("path" -> path, "size" -> size))
        case Some(file) => OkFile(file)
      }
    }
  }
}
