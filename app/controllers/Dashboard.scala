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
import services.DatasetState._
import services.Repo.repo
import services._

import scala.xml.Elem

object Dashboard extends Controller with Security with TreeHandling with SkosJson {

  def upload = Secure(parse.multipartFormData) {
    token => implicit request => {
      request.body.file("file") match {
        case Some(file) =>
          Logger.info(s"upload ${file.filename} (${file.contentType})")
          if (RepoUtil.acceptableFile(file.filename, file.contentType)) {
            println(s"Acceptable ${file.filename}")
            val datasetRepo = repo.datasetRepo(file.filename)
            file.ref.moveTo(datasetRepo.sourceFile, replace = true)
            datasetRepo.datasetDb.createDataset(DatasetState.READY)
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

  def harvest = Secure(parse.json) {
    token => implicit request => {
      def string(tag: String) = (request.body \ tag).asOpt[String] getOrElse ""
      def filled(tag: String) = (request.body \ tag).asOpt[String] getOrElse (throw new IllegalArgumentException(s"Missing $tag"))
      try {
        val datasetRepo = repo.datasetRepo(filled("name"))
        filled("harvestType") match {
          case "pmh" =>
            datasetRepo.startPmhHarvest(filled("url"), string("dataset"), filled("prefix"))
          case "adlib" =>
            datasetRepo.startAdLibHarvest(filled("url"), filled("dataset"))
          case _ =>
            throw new IllegalArgumentException("Missing harvestType=[pmh,adlib]")
        }
        Ok
      } catch {
        case e: IllegalArgumentException =>
          NotAcceptable(Json.obj("problem" -> e.getMessage))
      }
    }
  }

  def list = Secure() {
    token => implicit request => {
      val datasets = repo.repoDb.listDatasets.map {
        dataset =>
          val lists = DATASET_PROPERTY_LISTS.map(name => name -> toJsObject(dataset.info, name))
          Json.obj("name" -> dataset.name, "info" -> JsObject(lists))
      }
      Ok(JsArray(datasets))
    }
  }

  def analyze(fileName: String) = Secure() {
    token => implicit request => {
      repo.datasetRepoOption(fileName) match {
        case Some(datasetRepo) =>
          datasetRepo.startAnalysis()
          Ok
        case None =>
          NotFound(Json.obj("problem" -> s"Not found $fileName"))
      }
    }
  }

  def datasetInfo(fileName: String) = Secure() {
    token => implicit request => {
      repo.datasetRepo(fileName).datasetDb.getDatasetInfoOption match {
        case Some(datasetInfo) =>
          val lists = DATASET_PROPERTY_LISTS.map(name => name -> toJsObject(datasetInfo, name))
          Ok(JsObject(lists))
        case None =>
          NotFound(Json.obj("problem" -> s"Not found $fileName"))
      }
    }
  }

  def setPublished(fileName: String, published: String) = Secure() {
    token => implicit request => {
      val state = if (published == "true") PUBLISHED else SAVED
      val datasetInfo = repo.datasetRepo(fileName).datasetDb.setStatus(state, 0, 0)
      Ok(Json.obj("state" -> state.toString))
    }
  }

  def deleteDataset(fileName: String) = Secure() {
    token => implicit request => {
      val datasetRepo = repo.datasetRepo(fileName)
      datasetRepo.delete()
      Ok
    }
  }

  def index(fileName: String) = Secure() {
    token => implicit request => {
      OkFile(repo.datasetRepo(fileName).index)
    }
  }

  def nodeStatus(fileName: String, path: String) = Secure() {
    token => implicit request => {
      repo.datasetRepo(fileName).status(path) match {
        case None => NotFound(Json.obj("path" -> path))
        case Some(file) => OkFile(file)
      }
    }
  }

  def sample(fileName: String, path: String, size: Int) = Secure() {
    token => implicit request => {
      repo.datasetRepo(fileName).sample(path, size) match {
        case None => NotFound(Json.obj("path" -> path, "size" -> size))
        case Some(file) => OkFile(file)
      }
    }
  }

  def histogram(fileName: String, path: String, size: Int) = Secure() {
    token => implicit request => {
      repo.datasetRepo(fileName).histogram(path, size) match {
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
      val datasetRepo = repo.datasetRepo(fileName)
      datasetRepo.datasetDb.setRecordDelimiter(recordRoot, uniqueId, recordCount)
      println(s"store recordRoot=$recordRoot uniqueId=$uniqueId recordCount=$recordCount")
      //      datasetRepo.saveRecords(recordRoot, uniqueId, recordCount)
      Ok
    }
  }

  def saveRecords(fileName: String) = Secure() {
    token => implicit request => {
      val datasetRepo = repo.datasetRepo(fileName)
      datasetRepo.recordRepo.saveRecords()
      Ok
    }
  }

  def queryRecords(fileName: String) = Secure(parse.json) {
    token => implicit request => {
      println("query records arrival")
      val path = (request.body \ "path").as[String]
      val value = (request.body \ "value").as[String]
      val datasetRepo = repo.datasetRepo(fileName)
      val result: String = datasetRepo.recordRepo.recordsWithValue(path, value)
      Ok(result)
    }
  }

  def listSkos = Secure() {
    token => implicit request => {
      Ok(Json.obj("list" -> SkosRepo.repo.listFiles))
    }
  }

  def searchSkos(name: String, sought: String) = Secure() {
    token => implicit request => {

      def searchVocabulary(vocabulary: SkosVocabulary): LabelSearch = vocabulary.search("dut", sought, 25)

      Cache.getAs[SkosVocabulary](name) map {
        vocabulary => Ok(Json.obj("search" -> searchVocabulary(vocabulary)))
      } getOrElse {
        val vocabulary = SkosRepo.repo.vocabulary(name)
        Cache.set(name, vocabulary, CACHE_EXPIRATION)
        Ok(Json.obj("search" -> searchVocabulary(vocabulary)))
      }
    }
  }

  def getMappings(fileName: String) = Secure() {
    token => implicit request => {
      val datasetRepo = repo.datasetRepo(fileName)
      val mappings: scala.Seq[TermDb.TermMapping] = datasetRepo.termRepo.getMappings
      Ok(Json.obj("mappings" -> mappings))
    }
  }

  def getSourcePaths(fileName: String) = Secure() {
    token => implicit request => {
      val datasetRepo = repo.datasetRepo(fileName)
      val sourcePaths = datasetRepo.termRepo.getSourcePaths
      Ok(Json.obj("sourcePaths" -> sourcePaths))
    }
  }

  def setMapping(fileName: String) = Secure(parse.json) {
    token => implicit request => {
      val datasetRepo = repo.datasetRepo(fileName)
      if ((request.body \ "remove").asOpt[String].isDefined) {
        val sourceUri = (request.body \ "source").as[String]
        datasetRepo.termRepo.removeMapping(sourceUri)
        Ok("Mapping removed")
      }
      else {
        val sourceUri = (request.body \ "source").as[String]
        val targetUri = (request.body \ "target").as[String]
        val vocabulary = (request.body \ "vocabulary").as[String]
        val prefLabel = (request.body \ "prefLabel").as[String]
        datasetRepo.termRepo.addMapping(TermDb.TermMapping(sourceUri, targetUri, vocabulary, prefLabel))
        Ok("Mapping added")
      }
    }
  }

  def listSipFiles = Secure() {
    token => implicit request => {
      val fileNames = repo.listSipZip.map(_._1.getName)
      Ok(Json.obj("list" -> fileNames))
    }
  }

  val DATASET_PROPERTY_LISTS = List("status", "delimit", "namespaces", "harvest")

  private def toJsObject(datasetInfo: Elem, tag: String) = JsObject(
    (datasetInfo \ tag).head.child.filter(_.isInstanceOf[Elem]).map(
      n => n.label -> JsString(n.text)
    )
  )

}
