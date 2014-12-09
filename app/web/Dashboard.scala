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

import dataset._
import harvest.Harvesting
import harvest.Harvesting.HarvestType._
import mapping.CategoryDb._
import mapping.TermDb._
import org.OrgRepo.repo
import org.apache.commons.io.FileUtils
import org.joda.time.DateTime
import play.api.Logger
import play.api.libs.json._
import play.api.mvc._
import web.Application.OkFile

object Dashboard extends Controller with Security {

  val DATASET_PROPERTY_LISTS = List(
    "character",
    "metadata",
    "status",
    "progress",
    "tree",
    "source",
    "records",
    "publication",
    "categories",
    "namespaces",
    "harvest",
    "harvestCron",
    "sipFacts",
    "sipHints"
  )

  def listDatasets = Secure() { email => implicit request =>
    val datasets = repo.orgDb.listDatasets.flatMap { dataset =>
      val state = DatasetState.datasetStateFromInfo(dataset.info)
      val lists = DATASET_PROPERTY_LISTS.flatMap(name => DatasetDb.toJsObjectEntryOption(dataset.info, name))
      Some(Json.obj("name" -> dataset.datasetName, "info" -> JsObject(lists)))
    }
    Ok(JsArray(datasets))
  }

  def listPrefixes = Secure() { email => implicit request =>
    val prefixes = repo.sipFactory.prefixRepos.map(_.prefix)
    Ok(Json.toJson(prefixes))
  }

  def datasetInfo(datasetName: String) = Secure() { email => implicit request =>
    repo.datasetRepo(datasetName).datasetDb.infoOpt.map { info =>
      val lists = DATASET_PROPERTY_LISTS.flatMap(name => DatasetDb.toJsObjectEntryOption(info, name))
      Ok(JsObject(lists))
    } getOrElse NotFound(Json.obj("problem" -> s"Not found $datasetName"))
  }

  def create(datasetName: String, prefix: String) = Secure() { email => implicit request =>
    repo.datasetRepo(datasetName).datasetDb.createDataset(prefix)
    Ok(Json.obj("created" -> s"Dataset $datasetName with prefix $prefix"))
  }

  def command(datasetName: String, command: String) = Secure() { email => implicit request =>
    repo.datasetRepoOption(datasetName).map { datasetRepo =>
      Ok(datasetRepo.receiveCommand(command))
    } getOrElse {
      NotFound
    }
  }

  def upload(datasetName: String) = Secure(parse.multipartFormData) { email => implicit request =>
    repo.datasetRepoOption(datasetName).map { datasetRepo =>
      request.body.file("file").map { file =>
        val error = datasetRepo.acceptUpload(file.filename, { target =>
          file.ref.moveTo(target, replace = true)
          Logger.info(s"Dropped file ${file.filename} on $datasetName: ${target.getAbsolutePath}")
          datasetRepo.datasetDb.setProgress(ProgressState.ADOPTING, ProgressType.PERCENT, 1)
          target
        })
        error.map(message => NotAcceptable(Json.obj("problem" -> message))).getOrElse(Ok)
      } getOrElse {
        NotAcceptable(Json.obj("problem" -> "Cannot find file in upload"))
      }
    } getOrElse {
      NotAcceptable(Json.obj("problem" -> s"Cannot find dataset $datasetName"))
    }
  }

  def harvest(datasetName: String) = Secure(parse.json) { email => implicit request =>
    def optional(tag: String) = (request.body \ tag).asOpt[String] getOrElse ""
    def required(tag: String) = (request.body \ tag).asOpt[String] getOrElse (throw new IllegalArgumentException(s"Missing $tag"))
    try {
      val datasetRepo = repo.datasetRepo(datasetName)
      Logger.info(s"harvest ${required("url")} (${optional("dataset")}) to $datasetName")
      harvestTypeFromString(required("harvestType")) map { harvestType =>
        val prefix = harvestType match {
          case PMH => required("prefix")
          case PMH_REC => required("prefix")
          case ADLIB => ADLIB.name
        }
        val error = datasetRepo.firstHarvest(harvestType, required("url"), optional("dataset"), prefix)
        error.map(message => NotAcceptable(Json.obj("problem" -> message))).getOrElse(Ok)
      } getOrElse {
        NotAcceptable(Json.obj("problem" -> s"unknown harvest type: ${optional("harvestType")}"))
      }
    } catch {
      case e: IllegalArgumentException =>
        NotAcceptable(Json.obj("problem" -> e.getMessage))
    }
  }

