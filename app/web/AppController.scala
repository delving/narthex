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

import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream

import akka.actor._
import akka.stream.Materializer
import akka.util.Timeout
import dataset.DatasetActor._
import dataset.DsInfo
import dataset.DsInfo._
import harvest.Harvesting.HarvestType.harvestTypeFromString
import mapping.SkosMappingStore.SkosMapping
import mapping.SkosVocabulary._
import mapping.VocabInfo
import mapping.VocabInfo._
import nl.grons.metrics.scala.DefaultInstrumented
import organization.OrgActor.DatasetMessage
import org.apache.commons.io.FileUtils
import org.joda.time.DateTime
import organization.OrgContext
import play.api.Logger
import play.api.cache.CacheApi
import play.api.libs.concurrent.Execution.Implicits._
import play.api.libs.json._
import play.api.mvc._
import services.Temporal._
import triplestore.GraphProperties._
import triplestore.{Sparql, TripleStore}
import triplestore.Sparql.SkosifiedField

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

class AppController(val cacheApi: CacheApi, val orgContext: OrgContext, val sessionTimeoutInSeconds: Int)
                   (implicit val ts: TripleStore, implicit val actorSystem: ActorSystem,
                    implicit val materializer: Materializer)
  extends Controller with DefaultInstrumented {

  implicit val timeout = Timeout(500, TimeUnit.MILLISECONDS)

  val getListDsTimer = metrics.timer("list.datasets")


  metrics.cachedGauge("datasets.num.total", 1.minute) {
    val eventualList = listDsInfo(orgContext)
    val list: List[DsInfo] = Await.result(eventualList, 50.seconds)
    list.size
  }

  def sendRefresh(spec: String) = orgContext.orgActor ! DatasetMessage(spec, Command("refresh"))

  def listDatasets = Action.async { request =>
    getListDsTimer.timeFuture(listDsInfo(orgContext)).map(list => {

      val jsonBytes: Array[Byte] = Json.toJson(list).toString().toCharArray.map(_.toByte)
      val bos = new ByteArrayOutputStream(jsonBytes.length)
      val gzip = new GZIPOutputStream(bos)
      gzip.write(jsonBytes)
      gzip.close()
      val compressed = bos.toByteArray
      bos.close()
      Ok(compressed).withHeaders(
        CONTENT_ENCODING -> "gzip"
      ).as("application/json")
    })
  }


  def listPrefixes = Action { request =>
    val prefixes = orgContext.sipFactory.prefixRepos.map(_.prefix)
    Ok(Json.toJson(prefixes))
  }

  def datasetInfo(spec: String) = Action { request =>
    withDsInfo(spec, orgContext)(dsInfo => Ok(Json.toJson(dsInfo)))
  }

  def createDataset(spec: String, character: String, mapToPrefix: String) = Action.async { request =>
    orgContext.createDsInfo(Utils.adminUser, spec, character, mapToPrefix).map(dsInfo =>
      Ok(Json.obj("created" -> s"Dataset $spec with character $character and mapToPrefix $mapToPrefix"))
    )
  }

  def command(spec: String, command: String) = Action { request =>
    orgContext.orgActor ! DatasetMessage(spec, Command(command))
    Ok
  }

  def uploadDataset(spec: String) = Action(parse.multipartFormData) { request =>
    val datasetContext = orgContext.datasetContext(spec).mkdirs
    request.body.file("file").map { file =>
      val error = datasetContext.acceptUpload(file.filename, { target =>
        file.ref.moveTo(target, replace = true)
        Logger.debug(s"Dropped file ${file.filename} on $spec: ${target.getAbsolutePath}")
        target
      })
      error.map {
        message => NotAcceptable(Json.obj("problem" -> message))
      } getOrElse {
        Ok
      }
    } getOrElse {
      NotAcceptable(Json.obj("problem" -> "Cannot find file in upload"))
    }
  }

  def setDatasetProperties(spec: String) = Action(parse.json) { request =>
    withDsInfo(spec, orgContext) { dsInfo =>
      val propertyList = (request.body \ "propertyList").as[List[String]]
      val diProps: List[NXProp] = propertyList.map(name => allProps.getOrElse(name, throw new RuntimeException(s"Property not recognized: $name")))
      val propsValueOpts = diProps.map(prop => (prop, (request.body \ "values" \ prop.name).asOpt[String]))
      val propsValues = propsValueOpts.filter(t => t._2.isDefined).map(t => (t._1, t._2.get)) // find a better way
      Logger.debug(s"setDatasetProperties $propsValues")
      dsInfo.setSingularLiteralProps(propsValues: _*)
      sendRefresh(spec)
      Ok
    }
  }

  // ====== stats files =====

  def index(spec: String) = Action { request =>
    Utils.okFile(orgContext.datasetContext(spec).index)
  }

  def nodeStatus(spec: String, path: String) = Action { request =>
    orgContext.datasetContext(spec).status(path) match {
      case None => NotFound(Json.obj("path" -> path))
      case Some(file) => Utils.okFile(file)
    }
  }

  def sample(spec: String, path: String, size: Int) = Action { request =>
    orgContext.datasetContext(spec).sample(path, size) match {
      case None => NotFound(Json.obj("path" -> path, "size" -> size))
      case Some(file) => Utils.okFile(file)
    }
  }

  def histogram(spec: String, path: String, size: Int) = Action { request =>
    orgContext.datasetContext(spec).histogram(path, size) match {
      case None => NotFound(Json.obj("path" -> path, "size" -> size))
      case Some(file) => Utils.okFile(file)
    }
  }

  def setRecordDelimiter(spec: String) = Action(parse.json) { request =>
    val datasetContext = orgContext.datasetContext(spec)
    // todo: recordContainer instead perhaps
    val recordRoot = (request.body \ "recordRoot").as[String]
    val uniqueId = (request.body \ "uniqueId").as[String]
    val harvestTypeOpt = datasetContext.dsInfo.getLiteralProp(harvestType).flatMap(harvestTypeFromString)
    datasetContext.setDelimiters(harvestTypeOpt, recordRoot, uniqueId)
    Ok
  }

  // ====== vocabularies =====

  def toggleSkosifiedField(spec: String) = Action(parse.json) { request =>
    withDsInfo(spec, orgContext) { dsInfo: DsInfo =>
      val histogramPathOpt = (request.body \ "histogramPath").asOpt[String]
      val skosFieldTag = (request.body \ "skosFieldTag").as[String]
      val skosFieldUri = (request.body \ "skosFieldUri").as[String]
      val skosFieldValue = s"$skosFieldTag=$skosFieldUri"
      val included = (request.body \ "included").as[Boolean]
      Logger.debug(s"set skos field $skosFieldValue: $included")
      val currentSkosFields = dsInfo.getLiteralPropList(skosField)
      val action: String = if (included) {
        if (currentSkosFields.contains(skosFieldValue)) {
          "already exists"
        }
        else {
          val caseListOpt = for {
            path <- histogramPathOpt
            nodeRepo <- orgContext.datasetContext(spec).nodeRepo(path)
            histogram <- nodeRepo.largestHistogram
          } yield Sparql.createCasesFromHistogram(dsInfo, histogram)
          caseListOpt match {
            case Some(caseList) =>
              val addSkosEntriesQ = caseList.map(_.ensureSkosEntryQ).mkString
              val futureUpdate = ts.up.sparqlUpdate(addSkosEntriesQ).map { ok =>
                dsInfo.addLiteralPropToList(skosField, skosFieldValue)
                dsInfo.toggleNaveSkosField(datasetUri = dsInfo.uri, propertyUri = skosFieldUri, delete = false)
              }
              Await.result(futureUpdate, 1.minute)
              "added"
            case None =>
              "no skos entries"
          }
        }
      }
      else {
        if (!currentSkosFields.contains(skosFieldValue)) {
          "did not exists"
        }
        else {
          val skosifiedField = SkosifiedField(dsInfo.spec, dsInfo.uri, skosFieldValue)
          val futureUpdate = ts.up.sparqlUpdate(skosifiedField.removeSkosEntriesQ).map { ok =>
            dsInfo.removeLiteralPropFromList(skosField, skosFieldValue)
            dsInfo.toggleNaveSkosField(datasetUri = dsInfo.uri, propertyUri = skosFieldUri, delete = true)
          }
          Await.result(futureUpdate, 1.minute)
          "removed"
        }
      }
      Ok(Json.obj("action" -> action))
    }
  }

  def listVocabularies = Action.async { request =>
    listVocabInfo(orgContext).map(list => Ok(Json.toJson(list)))
  }

  def createVocabulary(spec: String) = Action.async { request =>
    VocabInfo.createVocabInfo(Utils.adminUser, spec, orgContext).map(ok =>
      Ok(Json.obj("created" -> s"Skos $spec created"))
    )
  }

  def deleteVocabulary(spec: String) = Action.async { request =>
    VocabInfo.freshVocabInfo(spec, orgContext).map { vocabInfoOpt =>
      vocabInfoOpt.map { vocabInfo =>
        vocabInfo.dropVocabulary
        Ok(Json.obj("created" -> s"Vocabulary $spec deleted"))
      } getOrElse {
        NotAcceptable(Json.obj("problem" -> s"Cannot find vocabulary $spec to delete"))
      }
    }
  }

  def uploadVocabulary(spec: String) = Action.async(parse.multipartFormData) { request =>
    withVocabInfo(spec, orgContext) { vocabInfo =>
      request.body.file("file").map { bodyFile =>
        val file = bodyFile.ref.file
        val putFile = ts.up.dataPutXMLFile(vocabInfo.skosGraphName, file)
        putFile.onFailure { case e: Throwable => Logger.error(s"Problem uploading vocabulary $spec", e) }
        putFile.map { ok =>
          val now: String = timeToString(new DateTime())
          vocabInfo.setSingularLiteralProps(skosUploadTime -> now)
          Ok
        }
      } getOrElse {
        Future(NotAcceptable(Json.obj("problem" -> "Cannot find file in upload")))
      }
    }
  }

  def vocabularyInfo(spec: String) = Action { request =>
    withVocabInfo(spec, orgContext)(vocabInfo => Ok(Json.toJson(vocabInfo)))
  }

  def vocabularyStatistics(spec: String) = Action.async { request =>
    withVocabInfo(spec, orgContext) { vocabInfo =>
      vocabInfo.conceptCount.map(stats => Ok(Json.toJson(stats)))
    }
  }

  def setVocabularyProperties(spec: String) = Action(parse.json) { request =>
    withVocabInfo(spec, orgContext) { vocabInfo =>
      val propertyList = (request.body \ "propertyList").as[List[String]]
      Logger.debug(s"setVocabularyProperties $propertyList")
      val diProps: List[NXProp] = propertyList.map(name => allProps.getOrElse(name, throw new RuntimeException(s"Property not recognized: $name")))
      val propsValueOpts = diProps.map(prop => (prop, (request.body \ "values" \ prop.name).asOpt[String]))
      val propsValues = propsValueOpts.filter(t => t._2.isDefined).map(t => (t._1, t._2.get)) // find a better way
      vocabInfo.setSingularLiteralProps(propsValues: _*)
      Ok
    }
  }

  def getVocabularyLanguages(spec: String) = Action { request =>
    withVocabInfo(spec, orgContext) { vocabInfo =>
      Ok(Json.obj("languages" -> vocabInfo.vocabulary.languages))
    }
  }

  def searchVocabulary(spec: String, sought: String, language: String) = Action { request =>
    val languageOpt = Option(language).find(lang => lang.trim.nonEmpty && lang != "-")
    Logger.debug(s"Search $spec/$language: $sought")
    withVocabInfo(spec, orgContext) { vocabInfo =>
      val labelSearch: LabelSearch = vocabInfo.vocabulary.search(sought, 25, languageOpt)
      Ok(Json.obj("search" -> labelSearch))
    }
  }

  def getSkosMappings(specA: String, specB: String) = Action.async { request =>
    val store = orgContext.vocabMappingStore(specA, specB)
    store.getMappings.map(tuples => Ok(Json.toJson(tuples.map(t => List(t._1, t._2)))))
  }

  def toggleSkosMapping(specA: String, specB: String) = Action.async(parse.json) { request =>
    val uriA = (request.body \ "uriA").as[String]
    val uriB = (request.body \ "uriB").as[String]
    val store = orgContext.vocabMappingStore(specA, specB)
    store.toggleMapping(SkosMapping(Utils.adminUser, uriA, uriB)).map { action =>
      Ok(Json.obj("action" -> action))
    }
  }

  def getTermVocabulary(spec: String) = Action { request => 
    withDsInfo(spec, orgContext) { dsInfo =>
      val results = dsInfo.vocabulary.concepts.map(concept => {
        //          val freq: Int = concept.frequency.getOrElse(0)
        val label = concept.getAltLabel(None).map(_.text).getOrElse("Label missing")
        val fieldPropertyTag = concept.fieldPropertyTag.getOrElse("")
        Json.obj(
          "uri" -> concept.resource.toString,
          "label" -> label,
          "frequency" -> concept.frequency,
          "fieldProperty" -> fieldPropertyTag
        )
      })
      Ok(Json.toJson(results))
    }
  }

  def getTermMappings(dsSpec: String) = Action.async { request =>
    val store = orgContext.termMappingStore(dsSpec)
    store.getMappings(categories = false).map(entries => Ok(Json.toJson(entries)))
  }

  def getCategoryMappings(dsSpec: String) = Action.async { request =>
    val store = orgContext.termMappingStore(dsSpec)
    store.getMappings(categories = true).map(entries => Ok(Json.toJson(entries)))
  }

  def toggleTermMapping(dsSpec: String, vocabSpec: String) = Action.async(parse.json) { request =>
    val uriA = (request.body \ "uriA").as[String]
    val uriB = (request.body \ "uriB").as[String]
    val store = orgContext.termMappingStore(dsSpec)
    withVocabInfo(vocabSpec, orgContext) { vocabInfo =>
      store.toggleMapping(SkosMapping(Utils.adminUser, uriA, uriB), vocabInfo).map { action =>
        Ok(Json.obj("action" -> action))
      }
    }
  }

  def listSipFiles(spec: String) = Action { request => 
    val datasetContext = orgContext.datasetContext(spec)
    val fileNames = datasetContext.sipRepo.listSips.map(_.file.getName)
    Ok(Json.obj("list" -> fileNames))
  }

  def deleteLatestSipFile(spec: String) = Action { request => 
    val datasetContext = orgContext.datasetContext(spec)
    val sips = datasetContext.sipRepo.listSips
    if (sips.size < 2) {
      NotFound(Json.obj("problem" -> s"Refusing to delete the last SIP file $spec"))
    }
    else {
      FileUtils.deleteQuietly(sips.head.file)
      Ok(Json.obj("deleted" -> sips.head.file.getName))
    }
  }

  def getCategoryList = Action.async{ request =>
    val map: Future[Option[VocabInfo]] = listVocabInfo(orgContext).map(list => list.find(_.spec == CATEGORIES_SPEC))
    map.map { catVocabInfoOpt =>
      catVocabInfoOpt.map { catVocabInfo =>
        val count = Await.result(catVocabInfo.conceptCount, 30.seconds)
        if (count > 5 && count < 30) {
          val categories = catVocabInfo.vocabulary.concepts.sortBy(_.prefLabels.head.text).map { c =>
            val details = c.altLabels.headOption.map(_.text).getOrElse("???")
            Json.obj(
              "uri" -> c.resource.toString,
              "code" -> c.prefLabels.head.text,
              "details" -> details
            )
          }
          Ok(Json.obj("categories" -> categories))
        }
        else {
          Ok(Json.obj("noCategories" -> s"Concept count not within range (5 - 30): $count"))
        }
      } getOrElse {
        Ok(Json.obj("noCategories" -> s"No SKOS vocabulary named '$CATEGORIES_SPEC'"))
      }
    }
  }

  def gatherCategoryCounts = Action { request => 
    orgContext.startCategoryCounts()
    Ok
  }

  def listSheets = Action { request => 
    Ok(Json.obj("sheets" -> orgContext.categoriesRepo.listSheets))
  }

  def sheet(spec: String) = Action(parse.anyContent) { implicit request =>
    Utils.okFile(orgContext.categoriesRepo.sheet(spec))
  }

}
