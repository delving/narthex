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

import controllers.Application.OkFile
import play.api.Logger
import play.api.Play.current
import play.api.cache.Cache
import play.api.libs.json._
import play.api.mvc._
import services.Repo.TermMapping
import services._

import scala.collection.immutable.Seq
import scala.xml.Elem

object Dashboard extends Controller with Security with TreeHandling with SkosJson {

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

  def datasetInfo(fileName: String) = Secure() {
    token => email => implicit request => {
      val repo = Repo(email)
      val datasetInfo = repo.fileRepo(fileName).getDatasetInfo
      // there should be a better way, but couldn't find one and got impatient
      def extract(tag: String) = {
        (datasetInfo \ tag).head.child.filter(_.isInstanceOf[Elem]).map(n => n.label -> JsString(n.text))
      }
      val status = extract("status")
      val delimit = extract("delimit")
      val namespaceNodes = datasetInfo \ "namespaces" \ "namespace"
      val namespaceObjects = namespaceNodes.map(node => Json.obj(
        "prefix" -> (node \ "prefix").text,
        "uri" -> (node \ "uri").text
      ))
      Ok(JsObject(Seq(
        "status" -> JsObject(status),
        "delimit" -> JsObject(delimit),
        "namespaces" -> JsArray(namespaceObjects)
      )))
    }
  }

  def deleteDataset(fileName: String) = Secure() {
    token => email => implicit request => {
      val repo = Repo(email)
      val fileRepo = repo.fileRepo(fileName)
      fileRepo.delete()
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

  def saveRecords(fileName: String) = Secure() {
    token => email => implicit request => {
      val repo = Repo(email)
      val fileRepo = repo.fileRepo(fileName)
      fileRepo.saveRecords()
      Ok
    }
  }

  def queryRecords(fileName: String) = Secure(parse.json) {
    token => email => implicit request => {
      println("query records arrival")
      val path = (request.body \ "path").as[String]
      val value = (request.body \ "value").as[String]
      val repo = Repo(email)
      val fileRepo = repo.fileRepo(fileName)
      val result: String = fileRepo.queryRecords(path, value)
      Ok(result)
    }
  }

  def listSkos = Secure() {
    token => email => implicit request => {
      Ok(Json.obj("list" -> SkosRepo.listFiles))
    }
  }

  def searchSkos(name: String, sought: String) = Secure() {
    token => email => implicit request => {

      def searchConceptScheme(conceptScheme: ConceptScheme) = conceptScheme.search("dut", sought, 25)

      Cache.getAs[ConceptScheme](name) map {
        cs => Ok(Json.obj("search" -> searchConceptScheme(cs)))
      } getOrElse {
        val cs: ConceptScheme = SkosRepo.conceptScheme(name)
        Cache.set(name, cs, CACHE_EXPIRATION)
        Ok(Json.obj("search" -> searchConceptScheme(cs)))
      }
    }
  }

  def getMappings(fileName: String) = Secure() {
    token => email => implicit request => {
      val repo = Repo(email)
      val fileRepo = repo.fileRepo(fileName)
      val mappings: scala.Seq[TermMapping] = fileRepo.getMappings
      Ok(Json.obj("mappings" -> mappings))
    }
  }

  def setMapping(fileName: String) = Secure(parse.json) {
    token => email => implicit request => {
      val sourceUri = (request.body \ "source").as[String]
      val targetUri = (request.body \ "target").as[String]
      val vocabulary = (request.body \ "vocabulary").as[String]
      val repo = Repo(email)
      val fileRepo = repo.fileRepo(fileName)
      fileRepo.setMapping(TermMapping(sourceUri, targetUri, vocabulary))
      Ok("thanks man")
    }
  }
}
