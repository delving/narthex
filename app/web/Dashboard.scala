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

package web

import dataset.DatasetOrigin.DROP
import dataset.DatasetState.{EMPTY, SOURCED}
import dataset.{DatasetDb, DatasetState}
import harvest.Harvesting
import harvest.Harvesting.HarvestType
import mapping.CategoryDb._
import mapping.SkosVocabulary._
import mapping.TermDb._
import mapping.{SkosRepo, SkosVocabulary}
import org.OrgRepo
import org.OrgRepo.repo
import org.apache.commons.io.FileUtils
import play.api.Logger
import play.api.Play.current
import play.api.cache.Cache
import play.api.libs.json._
import play.api.mvc._
import services.FileHandling.clearDir
import web.Application.OkFile

object Dashboard extends Controller with Security {

  val DATASET_PROPERTY_LISTS = List(
    "origin",
    "metadata",
    "status",
    "progress",
    "tree",
    "records",
    "publication",
    "categories",
    "progress",
    "delimit",
    "namespaces",
    "harvest",
    "harvestCron",
    "sipFacts",
    "sipHints"
  )

  def list = Secure() { token => implicit request =>
    val datasets = repo.repoDb.listDatasets.flatMap { dataset =>
      DatasetState.fromDatasetInfo(dataset.info).map { state: DatasetState =>
        if (state == DatasetState.DELETED) None else {
          val lists = DATASET_PROPERTY_LISTS.flatMap(name => DatasetDb.toJsObjectEntryOption(dataset.info, name))
          Some(Json.obj("name" -> dataset.name, "info" -> JsObject(lists)))
        }
      } getOrElse None
    }
    Ok(JsArray(datasets))
  }

  def datasetInfo(fileName: String) = Secure() { token => implicit request =>
    repo.datasetRepo(fileName).datasetDb.infoOption.map { info =>
      val lists = DATASET_PROPERTY_LISTS.flatMap(name => DatasetDb.toJsObjectEntryOption(info, name))
      Ok(JsObject(lists))
    } getOrElse NotFound(Json.obj("problem" -> s"Not found $fileName"))
  }

  def revert(fileName: String, command: String) = Secure() { token => implicit request =>
    val datasetRepo = repo.datasetRepo(fileName)
    val change = command match {
      case "interrupt" =>
        val interrupted = datasetRepo.interruptProgress
        s"Interrupted: $interrupted"
      case "tree" =>
        clearDir(datasetRepo.analyzedDir)
        datasetRepo.datasetDb.setTree(ready = false)
        "Tree removed"
      case "records" =>
        datasetRepo.recordDb.dropDb()
        datasetRepo.datasetDb.setRecords(ready = false)
        "Records removed"
      case "new" =>
        datasetRepo.datasetDb.createDataset(EMPTY)
        "New dataset created"
      case _ =>
        val revertedState = datasetRepo.revertState
        s"Reverted to $revertedState"
    }
    Ok(Json.obj("change" -> change))
  }

  def upload(fileName: String) = Secure(parse.multipartFormData) { token => implicit request =>
    request.body.file("file").map { file =>
      Logger.info(s"upload ${file.filename} (${file.contentType}) to $fileName")
      OrgRepo.acceptableFile(file.filename, file.contentType).map { suffix =>
        println(s"Acceptable ${file.filename}")
        val datasetRepo = repo.datasetRepo(s"$fileName$suffix")
        datasetRepo.datasetDb.setOrigin(DROP)
        datasetRepo.datasetDb.setStatus(SOURCED)
        file.ref.moveTo(datasetRepo.createIncomingFile(file.filename), replace = true)
        datasetRepo.startAnalysis()
        Ok(datasetRepo.name)
      } getOrElse NotAcceptable(Json.obj("problem" -> "File must be .xml or .xml.gz"))
    } getOrElse NotAcceptable(Json.obj("problem" -> "Missing file"))
  }

  def harvest(fileName: String) = Secure(parse.json) { token => implicit request =>
    def optional(tag: String) = (request.body \ tag).asOpt[String] getOrElse ""
    def required(tag: String) = (request.body \ tag).asOpt[String] getOrElse (throw new IllegalArgumentException(s"Missing $tag"))
    try {
      val datasetRepo = repo.datasetRepo(fileName)
      Logger.info(s"harvest ${required("url")} (${optional("dataset")}) to $fileName")
      HarvestType.fromString(required("harvestType")) map { harvestType =>
        val prefix = if (harvestType == HarvestType.PMH) required("prefix") else ""
        datasetRepo.firstHarvest(harvestType, required("url"), optional("dataset"), prefix)
        Ok
      } getOrElse {
        NotAcceptable(Json.obj("problem" -> s"unknown harvest type: ${optional("harvestType")}"))
      }
    } catch {
      case e: IllegalArgumentException =>
        NotAcceptable(Json.obj("problem" -> e.getMessage))
    }
  }

