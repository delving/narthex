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

import java.util.concurrent.TimeUnit

import akka.pattern.{AskTimeoutException, ask}
import akka.util.Timeout
import dataset.DatasetActor._
import dataset._
import harvest.Harvesting
import harvest.Harvesting.HarvestType._
import mapping.CategoryDb._
import mapping.TermDb._
import org.OrgActor
import org.OrgActor.DatasetMessage
import org.OrgRepo.repo
import org.apache.commons.io.FileUtils
import org.joda.time.DateTime
import play.api.Logger
import play.api.libs.concurrent.Execution.Implicits._
import play.api.libs.json._
import play.api.mvc._
import thesaurus.ThesaurusDb._
import web.MainController.OkFile

import scala.concurrent.Future

object AppController extends Controller with Security {

  implicit val timeout = Timeout(500, TimeUnit.MILLISECONDS)

  val DATASET_PROPERTY_LISTS = List(
    "character",
    "metadata",
    "status",
    "error",
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

  def listDatasets = Secure() { profile => implicit request =>
    val datasets = repo.orgDb.listDatasets.flatMap { dataset =>
      val state = DatasetState.datasetStateFromInfo(dataset.info)
      val lists = DATASET_PROPERTY_LISTS.flatMap(name => DatasetDb.toJsObjectEntryOption(dataset.info, name))
      Some(Json.obj("name" -> dataset.datasetName, "info" -> JsObject(lists)))
    }
    Ok(JsArray(datasets))
  }

  def listPrefixes = Secure() { profile => implicit request =>
    val prefixes = repo.sipFactory.prefixRepos.map(_.prefix)
    Ok(Json.toJson(prefixes))
  }

  def datasetInfo(datasetName: String) = Secure() { profile => implicit request =>
    repo.datasetRepo(datasetName).datasetDb.infoOpt.map { info =>
      val lists = DATASET_PROPERTY_LISTS.flatMap(name => DatasetDb.toJsObjectEntryOption(info, name))
      Ok(JsObject(lists))
    } getOrElse NotFound(Json.obj("problem" -> s"Not found $datasetName"))
  }

  def create(datasetName: String, prefix: String) = Secure() { profile => implicit request =>
    repo.datasetRepo(datasetName).datasetDb.createDataset(prefix)
    Ok(Json.obj("created" -> s"Dataset $datasetName with prefix $prefix"))
  }

  def datasetProgress(datasetName: String) = SecureAsync() { profile => implicit request =>
    val replyData = (OrgActor.actor ? DatasetMessage(datasetName, CheckState, question = true)).mapTo[DatasetActorData]
    replyData.map {
      case Dormant =>
        Ok(Json.obj(
          "progressType" -> ProgressType.TYPE_IDLE.name
        ))
      case Active(_, progressState, progressType, count) =>
        Ok(Json.obj(
          "progressState" -> progressState.name,
          "progressType" -> progressType.name,
          "count" -> count
        ))
      case InError(message) =>
        Ok(Json.obj(
          "progressType" -> ProgressType.TYPE_IDLE.name,
          "errorMessage" -> message
        ))
    } recover {
      case t: AskTimeoutException =>
        Ok(Json.obj(
          "progressType" -> ProgressType.TYPE_IDLE.name,
          "errorMessage" -> "actor didn't answer"
        ))
    }
  }

  def command(datasetName: String, command: String) = SecureAsync() { profile => implicit request =>
    if (command == "interrupt") {
      OrgActor.actor ! DatasetMessage(datasetName, InterruptWork)
      Future(Ok("interrupt sent"))
    }
    else {
      val replyString = (OrgActor.actor ? DatasetMessage(datasetName, Command(command), question = true)).mapTo[String]
      replyString.map { reply =>
        Ok(Json.obj("reply" -> reply))
      } recover {
        case t: AskTimeoutException =>
          Ok(Json.obj("reply" -> "there was no reply"))
      }
    }
  }

  def upload(datasetName: String) = Secure(parse.multipartFormData) { profile => implicit request =>
    repo.datasetRepoOption(datasetName).map { datasetRepo =>
      request.body.file("file").map { file =>
        val error = datasetRepo.acceptUpload(file.filename, { target =>
          file.ref.moveTo(target, replace = true)
          Logger.info(s"Dropped file ${file.filename} on $datasetName: ${target.getAbsolutePath}")
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

  def harvest(datasetName: String) = Secure(parse.json) { profile => implicit request =>
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

  def setHarvestCron(datasetName: String) = Secure(parse.json) { profile => implicit request =>
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

  def setMetadata(datasetName: String) = Secure(parse.json) { profile => implicit request =>
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

  def setPublication(datasetName: String) = Secure(parse.json) { profile => implicit request =>
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

  def setCategories(datasetName: String) = Secure(parse.json) { profile => implicit request =>
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

  def index(datasetName: String) = Secure() { profile => implicit request =>
    OkFile(repo.datasetRepo(datasetName).index)
  }

  def nodeStatus(datasetName: String, path: String) = Secure() { profile => implicit request =>
    repo.datasetRepo(datasetName).status(path) match {
      case None => NotFound(Json.obj("path" -> path))
      case Some(file) => OkFile(file)
    }
  }

  def sample(datasetName: String, path: String, size: Int) = Secure() { profile => implicit request =>
    repo.datasetRepo(datasetName).sample(path, size) match {
      case None => NotFound(Json.obj("path" -> path, "size" -> size))
      case Some(file) => OkFile(file)
    }
  }

  def histogram(datasetName: String, path: String, size: Int) = Secure() { profile => implicit request =>
    repo.datasetRepo(datasetName).histogram(path, size) match {
      case None => NotFound(Json.obj("path" -> path, "size" -> size))
      case Some(file) => OkFile(file)
    }
  }

  def setRecordDelimiter(datasetName: String) = Secure(parse.json) { profile => implicit request =>
    var recordRoot = (request.body \ "recordRoot").as[String]
    var uniqueId = (request.body \ "uniqueId").as[String]
    repo.datasetRepoOption(datasetName).map { datasetRepo =>
      datasetRepo.setRawDelimiters(recordRoot, uniqueId)
      Ok
    } getOrElse {
      NotFound(Json.obj("problem" -> "Dataset not found"))
    }
  }

  def listSheets = Secure() { profile => implicit request =>
    Ok(Json.obj("sheets" -> repo.categoriesRepo.listSheets))
  }

  def sheet(datasetName: String) = Action(parse.anyContent) { implicit request =>
    OkFile(repo.categoriesRepo.sheet(datasetName))
  }

  def listConceptSchemes = Secure() { profile => implicit request =>
    Ok(Json.obj("list" -> repo.skosRepo.conceptSchemes.map(_.name)))
  }

  def searchConceptScheme(conceptSchemeName: String, sought: String) = Secure() { profile => implicit request =>
    val schemeOpt = repo.skosRepo.conceptSchemes.find(scheme => conceptSchemeName == scheme.name)
    schemeOpt.map { scheme =>
      val nonemptySought = if (sought == "-") "" else sought
      val search = scheme.search("nl", nonemptySought, 25)
      Ok(Json.obj("search" -> search))
    } getOrElse {
      NotFound(Json.obj("problem" -> s"No concept scheme named '$conceptSchemeName' found."))
    }
  }

  def getTermSourcePaths(datasetName: String) = Secure() { profile => implicit request =>
    val datasetRepo = repo.datasetRepo(datasetName)
    val sourcePaths = datasetRepo.termDb.getSourcePaths
    Ok(Json.obj("sourcePaths" -> sourcePaths))
  }

  def getTermMappings(datasetName: String) = Secure() { profile => implicit request =>
    val datasetRepo = repo.datasetRepo(datasetName)
    val mappings: scala.Seq[TermMapping] = datasetRepo.termDb.getMappings
    Ok(Json.obj("mappings" -> mappings))
  }

  def setTermMapping(datasetName: String) = Secure(parse.json) { profile => implicit request =>
    val datasetRepo = repo.datasetRepo(datasetName)
    datasetRepo.invalidateEnrichmentCache()

    println(s"body: ${request.body}")

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
        attributionName = (request.body \ "attributionName").as[String],
        prefLabel = (request.body \ "prefLabel").as[String],
        who = profile.email,
        when = new DateTime()
      )
      datasetRepo.termDb.addMapping(termMapping)
      Ok("Mapping added")
    }
  }

  def getThesaurusMappings(conceptSchemeA: String, conceptSchemeB: String) = Secure() { profile => implicit request =>
    val thesaurusDb = repo.thesaurusDb(conceptSchemeA, conceptSchemeB)
    val mappings = thesaurusDb.getMappings
    Ok(Json.obj("mappings" -> mappings))
  }

  def setThesaurusMapping(conceptSchemeA: String, conceptSchemeB: String) = Secure(parse.json) { profile => implicit request =>
    val thesaurusDb = repo.thesaurusDb(conceptSchemeA, conceptSchemeB)
    val termMapping = ThesaurusMapping(
      uriA = (request.body \ "uriA").as[String],
      uriB = (request.body \ "uriB").as[String],
      who = profile.email,
      when = new DateTime()
    )
    val added = thesaurusDb.toggleMapping(termMapping)
    Ok(Json.obj("action" -> (if (added) "added" else "removed")))
  }

  def getCategoryList = Secure() { profile => implicit request =>
    repo.categoriesRepo.categoryListOption.map { list =>
      Ok(Json.toJson(list))
    } getOrElse {
      Ok(Json.obj("message" -> "No category file"))
    }
  }

  def gatherCategoryCounts = Secure() { profile => implicit request =>
    repo.startCategoryCounts()
    Ok
  }

  def getCategorySourcePaths(datasetName: String) = Secure() { profile => implicit request =>
    val datasetRepo = repo.datasetRepo(datasetName)
    val sourcePaths = datasetRepo.categoryDb.getSourcePaths
    Ok(Json.obj("sourcePaths" -> sourcePaths))
  }

  def getCategoryMappings(datasetName: String) = Secure() { profile => implicit request =>
    val datasetRepo = repo.datasetRepo(datasetName)
    val mappings: Seq[CategoryMapping] = datasetRepo.categoryDb.getMappings
    Ok(Json.obj("mappings" -> mappings))
  }

  def setCategoryMapping(datasetName: String) = Secure(parse.json) { profile => implicit request =>
    val datasetRepo = repo.datasetRepo(datasetName)
    val categoryMapping = CategoryMapping(
      (request.body \ "source").as[String],
      Seq((request.body \ "category").as[String])
    )
    val member = (request.body \ "member").as[Boolean]
    datasetRepo.categoryDb.setMapping(categoryMapping, member)
    Ok("Mapping " + (if (member) "added" else "removed"))
  }

  def listSipFiles(datasetName: String) = Secure() { profile => implicit request =>
    val datasetRepo = repo.datasetRepo(datasetName)
    val fileNames = datasetRepo.sipRepo.listSips.map(_.file.getName)
    Ok(Json.obj("list" -> fileNames))
  }

  def deleteLatestSipFile(datasetName: String) = Secure() { profile => implicit request =>
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
