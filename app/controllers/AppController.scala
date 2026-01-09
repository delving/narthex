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
import services.{CredentialEncryption, IndexStatsService, IndexStatsResponse, Temporal, TrendTrackingService}
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
   orgContext: OrgContext,
   indexStatsService: IndexStatsService
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
   * Also includes retry status for datasets in retry mode (fetched via separate query).
   */
  def listDatasetsLight = Action.async { request =>
    import dataset.DsInfo.listDsInfoLightWithRetry

    getListDsTimer.timeFuture(listDsInfoLightWithRetry(orgContext)).map(list => {
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

  /**
   * Get index statistics comparing Narthex datasets with Hub3 search index
   */
  def indexStats = Action.async { request =>
    indexStatsService.getIndexStats().map { stats =>
      Ok(Json.toJson(stats))
    }.recover {
      case e: Exception =>
        logger.error(s"Failed to fetch index stats: ${e.getMessage}", e)
        InternalServerError(Json.obj("error" -> "Failed to fetch index statistics"))
    }
  }

  /**
   * Lightweight endpoint to get counts of datasets with index issues.
   * Used for polling to show badge on Index Stats button.
   */
  def indexStatsWrongCount = Action.async { request =>
    indexStatsService.getIndexAlertCounts().map { case (wrongCount, notIndexedCount) =>
      Ok(Json.obj(
        "wrongCount" -> wrongCount,
        "notIndexedCount" -> notIndexedCount,
        "totalAlerts" -> (wrongCount + notIndexedCount)
      ))
    }.recover {
      case e: Exception =>
        logger.error(s"Failed to fetch alert counts: ${e.getMessage}", e)
        InternalServerError(Json.obj("error" -> "Failed to fetch alert counts"))
    }
  }

  /**
   * Get index statistics augmented with 24h trend data.
   */
  def indexStatsWithTrends = Action.async { request =>
    indexStatsService.getIndexStats().map { stats =>
      // Augment each dataset with trend data
      import services.TrendDelta
      import services.DatasetIndexStats

      def augmentWithTrends(datasets: List[DatasetIndexStats]): List[JsObject] = {
        datasets.map { ds =>
          val trendsLog = new java.io.File(
            orgContext.datasetsDir,
            s"${ds.spec}/trends.jsonl"
          )
          val trendSummary = TrendTrackingService.getDatasetTrendSummary(trendsLog, ds.spec)
          val baseJson = Json.toJson(ds).as[JsObject]

          trendSummary match {
            case Some(summary) =>
              baseJson ++ Json.obj(
                "delta24h" -> Json.obj(
                  "source" -> summary.delta24h.source,
                  "valid" -> summary.delta24h.valid,
                  "indexed" -> summary.delta24h.indexed
                )
              )
            case None =>
              baseJson ++ Json.obj(
                "delta24h" -> Json.obj(
                  "source" -> 0,
                  "valid" -> 0,
                  "indexed" -> 0
                )
              )
          }
        }
      }

      Ok(Json.obj(
        "totalIndexed" -> stats.totalIndexed,
        "totalDatasets" -> stats.totalDatasets,
        "correct" -> augmentWithTrends(stats.correct),
        "notIndexed" -> augmentWithTrends(stats.notIndexed),
        "notProcessed" -> augmentWithTrends(stats.notProcessed),
        "wrongCount" -> augmentWithTrends(stats.wrongCount),
        "deleted" -> augmentWithTrends(stats.deleted),
        "disabled" -> augmentWithTrends(stats.disabled)
      ))
    }.recover {
      case e: Exception =>
        logger.error(s"Failed to fetch index stats with trends: ${e.getMessage}", e)
        InternalServerError(Json.obj("error" -> "Failed to fetch index statistics"))
    }
  }

  /**
   * Get organization-wide trend summary.
   */
  def getOrganizationTrends = Action.async { request =>
    import services.{TrendDelta, DatasetTrendSummary, OrganizationTrends}

    listDsInfo(orgContext).map { datasets =>
      val summaries = datasets.flatMap { dsInfo =>
        val trendsLog = orgContext.datasetContext(dsInfo.spec).trendsLog
        TrendTrackingService.getDatasetTrendSummary(trendsLog, dsInfo.spec)
      }

      // Calculate net delta across all datasets
      val netDelta24h = TrendDelta(
        source = summaries.map(_.delta24h.source).sum,
        valid = summaries.map(_.delta24h.valid).sum,
        indexed = summaries.map(_.delta24h.indexed).sum
      )

      // Categorize datasets
      val growing = summaries.filter(s =>
        s.delta24h.source > 0 || s.delta24h.indexed > 0
      ).sortBy(s => -(s.delta24h.source + s.delta24h.indexed))

      val shrinking = summaries.filter(s =>
        s.delta24h.source < 0 || s.delta24h.indexed < 0
      ).sortBy(s => s.delta24h.source + s.delta24h.indexed)

      val stable = summaries.filter(s =>
        s.delta24h.source == 0 && s.delta24h.indexed == 0
      ).sortBy(_.spec)

      val orgTrends = OrganizationTrends(
        generatedAt = DateTime.now(),
        totalDatasets = datasets.size,
        totalSourceRecords = summaries.map(_.currentSource.toLong).sum,
        totalIndexedRecords = summaries.map(_.currentIndexed.toLong).sum,
        netDelta24h = netDelta24h,
        growing = growing,
        shrinking = shrinking,
        stable = stable
      )

      Ok(Json.toJson(orgTrends))
    }.recover {
      case e: Exception =>
        logger.error(s"Failed to get organization trends: ${e.getMessage}", e)
        InternalServerError(Json.obj("error" -> "Failed to fetch organization trends"))
    }
  }

  /**
   * Get trend history for a specific dataset.
   */
  def getDatasetTrends(spec: String) = Action { request =>
    val trendsLog = orgContext.datasetContext(spec).trendsLog
    val trends = TrendTrackingService.getDatasetTrends(trendsLog, spec)
    Ok(Json.toJson(trends))
  }

  /**
   * Manually trigger a daily trend snapshot for all datasets.
   */
  def triggerTrendSnapshot = Action.async { request =>
    import triplestore.GraphProperties._

    listDsInfo(orgContext).flatMap { datasets =>
      // Fetch Hub3 index counts
      indexStatsService.fetchHub3IndexCounts().map { case (_, hub3Counts) =>
        var captured = 0

        datasets.foreach { dsInfo =>
          try {
            val trendsLog = orgContext.datasetContext(dsInfo.spec).trendsLog
            val sourceRecords = dsInfo.getLiteralProp(sourceRecordCount).map(_.toInt).getOrElse(0)
            val acquiredRecords = dsInfo.getLiteralProp(acquiredRecordCount).map(_.toInt).getOrElse(0)
            val deletedRecords = dsInfo.getLiteralProp(deletedRecordCount).map(_.toInt).getOrElse(0)
            val validRecords = dsInfo.getLiteralProp(processedValid).map(_.toInt).getOrElse(0)
            val invalidRecords = dsInfo.getLiteralProp(processedInvalid).map(_.toInt).getOrElse(0)
            val indexedRecords = hub3Counts.getOrElse(dsInfo.spec, 0)

            TrendTrackingService.captureSnapshot(
              trendsLog = trendsLog,
              snapshotType = "daily",
              sourceRecords = sourceRecords,
              acquiredRecords = acquiredRecords,
              deletedRecords = deletedRecords,
              validRecords = validRecords,
              invalidRecords = invalidRecords,
              indexedRecords = indexedRecords
            )
            captured += 1
          } catch {
            case e: Exception =>
              logger.warn(s"Failed to capture snapshot for ${dsInfo.spec}: ${e.getMessage}")
          }
        }

        Ok(Json.obj(
          "success" -> true,
          "datasetsProcessed" -> datasets.size,
          "snapshotsCaptured" -> captured
        ))
      }
    }.recover {
      case e: Exception =>
        logger.error(s"Failed to trigger trend snapshot: ${e.getMessage}", e)
        InternalServerError(Json.obj("error" -> "Failed to trigger trend snapshot"))
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
    import scala.io.Source

    val datasetContext = orgContext.datasetContext(spec)
    val repo = datasetContext.datasetMappingRepo
    var versions = repo.listVersions

    // Auto-migrate: if no versions in repo but SIP exists with mapping, import it
    if (versions.isEmpty) {
      datasetContext.sipRepo.latestSipOpt.foreach { sip =>
        sip.sipMappingOpt.foreach { sipMapping =>
          val prefix = sipMapping.prefix
          val mappingFileName = s"mapping_$prefix.xml"
          sip.entries.get(mappingFileName).foreach { entry =>
            try {
              val inputStream = sip.zipFile.getInputStream(entry)
              try {
                val mappingXml = Source.fromInputStream(inputStream, "UTF-8").mkString
                repo.saveFromSipUpload(mappingXml, prefix, Some("Auto-migrated from existing SIP"))
                logger.info(s"Auto-migrated mapping from SIP for dataset $spec (prefix: $prefix)")
                versions = repo.listVersions // Reload after migration
              } finally {
                inputStream.close()
              }
            } catch {
              case e: Exception =>
                logger.warn(s"Failed to auto-migrate mapping from SIP for $spec: ${e.getMessage}")
            }
          }
        }
      }
    }

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
    import scala.io.Source

    val datasetContext = orgContext.datasetContext(spec)
    val repo = datasetContext.datasetMappingRepo

    repo.getXml(version) match {
      case Some(xml) => Ok(xml).as("application/xml")
      case None =>
        // Fallback: try to get mapping from SIP file directly
        if (version == "current" || version == "latest") {
          datasetContext.sipRepo.latestSipOpt.flatMap { sip =>
            sip.sipMappingOpt.flatMap { sipMapping =>
              val mappingFileName = s"mapping_${sipMapping.prefix}.xml"
              sip.entries.get(mappingFileName).map { entry =>
                val inputStream = sip.zipFile.getInputStream(entry)
                try {
                  val mappingXml = Source.fromInputStream(inputStream, "UTF-8").mkString
                  Ok(mappingXml).as("application/xml")
                } finally {
                  inputStream.close()
                }
              }
            }
          }.getOrElse(NotFound(Json.obj("problem" -> s"Mapping version $version not found for dataset $spec")))
        } else {
          NotFound(Json.obj("problem" -> s"Mapping version $version not found for dataset $spec"))
        }
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