  def setHarvestCron(fileName: String) = Secure(parse.json) { token => implicit request =>
    def required(tag: String) = (request.body \ tag).asOpt[String] getOrElse (throw new IllegalArgumentException(s"Missing $tag"))
    try {
      val datasetRepo = repo.datasetRepo(fileName)
      val cron = Harvesting.harvestCron(required("previous"), required("delay"), required("unit"))
      Logger.info(s"harvest $cron")
      datasetRepo.datasetDb.setHarvestCron(cron)
      Ok
    } catch {
      case e: IllegalArgumentException =>
        NotAcceptable(Json.obj("problem" -> e.getMessage))
    }
  }

  def setMetadata(fileName: String) = Secure(parse.json) { token => implicit request =>
    try {
      val obj = request.body.as[JsObject]
      val meta: Map[String, String] = obj.value.map(nv => nv._1 -> nv._2.as[String]).toMap
      Logger.info(s"saveMetadata: $meta")
      val datasetRepo = repo.datasetRepo(fileName)
      datasetRepo.datasetDb.setMetadata(meta)
      Ok
    } catch {
      case e: IllegalArgumentException =>
        NotAcceptable(Json.obj("problem" -> e.getMessage))
    }
  }

  def setPublication(fileName: String) = Secure(parse.json) { token => implicit request =>
    def param(tag: String) = (request.body \ tag).asOpt[String] getOrElse "false"
    try {
      val datasetRepo = repo.datasetRepo(fileName)
      datasetRepo.datasetDb.setPublication(param("oaipmh"), param("index"), param("lod"))
      Ok
    } catch {
      case e: IllegalArgumentException =>
        NotAcceptable(Json.obj("problem" -> e.getMessage))
    }
  }

  def setCategories(fileName: String) = Secure(parse.json) { token => implicit request =>
    def param(tag: String) = (request.body \ tag).asOpt[String] getOrElse "false"
    try {
      val datasetRepo = repo.datasetRepo(fileName)
      datasetRepo.datasetDb.setCategories(param("included"))
      Ok
    } catch {
      case e: IllegalArgumentException =>
        NotAcceptable(Json.obj("problem" -> e.getMessage))
    }
  }

  def analyze(fileName: String) = Secure() { token => implicit request =>
    repo.datasetRepoOption(fileName) match {
      case Some(datasetRepo) =>
        datasetRepo.startAnalysis()
        Ok
      case None =>
        NotFound(Json.obj("problem" -> s"Not found $fileName"))
    }
  }

  def index(fileName: String) = Secure() { token => implicit request =>
    OkFile(repo.datasetRepo(fileName).index)
  }

  def nodeStatus(fileName: String, path: String) = Secure() { token => implicit request =>
    repo.datasetRepo(fileName).status(path) match {
      case None => NotFound(Json.obj("path" -> path))
      case Some(file) => OkFile(file)
    }
  }

  def sample(fileName: String, path: String, size: Int) = Secure() { token => implicit request =>
    repo.datasetRepo(fileName).sample(path, size) match {
      case None => NotFound(Json.obj("path" -> path, "size" -> size))
      case Some(file) => OkFile(file)
    }
  }

  def histogram(fileName: String, path: String, size: Int) = Secure() { token => implicit request =>
    repo.datasetRepo(fileName).histogram(path, size) match {
      case None => NotFound(Json.obj("path" -> path, "size" -> size))
      case Some(file) => OkFile(file)
    }
  }

  def setRecordDelimiter(fileName: String) = Secure(parse.json) { token => implicit request =>
    var recordRoot = (request.body \ "recordRoot").as[String]
    var uniqueId = (request.body \ "uniqueId").as[String]
    var recordCount = (request.body \ "recordCount").as[Int]
    val datasetRepo = repo.datasetRepo(fileName)
    datasetRepo.datasetDb.setRecordDelimiter(recordRoot, uniqueId, recordCount)
    println(s"store recordRoot=$recordRoot uniqueId=$uniqueId recordCount=$recordCount")
    Ok
  }