  def setHarvestCron(datasetName: String) = Secure(parse.json) { email => implicit request =>
    def required(tag: String) = (request.body \ tag).asOpt[String] getOrElse (throw new IllegalArgumentException(s"Missing $tag"))
    try {
      val datasetRepo = repo.datasetRepo(datasetName)
      val cron = Harvesting.harvestCron(required("previous"), required("delay"), required("unit"))
      Logger.info(s"harvest $cron")
      datasetRepo.datasetDb.setHarvestCron(cron)
      Ok
    } catch {
      case e: IllegalArgumentException =>
        NotAcceptable(Json.obj("problem" -> e.getMessage))
    }
  }

  def setMetadata(datasetName: String) = Secure(parse.json) { email => implicit request =>
    try {
      val obj = request.body.as[JsObject]
      val meta: Map[String, String] = obj.value.map(nv => nv._1 -> nv._2.as[String]).toMap
      Logger.info(s"saveMetadata: $meta")
      val datasetRepo = repo.datasetRepo(datasetName)
      datasetRepo.datasetDb.setMetadata(meta)
      Ok
    } catch {
      case e: IllegalArgumentException =>
        NotAcceptable(Json.obj("problem" -> e.getMessage))
    }
  }

  def setPublication(datasetName: String) = Secure(parse.json) { email => implicit request =>
    def boolParam(tag: String) = (request.body \ tag).asOpt[String] getOrElse "false"
    def stringParam(tag: String) = (request.body \ tag).asOpt[String] getOrElse ""
    try {
      val datasetRepo = repo.datasetRepo(datasetName)
      datasetRepo.datasetDb.setPublication(boolParam("oaipmh"), boolParam("index"), boolParam("lod"))
      Ok
    } catch {
      case e: IllegalArgumentException =>
        NotAcceptable(Json.obj("problem" -> e.getMessage))
    }
  }

  def setCategories(datasetName: String) = Secure(parse.json) { email => implicit request =>
    def param(tag: String) = (request.body \ tag).asOpt[String] getOrElse "false"
    try {
      val datasetRepo = repo.datasetRepo(datasetName)
      datasetRepo.datasetDb.setCategories(param("included"))
      Ok
    } catch {
      case e: IllegalArgumentException =>
        NotAcceptable(Json.obj("problem" -> e.getMessage))
    }
  }

  def analyze(datasetName: String) = Secure() { email => implicit request =>
    repo.datasetRepoOption(datasetName) match {
      case Some(datasetRepo) =>
        datasetRepo.startAnalysis()
        Ok
      case None =>
        NotFound(Json.obj("problem" -> s"Not found $datasetName"))
    }
  }

  def index(datasetName: String) = Secure() { email => implicit request =>
    OkFile(repo.datasetRepo(datasetName).index)
  }

  def nodeStatus(datasetName: String, path: String) = Secure() { email => implicit request =>
    repo.datasetRepo(datasetName).status(path) match {
      case None => NotFound(Json.obj("path" -> path))
      case Some(file) => OkFile(file)
    }
  }

  def sample(datasetName: String, path: String, size: Int) = Secure() { email => implicit request =>
    repo.datasetRepo(datasetName).sample(path, size) match {
      case None => NotFound(Json.obj("path" -> path, "size" -> size))
      case Some(file) => OkFile(file)
    }
  }

  def histogram(datasetName: String, path: String, size: Int) = Secure() { email => implicit request =>
    repo.datasetRepo(datasetName).histogram(path, size) match {
      case None => NotFound(Json.obj("path" -> path, "size" -> size))
      case Some(file) => OkFile(file)
    }
  }

  def setRecordDelimiter(datasetName: String) = Secure(parse.json) { email => implicit request =>
    var recordRoot = (request.body \ "recordRoot").as[String]
    var uniqueId = (request.body \ "uniqueId").as[String]
    repo.datasetRepoOption(datasetName).map { datasetRepo =>
      datasetRepo.setRawDelimiters(recordRoot, uniqueId)
      Ok
    } getOrElse {
      NotFound(Json.obj("problem" -> "Dataset not found"))
    }
  }

  def saveRecords(datasetName: String) = Secure() { email => implicit request =>
    val datasetRepo = repo.datasetRepo(datasetName)
    datasetRepo.firstSaveRecords()
    Ok
  }

  def queryRecords(datasetName: String) = Secure(parse.json) { email => implicit request =>
    val path = (request.body \ "path").as[String]
    val value = (request.body \ "value").as[String]
    val datasetRepo = repo.datasetRepo(datasetName)
    datasetRepo.recordDbOpt.map { recordDb =>
      val recordsString = recordDb.recordsWithValue(path, value)
      val enrichedRecords = datasetRepo.enrichRecords(recordsString)
      val result = enrichedRecords.map(rec => rec.text).mkString("\n")
      Ok(result)
    } getOrElse {
      NotFound(Json.obj("problem" -> "No record database found"))
    }
  }

