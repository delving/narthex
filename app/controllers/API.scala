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
import services._
import play.api.libs.json._
import controllers.Application.OkFile
import scala.Some

object API extends Controller with XRay {

  def indexJSON(apiKey: String, email:String, fileName: String) = Action(parse.anyContent) {
    implicit request => {
      if (checkKey(email, fileName, apiKey)) {
        val repo = Repo(email)
        OkFile(repo.FileRepo(fileName).index)
      }
      else {
        Unauthorized
      }
    }
  }

  def indexText(apiKey: String, email:String, fileName: String, path: String) = Action(parse.anyContent) {
    implicit request => {
      if (checkKey(email, fileName, apiKey)) {
        val repo = Repo(email)
        repo.FileRepo(fileName).indexText(path) match {
          case None => NotFound(Json.obj("path" -> path))
          case Some(file) => OkFile(file)
        }
      }
      else {
        Unauthorized
      }
    }
  }

  def uniqueText(apiKey: String, email:String, fileName: String, path: String) = Action(parse.anyContent) {
    implicit request => {
      if (checkKey(email, fileName, apiKey)) {
        val repo = Repo(email)
        repo.FileRepo(fileName).uniqueText(path) match {
          case None => NotFound(Json.obj("path" -> path))
          case Some(file) => OkFile(file)
        }
      }
      else {
        Unauthorized
      }
    }
  }

  def histogramText(apiKey: String, email:String, fileName: String, path: String) = Action(parse.anyContent) {
    implicit request => {
      if (checkKey(email, fileName, apiKey)) {
        val repo = Repo(email)
        repo.FileRepo(fileName).histogramText(path) match {
          case None => NotFound(Json.obj("path" -> path))
          case Some(file) => OkFile(file)
        }
      }
      else {
        Unauthorized
      }
    }
  }

  private def checkKey(email:String , fileName:String, apiKey:String) = { // todo: mindless so far, and do it as an action like in Security.scala
    val hash = s"narthex|$email|$fileName".hashCode
    val expected: String = Integer.toString(hash, 16).substring(1)
    expected == apiKey
  }
}