  def saveRecords(fileName: String) = Secure() { token => implicit request =>
    val datasetRepo = repo.datasetRepo(fileName)
    datasetRepo.firstSaveRecords()
    Ok
  }

  def queryRecords(fileName: String) = Secure(parse.json) { token => implicit request =>
    val path = (request.body \ "path").as[String]
    val value = (request.body \ "value").as[String]
    val datasetRepo = repo.datasetRepo(fileName)
    val recordsString = datasetRepo.recordDb.recordsWithValue(path, value)
    val enrichedRecords = datasetRepo.enrichRecords(recordsString)
    val result = enrichedRecords.map(rec => rec.text).mkString("\n")
    Ok(result)
  }

  def listSheets = Secure() { token => implicit request =>
    Ok(Json.obj("sheets" -> repo.categoriesRepo.listSheets))
  }

  def sheet(fileName: String) = Action(parse.anyContent) { implicit request =>
    OkFile(repo.categoriesRepo.sheet(fileName))
  }

  def listSkos = Secure() { token => implicit request =>
    Ok(Json.obj("list" -> SkosRepo.repo.listFiles))
  }

  def searchSkos(name: String, sought: String) = Secure() { token => implicit request =>
    def searchVocabulary(vocabulary: SkosVocabulary): LabelSearch = vocabulary.search("dut", sought, 25)
    Cache.getAs[SkosVocabulary](name) map {
      vocabulary => Ok(Json.obj("search" -> searchVocabulary(vocabulary)))
    } getOrElse {
      val vocabulary = SkosRepo.repo.vocabulary(name)
      Cache.set(name, vocabulary, CACHE_EXPIRATION)
      Ok(Json.obj("search" -> searchVocabulary(vocabulary)))
    }
  }

  def getTermSourcePaths(fileName: String) = Secure() { token => implicit request =>
    val datasetRepo = repo.datasetRepo(fileName)
    val sourcePaths = datasetRepo.termDb.getSourcePaths
    Ok(Json.obj("sourcePaths" -> sourcePaths))
  }

  def getTermMappings(fileName: String) = Secure() { token => implicit request =>
    val datasetRepo = repo.datasetRepo(fileName)
    val mappings: scala.Seq[TermMapping] = datasetRepo.termDb.getMappings
    Ok(Json.obj("mappings" -> mappings))
  }

  def setTermMapping(fileName: String) = Secure(parse.json) { token => implicit request =>
    val datasetRepo = repo.datasetRepo(fileName)
    datasetRepo.invalidateEnrichmentCache()
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
      datasetRepo.termDb.addMapping(TermMapping(sourceUri, targetUri, vocabulary, prefLabel))
      Ok("Mapping added")
    }
  }

  def getCategoryList = Secure() { token => implicit request =>
    repo.categoriesRepo.categoryListOption.map { list =>
      Ok(Json.toJson(list))
    } getOrElse {
      NotFound(Json.obj("problem" -> "No category file"))
    }
  }

  def gatherCategoryCounts  = Secure() { token => implicit request =>
    Logger.info("Gather category counts!! (NOT IMPLEMENTED)")
    repo.startCategoryCounts()
    Ok
  }

  def getCategorySourcePaths(fileName: String) = Secure() { token => implicit request =>
    val datasetRepo = repo.datasetRepo(fileName)
    val sourcePaths = datasetRepo.categoryDb.getSourcePaths
    Ok(Json.obj("sourcePaths" -> sourcePaths))
  }

  def getCategoryMappings(fileName: String) = Secure() { token => implicit request =>
    val datasetRepo = repo.datasetRepo(fileName)
    val mappings: Seq[CategoryMapping] = datasetRepo.categoryDb.getMappings
    Ok(Json.obj("mappings" -> mappings))
  }

  def setCategoryMapping(fileName: String) = Secure(parse.json) { token => implicit request =>
    val datasetRepo = repo.datasetRepo(fileName)
    val categoryMapping = CategoryMapping(
      (request.body \ "source").as[String],
      Seq((request.body \ "category").as[String])
    )
    val member = (request.body \ "member").as[Boolean]
    datasetRepo.categoryDb.setMapping(categoryMapping, member)
    Ok("Mapping "+ (if (member) "added" else "removed"))
  }

  def listSipFiles = Secure() { token => implicit request =>
    val fileNames = repo.listSipZips.map(_.toString)
    Ok(Json.obj("list" -> fileNames))
  }

  def deleteSipFile(fileName: String) = Secure() { token => implicit request =>
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