  def listSheets = Secure() { email => implicit request =>
    Ok(Json.obj("sheets" -> repo.categoriesRepo.listSheets))
  }

  def sheet(datasetName: String) = Action(parse.anyContent) { implicit request =>
    OkFile(repo.categoriesRepo.sheet(datasetName))
  }

  def listConceptSchemes = Secure() { email => implicit request =>
    Ok(Json.obj("list" -> repo.skosRepo.conceptSchemes.map(_.name)))
  }

  def searchConceptScheme(conceptSchemeName: String, sought: String) = Secure() { email => implicit request =>
    val schemeOpt = repo.skosRepo.conceptSchemes.find(scheme => conceptSchemeName == scheme.name)
    schemeOpt.map{scheme =>
      val search = scheme.search("dut", sought, 25)
      Ok(Json.obj("search" -> search))
    } getOrElse {
      NotFound(Json.obj("problem" -> s"No concept scheme named '$conceptSchemeName' found."))
    }
  }

  def getTermSourcePaths(datasetName: String) = Secure() { email => implicit request =>
    val datasetRepo = repo.datasetRepo(datasetName)
    val sourcePaths = datasetRepo.termDb.getSourcePaths
    Ok(Json.obj("sourcePaths" -> sourcePaths))
  }

  def getTermMappings(datasetName: String) = Secure() { email => implicit request =>
    val datasetRepo = repo.datasetRepo(datasetName)
    val mappings: scala.Seq[TermMapping] = datasetRepo.termDb.getMappings
    Ok(Json.obj("mappings" -> mappings))
  }

  def setTermMapping(datasetName: String) = Secure(parse.json) { email => implicit request =>
    val datasetRepo = repo.datasetRepo(datasetName)
    datasetRepo.invalidateEnrichmentCache()
    if ((request.body \ "remove").asOpt[String].isDefined) {
      val sourceUri = (request.body \ "sourceURI").as[String]
      datasetRepo.termDb.removeMapping(sourceUri)
      Ok("Mapping removed")
    }
    else {
      val termMapping = TermMapping(
        sourceURI = (request.body \ "sourceURI").as[String],
        targetURI = (request.body \ "targetURI").as[String],
        conceptScheme = (request.body \ "conceptScheme").as[String],
        prefLabel = (request.body \ "prefLabel").as[String],
        who = email,
        when = new DateTime()
      )
      datasetRepo.termDb.addMapping(termMapping)
      Ok("Mapping added")
    }
  }

  def getCategoryList = Secure() { email => implicit request =>
    repo.categoriesRepo.categoryListOption.map { list =>
      Ok(Json.toJson(list))
    } getOrElse {
      Ok(Json.obj("message" -> "No category file"))
    }
  }

  def gatherCategoryCounts = Secure() { email => implicit request =>
    repo.startCategoryCounts()
    Ok
  }

  def getCategorySourcePaths(datasetName: String) = Secure() { email => implicit request =>
    val datasetRepo = repo.datasetRepo(datasetName)
    val sourcePaths = datasetRepo.categoryDb.getSourcePaths
    Ok(Json.obj("sourcePaths" -> sourcePaths))
  }

  def getCategoryMappings(datasetName: String) = Secure() { email => implicit request =>
    val datasetRepo = repo.datasetRepo(datasetName)
    val mappings: Seq[CategoryMapping] = datasetRepo.categoryDb.getMappings
    Ok(Json.obj("mappings" -> mappings))
  }

  def setCategoryMapping(datasetName: String) = Secure(parse.json) { email => implicit request =>
    val datasetRepo = repo.datasetRepo(datasetName)
    val categoryMapping = CategoryMapping(
      (request.body \ "source").as[String],
      Seq((request.body \ "category").as[String])
    )
    val member = (request.body \ "member").as[Boolean]
    datasetRepo.categoryDb.setMapping(categoryMapping, member)
    Ok("Mapping " + (if (member) "added" else "removed"))
  }

  def listSipFiles(datasetName: String) = Secure() { email => implicit request =>
    val datasetRepo = repo.datasetRepo(datasetName)
    val fileNames = datasetRepo.sipRepo.listSips.map(_.file.getName)
    Ok(Json.obj("list" -> fileNames))
  }

  def deleteLatestSipFile(datasetName: String) = Secure() { email => implicit request =>
    val datasetRepo = repo.datasetRepo(datasetName)
    val sips = datasetRepo.sipRepo.listSips
    if (sips.size < 2) {
      NotFound(Json.obj("problem" -> s"Refusing to delete the last SIP file $datasetName"))
    }
    else {
      FileUtils.deleteQuietly(sips.head.file)
      Ok(Json.obj("deleted" -> sips.head.file.getName))
    }
  }
}
