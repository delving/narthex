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
import org.apache.commons.io.FileUtils
import play.api.Logger
import play.api.Play.current
import play.api.cache.Cache
import play.api.libs.json._
import play.api.mvc._
import services.DatasetState._
import services.Repo.repo
import services._

object Dashboard extends Controller with Security with TreeHandling with SkosJson {

  def list = Secure() {
    token => implicit request => {
      val datasets = repo.repoDb.listDatasets.map {
        dataset =>
          val lists = DATASET_PROPERTY_LISTS.map(name => name -> DatasetDb.toJsObject(dataset.info, name))
          Json.obj("name" -> dataset.name, "info" -> JsObject(lists))
      }
      Ok(JsArray(datasets))
    }
  }

  def upload(fileName: String) = Secure(parse.multipartFormData) {
    token => implicit request => {
      request.body.file("file") match {
        case Some(file) =>
          Logger.info(s"upload ${file.filename} (${file.contentType}) to $fileName")
          RepoUtil.acceptableFile(file.filename, file.contentType) match {
            case Some(suffix) =>
              println(s"Acceptable ${file.filename}")
              val datasetRepo = repo.datasetRepo(s"$fileName$suffix")
              file.ref.moveTo(datasetRepo.source, replace = true)
              datasetRepo.datasetDb.createDataset(DatasetState.READY)
              datasetRepo.datasetDb.setOrigin(DatasetOrigin.DROP, "?") // find out who
              Ok(datasetRepo.name)
            case None =>
              println(s"NOT Acceptable ${file.filename}")
              NotAcceptable(Json.obj("problem" -> "File must be .xml or .xml.gz"))
          }
        case None =>
          NotAcceptable(Json.obj("problem" -> "Missing file"))
      }
    }
  }

  def harvest(fileName: String) = Secure(parse.json) {
    token => implicit request => {
      def optional(tag: String) = (request.body \ tag).asOpt[String] getOrElse ""
      def required(tag: String) = (request.body \ tag).asOpt[String] getOrElse (throw new IllegalArgumentException(s"Missing $tag"))
      try {
        val datasetRepo = repo.datasetRepo(fileName)
        Logger.info(s"harvest ${required("url")} (${optional("dataset")}) to $fileName")
        required("harvestType") match {
          case "pmh" =>
            datasetRepo.startPmhHarvest(required("url"), optional("dataset"), required("prefix"))
          case "adlib" =>
            datasetRepo.startAdLibHarvest(required("url"), required("dataset"))
          case _ =>
            throw new IllegalArgumentException("Missing harvestType [pmh,adlib]")
        }
        Ok
      } catch {
        case e: IllegalArgumentException =>
          NotAcceptable(Json.obj("problem" -> e.getMessage))
      }
    }
  }

  def setMetadata(fileName: String) = Secure(parse.json) {
    token => implicit request => {
      try {
        val obj = request.body.as[JsObject]
        val meta: Map[String,String] = obj.value.map(nv => nv._1 -> nv._2.as[String]).toMap
        Logger.info(s"saveMetadata: $meta")
        val datasetRepo = repo.datasetRepo(fileName)
        datasetRepo.datasetDb.setMetadata(meta)
        Ok
      } catch {
        case e: IllegalArgumentException =>
          NotAcceptable(Json.obj("problem" -> e.getMessage))
      }
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
          val lists = DATASET_PROPERTY_LISTS.map(name => name -> DatasetDb.toJsObject(datasetInfo, name))
          Ok(JsObject(lists))
        case None =>
          NotFound(Json.obj("problem" -> s"Not found $fileName"))
      }
    }
  }

  def goToState(fileName: String, state: String) = Secure() {
    token => implicit request => {
      fromString(state) match {
        case Some(datasetState) =>
          if (datasetState == DatasetState.EMPTY) {
            repo.datasetRepoOption(fileName) map {
              datasetRepo =>
                if (datasetRepo.goToState(datasetState))
                  Ok(Json.obj("state" -> datasetState.toString))
                else
                  NotAcceptable(Json.obj("problem" -> "Cannot revert to empty"))
            } getOrElse {
              repo.datasetRepo(fileName).datasetDb.createDataset(datasetState)
              Ok(Json.obj("state" -> datasetState.toString))
            }
          }
          else {
            val datasetRepo = repo.datasetRepo(fileName)
            if (datasetRepo.goToState(datasetState))
              Ok(Json.obj("state" -> datasetState.toString))
            else
              NotAcceptable(Json.obj("problem" -> s"Cannot revert to nonempty state $datasetState"))
          }

        case None =>
          NotAcceptable(Json.obj("problem" -> s"Cannot find state $state"))
      }
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
      datasetRepo.saveRecords()
      Ok
    }
  }

  def queryRecords(fileName: String) = Secure(parse.json) {
    token => implicit request => {
      val path = (request.body \ "path").as[String]
      val value = (request.body \ "value").as[String]
      val datasetRepo = repo.datasetRepo(fileName)
      val recordsString = datasetRepo.recordDb.recordsWithValue(path, value)
      val enrichedRecords = datasetRepo.enrichRecords(recordsString)
      val result = enrichedRecords.map(rec => rec.text).mkString("\n")
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
      val mappings: scala.Seq[TermDb.TermMapping] = datasetRepo.termDb.getMappings
      Ok(Json.obj("mappings" -> mappings))
    }
  }

  def getSourcePaths(fileName: String) = Secure() {
    token => implicit request => {
      val datasetRepo = repo.datasetRepo(fileName)
      val sourcePaths = datasetRepo.termDb.getSourcePaths
      Ok(Json.obj("sourcePaths" -> sourcePaths))
    }
  }

  def setMapping(fileName: String) = Secure(parse.json) {
    token => implicit request => {
      val datasetRepo = repo.datasetRepo(fileName)
      datasetRepo.invalidateEnrichementCache()
      if ((request.body \ "remove").asOpt[String].isDefined) {
        val sourceUri = (request.body \ "source").as[String]
        datasetRepo.termDb.removeMapping(sourceUri)
        Ok("Mapping removed")
      }
      else {
        val sourceUri = (request.body \ "source").as[String]
        val targetUri = (request.body \ "target").as[String]
        val vocabulary = (request.body \ "vocabulary").as[String]
        val prefLabel = (request.body \ "prefLabel").as[String]
        datasetRepo.termDb.addMapping(TermDb.TermMapping(sourceUri, targetUri, vocabulary, prefLabel))
        Ok("Mapping added")
      }
    }
  }

  def listSipFiles = Secure() {
    token => implicit request => {
      val fileNames = repo.listSipZips.map(_.toString)
      Ok(Json.obj("list" -> fileNames))
    }
  }

  def deleteSipFile(fileName: String) = Secure() {
    token => implicit request => {
      repo.listSipZips.find(_.zipFile.getName == fileName) match {
        case Some(sipZip) =>
          FileUtils.deleteQuietly(sipZip.zipFile)
          FileUtils.deleteQuietly(sipZip.factsFile)
          FileUtils.deleteQuietly(sipZip.hintsFile)
          Ok(Json.obj("deleted" -> sipZip.toString))
        case None =>
          NotFound(Json.obj("problem" -> s"Could not delete $fileName"))
      }
    }
  }

  val DATASET_PROPERTY_LISTS = List("origin", "metadata", "status", "delimit", "namespaces", "harvest", "sipFacts", "sipHints")

}
