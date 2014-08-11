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
import services.Repo.repo
import services._

import scala.collection.immutable.Seq
import scala.xml.Elem

object Dashboard extends Controller with Security with TreeHandling with SkosJson {

  def upload = Secure(parse.multipartFormData) {
    token => implicit request => {
      request.body.file("file") match {
        case Some(file) =>
          Logger.info(s"upload ${file.filename} (${file.contentType})")
          if (Repo.acceptableFile(file.filename, file.contentType)) {
            println(s"Acceptable ${file.filename}")
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
    token => implicit request => {
      repo.scanForAnalysisWork()
      val stringList = repo.listFileRepos
      Ok(Json.toJson(stringList.map(string => Json.obj("name" -> string))))
    }
  }

  def work = Secure() {
    token => implicit request => {
      repo.scanForAnalysisWork()
      Ok
    }
  }

  def datasetInfo(fileName: String) = Secure() {
    token => implicit request => {
      val datasetInfo = repo.fileRepo(fileName).datasetDb.getDatasetInfo
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
    token => implicit request => {
      val fileRepo = repo.fileRepo(fileName)
      fileRepo.delete()
      Ok
    }
  }

  def index(fileName: String) = Secure() {
    token => implicit request => {
      OkFile(repo.fileRepo(fileName).index)
    }
  }

  def nodeStatus(fileName: String, path: String) = Secure() {
    token => implicit request => {
      repo.fileRepo(fileName).status(path) match {
        case None => NotFound(Json.obj("path" -> path))
        case Some(file) => OkFile(file)
      }
    }
  }

  def sample(fileName: String, path: String, size: Int) = Secure() {
    token => implicit request => {
      repo.fileRepo(fileName).sample(path, size) match {
        case None => NotFound(Json.obj("path" -> path, "size" -> size))
        case Some(file) => OkFile(file)
      }
    }
  }

  def histogram(fileName: String, path: String, size: Int) = Secure() {
    token => implicit request => {
      repo.fileRepo(fileName).histogram(path, size) match {
        case None => NotFound(Json.obj("path" -> path, "size" -> size))
        case Some(file) => OkFile(file)
      }
    }
  }

  def setRecordDelimiter(fileName: String) = Secure(parse.json) {
    token => implicit request => {
      var recordRoot = (request.body \ "recordRoot").as[String]
      var uniqueId = (request.body \ "uniqueId").as[String]
      var recordCount = (request.body \ "recordCount").as[Int]
      val fileRepo = repo.fileRepo(fileName)
      fileRepo.datasetDb.setRecordDelimiter(recordRoot, uniqueId, recordCount)
      println(s"store recordRoot=$recordRoot uniqueId=$uniqueId recordCount=$recordCount")
      //      fileRepo.saveRecords(recordRoot, uniqueId, recordCount)
      Ok
    }
  }

  def saveRecords(fileName: String) = Secure() {
    token => implicit request => {
      val fileRepo = repo.fileRepo(fileName)
      fileRepo.recordRepo.saveRecords()
      Ok
    }
  }

  def queryRecords(fileName: String) = Secure(parse.json) {
    token => implicit request => {
      println("query records arrival")
      val path = (request.body \ "path").as[String]
      val value = (request.body \ "value").as[String]
      val fileRepo = repo.fileRepo(fileName)
      val result: String = fileRepo.recordRepo.recordsWithValue(path, value)
      Ok(result)
    }
  }

  def listSkos = Secure() {
    token => implicit request => {
      Ok(Json.obj("list" -> SkosRepo.listFiles))
    }
  }

  def searchSkos(name: String, sought: String) = Secure() {
    token => implicit request => {

      def searchVocabulary(vocabulary: SkosVocabulary): LabelSearch = vocabulary.search("dut", sought, 25)

      Cache.getAs[SkosVocabulary](name) map {
        vocabulary => Ok(Json.obj("search" -> searchVocabulary(vocabulary)))
      } getOrElse {
        val vocabulary = SkosRepo.vocabulary(name)
        Cache.set(name, vocabulary, CACHE_EXPIRATION)
        Ok(Json.obj("search" -> searchVocabulary(vocabulary)))
      }
    }
  }

  def getMappings(fileName: String) = Secure() {
    token => implicit request => {
      val fileRepo = repo.fileRepo(fileName)
      val mappings: scala.Seq[TermDb.TermMapping] = fileRepo.termRepo.getMappings
      Ok(Json.obj("mappings" -> mappings))
    }
  }

  def setMapping(fileName: String) = Secure(parse.json) {
    token => implicit request => {
      val sourceUri = (request.body \ "source").as[String]
      val targetUri = (request.body \ "target").as[String]
      val vocabulary = (request.body \ "vocabulary").as[String]
      val prefLabel = (request.body \ "prefLabel").as[String]
      val fileRepo = repo.fileRepo(fileName)
      fileRepo.termRepo.setMapping(TermDb.TermMapping(sourceUri, targetUri, vocabulary, prefLabel))
      Ok("thanks man")
    }
  }

  def listSipFiles = Secure() {
    token => implicit request => {
      val fileNames = repo.listSipZip.map(_._1.getName)
      Ok(Json.obj("list" -> fileNames))
    }
  }
}
