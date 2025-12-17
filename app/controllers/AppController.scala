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

import javax.inject._
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.util.{Success, Failure}
import play.api.Logging
import play.api.cache.AsyncCacheApi
import play.api.libs.json._
import play.api.mvc._
import play.api.mvc.BaseController
import akka.actor._
import akka.stream.Materializer
import akka.util.Timeout
import nl.grons.metrics4.scala.DefaultInstrumented
import org.apache.commons.io.FileUtils
import org.joda.time.DateTime

import organization.OrgContext
import services.{CredentialEncryption, Temporal}
import services.Temporal._
import triplestore.GraphProperties._
import triplestore.{Sparql, TripleStore}
import triplestore.Sparql.SkosifiedField
import dataset.DatasetActor._
import dataset.DsInfo
import dataset.DsInfo._
import harvest.Harvesting.HarvestType.harvestTypeFromString
import mapping.SkosMappingStore.SkosMapping
import mapping.SkosVocabulary._
import mapping.VocabInfo
import mapping.DefaultMappingRepo
import mapping.DefaultMappingRepo._
import mapping.DatasetMappingRepo
import mapping.DatasetMappingRepo._
import mapping.VocabInfo._
import organization.OrgActor.DatasetMessage
import web.Utils

@Singleton
class AppController @Inject() (
   orgContext: OrgContext
)(implicit
   ec: ExecutionContext,
   ts: TripleStore
) extends InjectedController with Logging with DefaultInstrumented {

  implicit val timeout: Timeout = Timeout(500, TimeUnit.MILLISECONDS)

  val getListDsTimer = metrics.timer("list.datasets")

  metrics.cachedGauge("datasets.num.total", 1.minute) {
    val eventualList = listDsInfo(orgContext)
    val list: List[DsInfo] = Await.result(eventualList, 50.seconds)
    list.size
  }

  def sendRefresh(spec: String) = orgContext.orgActor ! DatasetMessage(spec, Command("refresh"))

  def listDatasets = Action.async { request =>
    getListDsTimer.timeFuture(listDsInfo(orgContext)).map(list => {

      val jsonBytes: Array[Byte] = Json.toJson(list).toString().getBytes("UTF-8")
      val bos = new ByteArrayOutputStream(jsonBytes.length)
      val gzip = new GZIPOutputStream(bos)
      gzip.write(jsonBytes)
      gzip.close()
      val compressed = bos.toByteArray
      bos.close()
      Ok(compressed).withHeaders(
        CONTENT_ENCODING -> "gzip"
      ).as("application/json; charset=utf-8")
    })
  }

  /**
   * Lightweight dataset list endpoint - only returns minimal fields for collapsed view.
   * This significantly improves initial page load performance (10-20x faster).
   */
  def listDatasetsLight = Action.async { request =>
    import dataset.DsInfo.listDsInfoLight

    getListDsTimer.timeFuture(listDsInfoLight(orgContext)).map(list => {
      val jsonBytes: Array[Byte] = Json.toJson(list).toString().getBytes("UTF-8")
      val bos = new ByteArrayOutputStream(jsonBytes.length)
      val gzip = new GZIPOutputStream(bos)
      gzip.write(jsonBytes)
      gzip.close()
      val compressed = bos.toByteArray
      bos.close()
      Ok(compressed).withHeaders(
        CONTENT_ENCODING -> "gzip"
      ).as("application/json; charset=utf-8")
    })
  }

  def listActiveDatasets = Action.async { request =>
    import scala.concurrent.duration._
    import akka.pattern.ask
    import akka.util.Timeout
    import organization.OrgActor.{GetQueueStatus, QueueStatus, completionDetailFormat}

    implicit val timeout = Timeout(2.seconds)

    // Query OrgActor for complete queue status
    (orgContext.orgActor ? GetQueueStatus).mapTo[QueueStatus].map { status =>
      Ok(Json.obj(
        "processing" -> status.processing.sorted,
        "saving" -> status.saving.sorted,
        "queued" -> status.queued.map { case (spec, trigger, pos) =>
          Json.obj("spec" -> spec, "trigger" -> trigger, "position" -> pos)
        },
        "queueLength" -> status.queued.length,
        "availableSlots" -> orgContext.semaphore.availablePermits(),
        "completionStats" -> Json.obj(
          "manual1h" -> status.stats.manual1h,
          "automatic1h" -> status.stats.automatic1h,
          "manual4h" -> status.stats.manual4h,
          "automatic4h" -> status.stats.automatic4h,
          "manual24h" -> status.stats.manual24h,
          "automatic24h" -> status.stats.automatic24h
        ),
        "completionDetails" -> Json.toJson(status.completionDetails)
      ))
    }
  }

  def cancelQueuedOperation(spec: String) = Action.async { request =>
    import scala.concurrent.duration._
    import akka.pattern.ask
    import akka.util.Timeout
    import organization.OrgActor.{CancelQueuedOperation, CancelResult}

    implicit val timeout = Timeout(2.seconds)

    (orgContext.orgActor ? CancelQueuedOperation(spec)).mapTo[CancelResult].map { result =>
      Ok(Json.obj("success" -> result.success, "message" -> result.message))
    }
  }

  def listPrefixes = Action { request =>
    val prefixes = orgContext.sipFactory.prefixRepos.map(_.prefix)
    Ok(Json.toJson(prefixes))
  }

  def datasetInfo(spec: String) = Action { request =>
    withDsInfo(spec, orgContext)(dsInfo => Ok(Json.toJson(dsInfo)))
  }

  def createDataset(spec: String, character: String, mapToPrefix: String) = Action.async { request =>
    orgContext.createDsInfo(spec, character, mapToPrefix).map(dsInfo =>
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
        logger.debug(s"Dropped file ${file.filename} on $spec: ${target.getAbsolutePath}")
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

      // Handle password encryption: encrypt harvestPassword before saving
      val processedPropsValues = propsValues.map {
        case (prop, value) if prop == harvestPassword && value.nonEmpty =>
          // Encrypt the password if it's not already encrypted
          val encryptedValue = if (CredentialEncryption.isEncrypted(value)) {
            value // Already encrypted, keep as-is
          } else {
            CredentialEncryption.encrypt(value, orgContext.appConfig.appSecret)
          }
          (prop, encryptedValue)
        case other => other
      }

      logger.debug(s"setDatasetProperties $processedPropsValues")
      dsInfo.setSingularLiteralProps(processedPropsValues: _*)
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
    val recordRootValue = (request.body \ "recordRoot").as[String]
    val uniqueIdValue = (request.body \ "uniqueId").as[String]
    val harvestTypeOpt = datasetContext.dsInfo.getLiteralProp(harvestType).flatMap(harvestTypeFromString)
    datasetContext.setDelimiters(harvestTypeOpt, recordRootValue, uniqueIdValue)
    // Store delimiter values in triple store for visibility and to track that they've been set
    datasetContext.dsInfo.setDelimiters(recordRootValue, uniqueIdValue)
    // Broadcast update via WebSocket so UI refreshes
    sendRefresh(spec)
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
      logger.debug(s"set skos field $skosFieldValue: $included")
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
    VocabInfo.createVocabInfo(spec, orgContext).map(ok =>
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
        val file = bodyFile.ref.path.toFile
        val putFile = ts.up.dataPutXMLFile(vocabInfo.skosGraphName, file)
        putFile.onComplete {
          case Success(_) => ()
          case Failure(e) => logger.error(s"Problem uploading vocabulary $spec", e)
        }
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
      logger.debug(s"setVocabularyProperties $propertyList")
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
    logger.debug(s"Search $spec/$language: $sought")
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
    store.toggleMapping(SkosMapping(uriA, uriB)).map { action =>
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
      store.toggleMapping(SkosMapping(uriA, uriB), vocabInfo).map { action =>
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

  // ==================== Default Mappings (Named Mappings) ====================

  private lazy val defaultMappingRepo = new DefaultMappingRepo(orgContext.orgRoot)

  def listDefaultMappings = Action { request =>
    val prefixMappings = defaultMappingRepo.listAll()
    // Get available prefixes from the factory directory (schema definitions)
    val availablePrefixes = orgContext.sipFactory.prefixRepos.map(_.prefix).toList.sorted
    Ok(Json.obj(
      "prefixes" -> Json.toJson(prefixMappings),
      "availablePrefixes" -> Json.toJson(availablePrefixes)
    ))
  }

  def listMappingsForPrefix(prefix: String) = Action { request =>
    val mappings = defaultMappingRepo.listMappingsForPrefix(prefix)
    Ok(Json.obj(
      "prefix" -> prefix,
      "mappings" -> Json.toJson(mappings)
    ))
  }

  def getNamedMappingInfo(prefix: String, name: String) = Action { request =>
    defaultMappingRepo.getInfo(prefix, name) match {
      case Some(info) => Ok(Json.toJson(info))
      case None => NotFound(Json.obj("problem" -> s"No mapping found: $prefix/$name"))
    }
  }

  def getDefaultMappingXml(prefix: String, name: String, version: String) = Action { request =>
    defaultMappingRepo.getXml(prefix, name, version) match {
      case Some(xml) => Ok(xml).as("application/xml")
      case None => NotFound(Json.obj("problem" -> s"Mapping version not found: $prefix/$name/$version"))
    }
  }

  def createNamedMapping(prefix: String) = Action(parse.json) { request =>
    val displayName = (request.body \ "displayName").as[String]
    if (displayName.trim.isEmpty) {
      BadRequest(Json.obj("problem" -> "Display name is required"))
    } else {
      val mapping = defaultMappingRepo.createMapping(prefix, displayName)
      Ok(Json.toJson(mapping))
    }
  }

  def uploadDefaultMapping(prefix: String, name: String) = Action(parse.multipartFormData) { request =>
    request.body.file("file").map { file =>
      val xmlContent = FileUtils.readFileToString(file.ref.path.toFile, "UTF-8")
      val notes = request.body.dataParts.get("notes").flatMap(_.headOption)
      val version = defaultMappingRepo.saveVersion(prefix, name, xmlContent, "upload", None, notes)
      Ok(Json.toJson(version))
    }.getOrElse {
      NotAcceptable(Json.obj("problem" -> "No file provided"))
    }
  }

  def copyMappingFromDataset(prefix: String, name: String, spec: String) = Action(parse.json) { request =>
    import scala.io.Source

    val notes = (request.body \ "notes").asOpt[String]

    // Get the mapping XML from the dataset's current SIP
    val sipRepo = orgContext.datasetContext(spec).sipRepo

    sipRepo.latestSipOpt match {
      case Some(sip) =>
        // Find the mapping file in the SIP zip
        val mappingFileName = s"mapping_$prefix.xml"
        sip.entries.get(mappingFileName) match {
          case Some(entry) =>
            val inputStream = sip.zipFile.getInputStream(entry)
            try {
              val mappingXml = Source.fromInputStream(inputStream, "UTF-8").mkString
              val version = defaultMappingRepo.saveVersion(
                prefix,
                name,
                mappingXml,
                "copy_from_dataset",
                Some(spec),
                notes.orElse(Some(s"Copied from dataset: $spec"))
              )
              Ok(Json.toJson(version))
            } finally {
              inputStream.close()
            }
          case None =>
            NotFound(Json.obj("problem" -> s"No mapping file found in SIP for prefix: $prefix"))
        }
      case None =>
        NotFound(Json.obj("problem" -> s"No SIP found for dataset: $spec"))
    }
  }

  def setCurrentDefaultMapping(prefix: String, name: String) = Action(parse.json) { request =>
    val hash = (request.body \ "hash").as[String]
    if (defaultMappingRepo.setCurrentVersion(prefix, name, hash)) {
      Ok(Json.obj("success" -> true))
    } else {
      NotFound(Json.obj("problem" -> s"Version not found: $prefix/$name/$hash"))
    }
  }

  def deleteDefaultMappingVersion(prefix: String, name: String, hash: String) = Action { request =>
    if (defaultMappingRepo.deleteVersion(prefix, name, hash)) {
      Ok(Json.obj("success" -> true))
    } else {
      NotFound(Json.obj("problem" -> s"Version not found: $prefix/$name/$hash"))
    }
  }

  // ==================== Dataset Mapping Source ====================

  def listDatasetMappingVersions(spec: String) = Action { request =>
    val datasetContext = orgContext.datasetContext(spec)
    val repo = datasetContext.datasetMappingRepo
    val versions = repo.listVersions
    val currentHash = repo.getCurrentVersionHash

    Ok(Json.obj(
      "spec" -> spec,
      "prefix" -> repo.getPrefix,
      "currentVersion" -> currentHash,
      "versions" -> versions.map { v =>
        Json.obj(
          "timestamp" -> v.timestamp.toString,
          "hash" -> v.hash,
          "source" -> v.source,
          "sourceDefault" -> v.sourceDefault,
          "description" -> v.description,
          "isCurrent" -> currentHash.contains(v.hash)
        )
      }
    ))
  }

  def getDatasetMappingXml(spec: String, version: String) = Action { request =>
    val datasetContext = orgContext.datasetContext(spec)
    val repo = datasetContext.datasetMappingRepo

    repo.getXml(version) match {
      case Some(xml) => Ok(xml).as("application/xml")
      case None => NotFound(Json.obj("problem" -> s"Mapping version $version not found for dataset $spec"))
    }
  }

  def setDatasetMappingSource(spec: String) = Action(parse.json) { request =>
    val source = (request.body \ "source").as[String]
    val prefix = (request.body \ "prefix").asOpt[String]
    val name = (request.body \ "name").asOpt[String]
    val version = (request.body \ "version").asOpt[String]

    DsInfo.withDsInfo(spec, orgContext) { dsInfo =>
      dsInfo.setMappingSource(source, prefix, name, version)
      Ok(Json.obj(
        "success" -> true,
        "mappingSource" -> dsInfo.getMappingSource,
        "defaultMappingPrefix" -> dsInfo.getDefaultMappingPrefix,
        "defaultMappingName" -> dsInfo.getDefaultMappingName,
        "defaultMappingVersion" -> dsInfo.getDefaultMappingVersion
      ))
    }
  }

  def rollbackDatasetMapping(spec: String, hash: String) = Action { request =>
    val datasetContext = orgContext.datasetContext(spec)
    val repo = datasetContext.datasetMappingRepo

    repo.rollbackTo(hash) match {
      case Some(newVersion) =>
        // Also switch to manual mapping source since we're using a specific dataset version
        DsInfo.withDsInfo(spec, orgContext) { dsInfo =>
          dsInfo.setMappingSource("manual", None, None)
        }
        Ok(Json.obj(
          "success" -> true,
          "newVersion" -> Json.obj(
            "timestamp" -> newVersion.timestamp.toString,
            "hash" -> newVersion.hash,
            "source" -> newVersion.source,
            "description" -> newVersion.description
          )
        ))
      case None =>
        NotFound(Json.obj("problem" -> s"Cannot rollback: version $hash not found for dataset $spec"))
    }
  }

}

