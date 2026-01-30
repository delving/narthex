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
import services.{CredentialEncryption, IndexStatsService, IndexStatsResponse, MemoryMonitorService, QualitySummaryService, Temporal, TrendTrackingService, ViolationRecordService}
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

// sip-core imports for Groovy code generation
import eu.delving.metadata.{CodeGenerator, EditPath, MappingResult, NodeMapping, Path}
import eu.delving.groovy.{BulkMappingRunner, MetadataRecordFactory, XmlSerializer}
import scala.jdk.CollectionConverters._

@Singleton
class AppController @Inject() (
   orgContext: OrgContext,
   indexStatsService: IndexStatsService,
   qualitySummaryService: QualitySummaryService,
   violationRecordService: ViolationRecordService,
   memoryMonitorService: MemoryMonitorService
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
   * First tries to read from cached trends_summary.json for fast response,
   * falls back to computing from individual dataset files if cache is stale.
   */
  def getOrganizationTrends = Action.async { request =>
    import services.{TrendDelta, DatasetTrendSummary, OrganizationTrends}

    // Try to read from cached summary file first (fast path for 200+ datasets)
    TrendTrackingService.readTrendsSummary(orgContext.trendsSummaryFile) match {
      case Some(summary) =>
        logger.debug(s"Using cached trends summary from ${summary.generatedAt}")
        val orgTrends = TrendTrackingService.buildOrganizationTrends(summary)
        Future.successful(Ok(Json.toJson(orgTrends)))

      case None =>
        // Fallback: compute from individual dataset files (slower but always current)
        logger.debug("Computing trends from individual dataset files")
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
        val specs = scala.collection.mutable.ListBuffer[String]()

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
            specs += dsInfo.spec
            captured += 1
          } catch {
            case e: Exception =>
              logger.warn(s"Failed to capture snapshot for ${dsInfo.spec}: ${e.getMessage}")
          }
        }

        // Generate organization-level summary file for fast API responses
        try {
          TrendTrackingService.generateTrendsSummary(
            orgContext.trendsSummaryFile,
            orgContext.datasetsDir,
            specs.toList
          )
        } catch {
          case e: Exception =>
            logger.warn(s"Failed to generate trends summary: ${e.getMessage}")
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

  /**
   * Get Record Definition (rec-def) as JSON for the mapping editor.
   * Parses the rec-def XML and converts it to a tree structure suitable for the UI.
   */
  def getRecDef(prefix: String) = Action { request =>
    orgContext.sipFactory.prefixRepo(prefix) match {
      case None =>
        NotFound(Json.obj("error" -> s"Prefix '$prefix' not found"))
      case Some(prefixRepo) =>
        try {
          val recDefXml = scala.xml.XML.loadFile(prefixRepo.recordDefinition)
          val recDefJson = convertRecDefToJson(recDefXml, prefix, prefixRepo.schemaVersions)
          Ok(recDefJson)
        } catch {
          case e: Exception =>
            logger.error(s"Failed to parse rec-def for prefix $prefix: ${e.getMessage}", e)
            InternalServerError(Json.obj("error" -> s"Failed to parse rec-def: ${e.getMessage}"))
        }
    }
  }

  /**
   * Convert rec-def XML to JSON format for the mapping editor UI.
   */
  private def convertRecDefToJson(xml: scala.xml.Elem, prefix: String, schemaVersions: String): JsValue = {
    // Extract namespaces
    val namespaces = (xml \ "namespaces" \ "namespace").map { ns =>
      Json.obj(
        "prefix" -> (ns \ "@prefix").text,
        "uri" -> (ns \ "@uri").text
      )
    }

    // Extract custom functions
    val functions = (xml \ "functions" \ "mapping-function").map { fn =>
      val codeLines = (fn \ "groovy-code" \ "string").map(_.text)
      val sampleInputs = (fn \ "sample-input" \ "string").map(_.text)
      Json.obj(
        "name" -> (fn \ "@name").text,
        "code" -> codeLines.mkString("\n"),
        "sampleInputs" -> sampleInputs
      )
    }

    // Build documentation lookup from <docs> section
    val docsLookup: Map[String, JsObject] = (xml \ "docs" \ "doc").flatMap { doc =>
      val pathAttr = (doc \ "@path").text
      val tagAttr = (doc \ "@tag").text
      val lines = (doc \ "string").map(_.text).mkString(" ")
      val paras = (doc \ "para").map { para =>
        val name = (para \ "@name").text
        val content = para.text.trim
        Json.obj("name" -> name, "content" -> content)
      }

      val description: String = if (lines.nonEmpty) lines else paras.headOption.map(p => (p \ "content").as[String]).getOrElse("")
      val docObj = Json.obj(
        "description" -> description,
        "paragraphs" -> paras
      )

      if (pathAttr.nonEmpty) Some(pathAttr -> docObj)
      else if (tagAttr.nonEmpty) Some(tagAttr -> docObj)
      else None
    }.toMap

    // Extract the root element and build the tree
    val rootElem = xml \ "root"
    val tree = if (rootElem.nonEmpty) {
      // Get the root tag (e.g., "RDF") to use as base path for documentation lookup
      val rootTag = (rootElem.head \ "@tag").text
      val basePath = if (rootTag.nonEmpty) s"/$rootTag" else ""
      convertElemToTreeNodes(rootElem.head, basePath, prefix, docsLookup)
    } else {
      // If no explicit root, look for elem-groups
      val elemGroups = (xml \ "elem-groups" \ "elem-group").flatMap { group =>
        (group \ "elem").map(e => convertElemToTreeNode(e, "", prefix, docsLookup))
      }
      Json.toJson(elemGroups)
    }

    Json.obj(
      "prefix" -> prefix,
      "version" -> schemaVersions.replace(s"${prefix}_", ""),
      "namespaces" -> namespaces,
      "functions" -> functions,
      "tree" -> tree
    )
  }

  /**
   * Convert a root element containing multiple child elements to tree nodes.
   */
  private def convertElemToTreeNodes(rootNode: scala.xml.Node, parentPath: String, defaultPrefix: String, docsLookup: Map[String, JsObject]): JsArray = {
    val children = (rootNode \ "elem").map { elem =>
      convertElemToTreeNode(elem, parentPath, defaultPrefix, docsLookup)
    }
    JsArray(children.toSeq)
  }

  /**
   * Convert a single elem XML node to a TreeNode JSON object.
   */
  private def convertElemToTreeNode(elem: scala.xml.Node, parentPath: String, defaultPrefix: String, docsLookup: Map[String, JsObject]): JsObject = {
    val tag = (elem \ "@tag").text
    val label = (elem \ "@label").headOption.map(_.text)
    val required = (elem \ "@required").headOption.map(_.text == "true").getOrElse(false)
    val singular = (elem \ "@singular").headOption.map(_.text == "true").getOrElse(false)
    val hidden = (elem \ "@hidden").headOption.map(_.text == "true").getOrElse(false)
    val unmappable = (elem \ "@unmappable").headOption.map(_.text == "true").getOrElse(false)

    // Build the path
    val currentPath = if (parentPath.isEmpty) s"/$tag" else s"$parentPath/$tag"

    // Create a unique ID from the path
    val id = currentPath.replace("/", "_").replace(":", "_").replaceFirst("^_", "")

    // Get documentation if available
    val doc = docsLookup.get(currentPath).orElse(docsLookup.get(tag))

    // Process attributes defined via attrs attribute (e.g., attrs="rdf:resource,xml:lang")
    val attrsString = (elem \ "@attrs").headOption.map(_.text).getOrElse("")
    val attrNames = if (attrsString.nonEmpty) attrsString.split("[, ]+").toList else List.empty
    val attrsFromRef = attrNames.map { attrName =>
      val attrTag = if (attrName.startsWith("@")) attrName else s"@$attrName"
      val attrPath = s"$currentPath/$attrTag"
      val attrId = attrPath.replace("/", "_").replace(":", "_").replace("@", "").replaceFirst("^_", "")
      Json.obj(
        "id" -> attrId,
        "name" -> attrTag,
        "path" -> attrPath,
        "isAttribute" -> true
      )
    }

    // Process inline attributes
    val inlineAttrs = (elem \ "attr").map { attr =>
      val attrTag = "@" + (attr \ "@tag").text
      val attrPath = s"$currentPath/$attrTag"
      val attrId = attrPath.replace("/", "_").replace(":", "_").replace("@", "").replaceFirst("^_", "")
      Json.obj(
        "id" -> attrId,
        "name" -> attrTag,
        "path" -> attrPath,
        "isAttribute" -> true
      )
    }

    // Process child elements
    val childElems = (elem \ "elem").map { childElem =>
      convertElemToTreeNode(childElem, currentPath, defaultPrefix, docsLookup)
    }

    // Combine attributes and child elements
    val allChildren = attrsFromRef ++ inlineAttrs ++ childElems

    // Build the result object
    var result = Json.obj(
      "id" -> id,
      "name" -> tag,
      "path" -> currentPath,
      "isAttribute" -> false
    )

    if (label.isDefined) result = result + ("label" -> JsString(label.get))
    if (required) result = result + ("required" -> JsBoolean(true))
    if (!singular) result = result + ("repeatable" -> JsBoolean(true))
    if (hidden) result = result + ("hidden" -> JsBoolean(true))
    if (unmappable) result = result + ("unmappable" -> JsBoolean(true))
    if (doc.isDefined) result = result + ("documentation" -> doc.get)
    if (allChildren.nonEmpty) result = result + ("children" -> Json.toJson(allChildren))

    result
  }

  /**
   * Get mappings for a dataset as JSON for the mapping editor.
   * Parses the mapping XML and returns structured data.
   */
  def getMappingsJson(spec: String) = Action { request =>
    import scala.io.Source

    val datasetContext = orgContext.datasetContext(spec)
    val repo = datasetContext.datasetMappingRepo

    // Try to get mapping from repo first, then fallback to SIP file
    val mappingXmlOpt: Option[String] = repo.getXml("current").orElse {
      datasetContext.sipRepo.latestSipOpt.flatMap { sip =>
        sip.sipMappingOpt.flatMap { sipMapping =>
          val mappingFileName = s"mapping_${sipMapping.prefix}.xml"
          sip.entries.get(mappingFileName).map { entry =>
            val inputStream = sip.zipFile.getInputStream(entry)
            try {
              Source.fromInputStream(inputStream, "UTF-8").mkString
            } finally {
              inputStream.close()
            }
          }
        }
      }
    }

    mappingXmlOpt match {
      case None =>
        NotFound(Json.obj("error" -> s"No mapping found for dataset $spec"))
      case Some(xmlString) =>
        try {
          val xml = scala.xml.XML.loadString(xmlString)
          val mappingsJson = convertMappingToJson(xml)
          Ok(mappingsJson)
        } catch {
          case e: Exception =>
            logger.error(s"Failed to parse mapping XML for dataset $spec: ${e.getMessage}", e)
            InternalServerError(Json.obj("error" -> s"Failed to parse mapping: ${e.getMessage}"))
        }
    }
  }

  /**
   * Convert mapping XML to JSON format for the mapping editor UI.
   */
  private def convertMappingToJson(xml: scala.xml.Elem): JsValue = {
    val prefix = (xml \ "@prefix").text
    val schemaVersion = (xml \ "@schemaVersion").text
    val locked = (xml \ "@locked").text == "true"

    // Extract facts
    val facts = (xml \ "facts" \ "entry").map { entry =>
      val key = (entry \ "@key").text
      val value = entry.text
      key -> value
    }.toMap

    // Extract custom functions
    val functions = (xml \ "functions" \ "mapping-function").map { fn =>
      val name = (fn \ "@name").text
      val codeLines = (fn \ "groovy-code" \ "string").map(_.text)
      Json.obj(
        "name" -> name,
        "code" -> codeLines.mkString("\n")
      )
    }

    // Extract node mappings
    val nodeMappings = (xml \ "node-mappings" \ "node-mapping").map { nm =>
      val inputPath = (nm \ "@inputPath").text
      val outputPath = (nm \ "@outputPath").text
      val operator = (nm \ "@operator").headOption.map(_.text)

      // Get groovy code if present
      val groovyCodeLines = (nm \ "groovy-code" \ "string").map(_.text)
      val groovyCode = if (groovyCodeLines.nonEmpty) Some(groovyCodeLines.mkString("\n")) else None

      // Get documentation if present
      val docLines = (nm \ "documentation" \ "string").map(_.text)
      val documentation = if (docLines.nonEmpty) Some(docLines.mkString("\n")) else None

      // Get siblings if present
      val siblings = (nm \ "siblings" \ "path").map(_.text).toList

      var mapping = Json.obj(
        "inputPath" -> inputPath,
        "outputPath" -> outputPath
      )

      if (operator.isDefined) mapping = mapping + ("operator" -> JsString(operator.get))
      if (groovyCode.isDefined) mapping = mapping + ("groovyCode" -> JsString(groovyCode.get))
      if (documentation.isDefined) mapping = mapping + ("documentation" -> JsString(documentation.get))
      if (siblings.nonEmpty) mapping = mapping + ("siblings" -> Json.toJson(siblings))

      mapping
    }

    Json.obj(
      "prefix" -> prefix,
      "schemaVersion" -> schemaVersion,
      "locked" -> locked,
      "facts" -> Json.toJson(facts),
      "functions" -> functions,
      "mappings" -> nodeMappings
    )
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

  // ====== source analysis stats files =====

  def sourceIndex(spec: String) = Action { request =>
    val indexFile = orgContext.datasetContext(spec).sourceIndex
    if (indexFile.exists()) Utils.okFile(indexFile)
    else NotFound(Json.obj("error" -> "Source not analyzed"))
  }

  def sourceNodeStatus(spec: String, path: String) = Action { request =>
    orgContext.datasetContext(spec).sourceStatus(path) match {
      case None => NotFound(Json.obj("path" -> path))
      case Some(file) => Utils.okFile(file)
    }
  }

  def sourceSample(spec: String, path: String, size: Int) = Action { request =>
    orgContext.datasetContext(spec).sourceSample(path, size) match {
      case None => NotFound(Json.obj("path" -> path, "size" -> size))
      case Some(file) => Utils.okFile(file)
    }
  }

  def sourceHistogram(spec: String, path: String, size: Int) = Action { request =>
    orgContext.datasetContext(spec).sourceHistogram(path, size) match {
      case None => NotFound(Json.obj("path" -> path, "size" -> size))
      case Some(file) => Utils.okFile(file)
    }
  }

  // ====== quality summary endpoints =====

  /**
   * Get quality summary for a dataset's processed analysis
   */
  def qualitySummary(spec: String) = Action { request =>
    qualitySummaryService.getQualitySummary(spec, isSource = false) match {
      case Some(summary) => Ok(Json.toJson(summary))
      case None => NotFound(Json.obj("error" -> "Quality summary not available - dataset not analyzed"))
    }
  }

  /**
   * Get quality summary for a dataset's source analysis
   */
  def sourceQualitySummary(spec: String) = Action { request =>
    qualitySummaryService.getQualitySummary(spec, isSource = true) match {
      case Some(summary) => Ok(Json.toJson(summary))
      case None => NotFound(Json.obj("error" -> "Source quality summary not available - source not analyzed"))
    }
  }

  /**
   * Export quality summary as JSON file
   */
  def exportQualitySummaryJson(spec: String, isSource: Boolean) = Action { request =>
    qualitySummaryService.getQualitySummary(spec, isSource) match {
      case Some(summary) =>
        val filename = s"${spec}-quality-summary${if (isSource) "-source" else ""}.json"
        Ok(Json.prettyPrint(Json.toJson(summary)))
          .as("application/json")
          .withHeaders("Content-Disposition" -> s"""attachment; filename="$filename"""")
      case None =>
        NotFound(Json.obj("error" -> "Quality summary not available"))
    }
  }

  /**
   * Export quality summary as CSV file
   */
  def exportQualitySummaryCsv(spec: String, isSource: Boolean) = Action { request =>
    qualitySummaryService.getQualitySummary(spec, isSource) match {
      case Some(summary) =>
        val filename = s"${spec}-quality-summary${if (isSource) "-source" else ""}.csv"
        val csv = qualitySummaryService.toCSV(summary, spec, isSource)
        Ok(csv)
          .as("text/csv")
          .withHeaders("Content-Disposition" -> s"""attachment; filename="$filename"""")
      case None =>
        NotFound(Json.obj("error" -> "Quality summary not available"))
    }
  }

  /**
   * Get quality comparison between source and processed analysis
   */
  def qualityComparison(spec: String) = Action { request =>
    qualitySummaryService.getQualityComparison(spec) match {
      case Some(comparison) => Ok(Json.toJson(comparison))
      case None => NotFound(Json.obj("error" -> "Comparison not available - neither source nor processed analysis exists"))
    }
  }

  /**
   * Find records containing a specific value.
   * Used for drill-down from violation samples to source records.
   */
  def recordsByValue(spec: String, value: String, limit: Int) = Action { request =>
    val datasetContext = orgContext.datasetContext(spec)

    if (value.isEmpty) {
      BadRequest(Json.obj("error" -> "Value parameter is required"))
    } else {
      val matches = violationRecordService.findRecordsByValue(datasetContext, value, limit)
      Ok(Json.obj(
        "spec" -> spec,
        "searchValue" -> value,
        "matchCount" -> matches.size,
        "matches" -> Json.toJson(matches.map { m =>
          Json.obj(
            "recordId" -> m.recordId,
            "matchedValue" -> m.matchedValue,
            "context" -> m.context,
            "matchCount" -> m.matchCount
          )
        })
      ))
    }
  }

  /**
   * Count records containing a specific value (faster than full search).
   */
  def recordCountByValue(spec: String, value: String) = Action { request =>
    val datasetContext = orgContext.datasetContext(spec)

    if (value.isEmpty) {
      BadRequest(Json.obj("error" -> "Value parameter is required"))
    } else {
      val count = violationRecordService.countRecordsWithValue(datasetContext, value)
      Ok(Json.obj(
        "spec" -> spec,
        "searchValue" -> value,
        "recordCount" -> count
      ))
    }
  }

  /**
   * Export all record IDs containing a specific value.
   * Supports JSON and CSV formats for bulk remediation workflows.
   */
  def exportProblemRecords(spec: String, value: String, violationType: String, format: String) = Action { request =>
    val datasetContext = orgContext.datasetContext(spec)

    if (value.isEmpty) {
      BadRequest(Json.obj("error" -> "Value parameter is required"))
    } else {
      val export = violationRecordService.exportProblemRecordIds(datasetContext, value, violationType)

      format.toLowerCase match {
        case "csv" =>
          val csvContent = violationRecordService.formatAsCsv(export)
          Ok(csvContent)
            .as("text/csv")
            .withHeaders(
              "Content-Disposition" -> s"attachment; filename=${spec}_problem_records.csv"
            )

        case _ => // Default to JSON
          Ok(Json.toJson(export))
      }
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

  // ====== Mapping Editor Save Endpoints =====

  /**
   * Save mapping from the web-based mapping editor.
   * Accepts JSON format and converts it to mapping XML.
   */
  def saveMappingFromEditor(spec: String) = Action(parse.json) { request =>
    val datasetContext = orgContext.datasetContext(spec)
    val repo = datasetContext.datasetMappingRepo

    try {
      // Extract fields from JSON
      val prefix = (request.body \ "prefix").as[String]
      val schemaVersion = (request.body \ "schemaVersion").asOpt[String].getOrElse("1.0")
      val locked = (request.body \ "locked").asOpt[Boolean].getOrElse(false)
      val description = (request.body \ "description").asOpt[String]

      // Convert JSON to XML
      val mappingXml = convertJsonToMappingXml(request.body)

      // Save to repository
      val version = repo.saveFromEditor(mappingXml, prefix, description)

      // Switch to manual mapping source since we're editing directly
      DsInfo.withDsInfo(spec, orgContext) { dsInfo =>
        dsInfo.setMappingSource("manual", None, None)
      }

      Ok(Json.obj(
        "success" -> true,
        "version" -> Json.obj(
          "hash" -> version.hash,
          "timestamp" -> version.timestamp.toString,
          "source" -> version.source,
          "description" -> version.description
        ),
        "xml" -> mappingXml
      ))
    } catch {
      case e: Exception =>
        logger.error(s"Failed to save mapping from editor for $spec: ${e.getMessage}", e)
        InternalServerError(Json.obj(
          "success" -> false,
          "error" -> e.getMessage
        ))
    }
  }

  /**
   * Convert JSON mapping data back to XML format.
   * This is the reverse of convertMappingToJson.
   */
  private def convertJsonToMappingXml(json: JsValue): String = {
    val prefix = (json \ "prefix").as[String]
    val schemaVersion = (json \ "schemaVersion").asOpt[String].getOrElse("1.0")
    val locked = (json \ "locked").asOpt[Boolean].getOrElse(false)

    // Build facts section
    val facts = (json \ "facts").asOpt[Map[String, String]].getOrElse(Map.empty)
    val factsXml = if (facts.nonEmpty) {
      val entries = facts.map { case (key, value) =>
        s"""    <entry><string>$key</string><string>${escapeXml(value)}</string></entry>"""
      }.mkString("\n")
      s"""  <facts>\n$entries\n  </facts>"""
    } else {
      "  <facts/>"
    }

    // Build functions section
    val functions = (json \ "functions").asOpt[Seq[JsValue]].getOrElse(Seq.empty)
    val functionsXml = if (functions.nonEmpty) {
      val funcs = functions.map { fn =>
        val name = (fn \ "name").as[String]
        val code = (fn \ "code").asOpt[String].getOrElse("")
        val codeLines = code.split("\n").map(line => s"        <string>${escapeXml(line)}</string>").mkString("\n")
        s"""    <mapping-function name="$name">
      <groovy-code>
$codeLines
      </groovy-code>
    </mapping-function>"""
      }.mkString("\n")
      s"""  <functions>\n$funcs\n  </functions>"""
    } else {
      "  <functions/>"
    }

    // Build node-mappings section
    val mappings = (json \ "mappings").asOpt[Seq[JsValue]].getOrElse(Seq.empty)
    val nodeMappingsXml = if (mappings.nonEmpty) {
      val nodes = mappings.map { nm =>
        val inputPath = (nm \ "inputPath").as[String]
        val outputPath = (nm \ "outputPath").as[String]
        val operator = (nm \ "operator").asOpt[String]
        val groovyCode = (nm \ "groovyCode").asOpt[String]
        val documentation = (nm \ "documentation").asOpt[String]
        val siblings = (nm \ "siblings").asOpt[Seq[String]].getOrElse(Seq.empty)

        // Build operator attribute
        val operatorAttr = operator.map(op => s""" operator="$op"""").getOrElse("")

        // Check if we need child elements
        val hasChildren = groovyCode.isDefined || documentation.isDefined || siblings.nonEmpty

        if (!hasChildren) {
          s"""    <node-mapping inputPath="$inputPath" outputPath="$outputPath"$operatorAttr/>"""
        } else {
          val childElements = new StringBuilder()

          // Add siblings if present
          if (siblings.nonEmpty) {
            childElements.append("      <siblings>\n")
            siblings.foreach { path =>
              childElements.append(s"        <path>$path</path>\n")
            }
            childElements.append("      </siblings>\n")
          }

          // Add documentation if present
          documentation.foreach { doc =>
            val docLines = doc.split("\n").map(line => s"        <string>${escapeXml(line)}</string>").mkString("\n")
            childElements.append(s"      <documentation>\n$docLines\n      </documentation>\n")
          }

          // Add groovy code if present
          groovyCode.foreach { code =>
            val codeLines = code.split("\n").map(line => s"        <string>${escapeXml(line)}</string>").mkString("\n")
            childElements.append(s"      <groovy-code>\n$codeLines\n      </groovy-code>\n")
          }

          s"""    <node-mapping inputPath="$inputPath" outputPath="$outputPath"$operatorAttr>
${childElements.toString.stripSuffix("\n")}
    </node-mapping>"""
        }
      }.mkString("\n")
      s"""  <node-mappings>\n$nodes\n  </node-mappings>"""
    } else {
      "  <node-mappings/>"
    }

    // Build complete XML
    s"""<?xml version='1.0' encoding='UTF-8'?>
<rec-mapping prefix="$prefix" schemaVersion="$schemaVersion" locked="$locked">
$factsXml
$functionsXml
  <dyn-opts/>
$nodeMappingsXml
</rec-mapping>
"""
  }

  /**
   * Escape special XML characters in text content.
   */
  private def escapeXml(text: String): String = {
    text
      .replace("&", "&amp;")
      .replace("<", "&lt;")
      .replace(">", "&gt;")
      .replace("\"", "&quot;")
      .replace("'", "&apos;")
  }

  // ====== Mapping Editor Preview Endpoints =====

  /**
   * Get sample records for the mapping editor preview.
   * Returns records as XML strings suitable for preview display.
   */
  def previewSampleRecords(spec: String, count: Int) = Action { request =>
    val datasetContext = orgContext.datasetContext(spec)

    datasetContext.sourceRepoOpt match {
      case None =>
        NotFound(Json.obj("error" -> "No source repository found for dataset"))
      case Some(sourceRepo) =>
        val idFilter = datasetContext.dsInfo.getIdFilter
        val samplePockets = sourceRepo.getSampleRecords(count, idFilter)

        val records = samplePockets.zipWithIndex.map { case (pocket, index) =>
          Json.obj(
            "index" -> index,
            "id" -> pocket.id,
            "xml" -> pocket.text
          )
        }

        Ok(Json.obj(
          "spec" -> spec,
          "totalRecords" -> samplePockets.size,
          "records" -> Json.toJson(records)
        ))
    }
  }

  /**
   * Execute mapping on a sample record and return the transformed output.
   * Used for live preview in the mapping editor.
   */
  def previewMappingExecution(spec: String, recordIndex: Int) = Action { request =>
    val datasetContext = orgContext.datasetContext(spec)

    // Check for source repository
    val sourceRepoOpt = datasetContext.sourceRepoOpt
    if (sourceRepoOpt.isEmpty) {
      NotFound(Json.obj("error" -> "No source repository found for dataset"))
    } else {
      // Check for SIP mapper
      val sipMapperOpt = datasetContext.sipMapperOpt
      if (sipMapperOpt.isEmpty) {
        NotFound(Json.obj("error" -> "No mapping found for dataset. Please ensure a mapping file exists."))
      } else {
        val sourceRepo = sourceRepoOpt.get
        val sipMapper = sipMapperOpt.get
        val idFilter = datasetContext.dsInfo.getIdFilter

        // Get sample records (cache this for efficiency in real usage)
        val samplePockets = sourceRepo.getSampleRecords(recordIndex + 1, idFilter)

        if (recordIndex >= samplePockets.size) {
          NotFound(Json.obj("error" -> s"Record index $recordIndex not found. Only ${samplePockets.size} records available."))
        } else {
          val pocket = samplePockets(recordIndex)

          // Execute mapping
          sipMapper.executeMapping(pocket) match {
            case Success(outputPocket) =>
              Ok(Json.obj(
                "success" -> true,
                "spec" -> spec,
                "recordIndex" -> recordIndex,
                "recordId" -> pocket.id,
                "inputXml" -> pocket.text,
                "outputXml" -> outputPocket.text,
                "mappingPrefix" -> sipMapper.prefix
              ))
            case Failure(ex) =>
              Ok(Json.obj(
                "success" -> false,
                "spec" -> spec,
                "recordIndex" -> recordIndex,
                "recordId" -> pocket.id,
                "inputXml" -> pocket.text,
                "error" -> ex.getMessage,
                "errorType" -> ex.getClass.getSimpleName,
                "mappingPrefix" -> sipMapper.prefix
              ))
          }
        }
      }
    }
  }

  /**
   * Get a specific record by ID and execute mapping on it.
   * Useful for searching/jumping to specific records in the preview.
   */
  def previewMappingById(spec: String, recordId: String) = Action { request =>
    val datasetContext = orgContext.datasetContext(spec)

    datasetContext.sourceRepoOpt match {
      case None =>
        NotFound(Json.obj("error" -> "No source repository found for dataset"))
      case Some(sourceRepo) =>
        datasetContext.sipMapperOpt match {
          case None =>
            NotFound(Json.obj("error" -> "No mapping found for dataset"))
          case Some(sipMapper) =>
            val idFilter = datasetContext.dsInfo.getIdFilter

            sourceRepo.getRecordById(recordId, idFilter) match {
              case None =>
                NotFound(Json.obj("error" -> s"Record with ID '$recordId' not found"))
              case Some(pocket) =>
                sipMapper.executeMapping(pocket) match {
                  case Success(outputPocket) =>
                    Ok(Json.obj(
                      "success" -> true,
                      "spec" -> spec,
                      "recordId" -> pocket.id,
                      "inputXml" -> pocket.text,
                      "outputXml" -> outputPocket.text,
                      "mappingPrefix" -> sipMapper.prefix
                    ))
                  case Failure(ex) =>
                    Ok(Json.obj(
                      "success" -> false,
                      "spec" -> spec,
                      "recordId" -> pocket.id,
                      "inputXml" -> pocket.text,
                      "error" -> ex.getMessage,
                      "errorType" -> ex.getClass.getSimpleName,
                      "mappingPrefix" -> sipMapper.prefix
                    ))
                }
            }
        }
    }
  }

  /**
   * Search through all records for content matching the query.
   * Returns a list of matching record IDs with snippets.
   */
  def searchRecordsByContent(spec: String, query: String, limit: Int) = Action { request =>
    if (query.trim.isEmpty) {
      BadRequest(Json.obj("error" -> "Search query cannot be empty"))
    } else {
      val datasetContext = orgContext.datasetContext(spec)

      datasetContext.sourceRepoOpt match {
        case None =>
          NotFound(Json.obj("error" -> "No source repository found for dataset"))
        case Some(sourceRepo) =>
          val idFilter = datasetContext.dsInfo.getIdFilter
          val matchingRecords = sourceRepo.searchRecordsByContent(query, limit, idFilter)

          val results = matchingRecords.map { pocket =>
            // Create a snippet around the first match
            val lowerText = pocket.text.toLowerCase
            val lowerQuery = query.toLowerCase
            val matchIndex = lowerText.indexOf(lowerQuery)
            val snippetStart = Math.max(0, matchIndex - 50)
            val snippetEnd = Math.min(pocket.text.length, matchIndex + query.length + 50)
            val snippet = (if (snippetStart > 0) "..." else "") +
              pocket.text.substring(snippetStart, snippetEnd) +
              (if (snippetEnd < pocket.text.length) "..." else "")

            Json.obj(
              "id" -> pocket.id,
              "snippet" -> snippet,
              "matchIndex" -> matchIndex
            )
          }

          Ok(Json.obj(
            "spec" -> spec,
            "query" -> query,
            "totalMatches" -> results.size,
            "limit" -> limit,
            "results" -> Json.toJson(results)
          ))
      }
    }
  }

  // ====== Groovy Code Generation Endpoints =====

  /**
   * Generate full Groovy mapping code from the server.
   * Uses sip-core's CodeGenerator to produce the complete mapping code.
   *
   * Request body (optional):
   * {
   *   "mappings": [
   *     { "outputPath": "/rdf:RDF/...", "groovyCode": "custom code here" }
   *   ]
   * }
   *
   * Returns:
   * {
   *   "success": true,
   *   "code": "// full groovy code...",
   *   "prefix": "edm"
   * }
   */
  def generateGroovyCode(spec: String) = Action(parse.json) { request =>
    val datasetContext = orgContext.datasetContext(spec)

    datasetContext.sipRepo.latestSipOpt.flatMap(_.sipMappingOpt) match {
      case None =>
        NotFound(Json.obj("error" -> "No mapping found for dataset. Please ensure a SIP file exists with a valid mapping."))
      case Some(sipMapping) =>
        try {
          // Check for custom groovy code overrides in the request
          val customCodes = (request.body \ "mappings").asOpt[JsArray].map { mappings =>
            mappings.value.flatMap { mapping =>
              val outputPath = (mapping \ "outputPath").asOpt[String]
              val groovyCode = (mapping \ "groovyCode").asOpt[String]
              (outputPath, groovyCode) match {
                case (Some(path), Some(code)) => Some(path -> code)
                case _ => None
              }
            }.toMap
          }.getOrElse(Map.empty)

          // Apply custom codes to the recMapping's node mappings
          val recMapping = sipMapping.recMapping
          if (customCodes.nonEmpty) {
            // Create a map of output path -> custom code for lookup during generation
            // The CodeGenerator can use EditPath for this, but for full code generation
            // we need to modify the node mappings' groovyCode directly (temporarily)
            for ((pathStr, code) <- customCodes) {
              val path = Path.create(pathStr)
              val nodeMapping = recMapping.getRecDefTree.getRecDefNode(path)
              if (nodeMapping != null && nodeMapping.getNodeMappings != null) {
                nodeMapping.getNodeMappings.values().asScala.foreach { nm =>
                  nm.groovyCode = eu.delving.metadata.StringUtil.stringToLines(code)
                }
              }
            }
          }

          val codeGenerator = new CodeGenerator(recMapping).withTrace(false)
          val code = codeGenerator.toRecordMappingCode()

          Ok(Json.obj(
            "success" -> true,
            "code" -> code,
            "prefix" -> sipMapping.prefix,
            "spec" -> spec
          ))
        } catch {
          case ex: Exception =>
            logger.error(s"Failed to generate Groovy code for $spec", ex)
            InternalServerError(Json.obj(
              "error" -> s"Failed to generate code: ${ex.getMessage}",
              "errorType" -> ex.getClass.getSimpleName
            ))
        }
    }
  }

  /**
   * Generate Groovy code for a single mapping.
   * Uses sip-core's CodeGenerator with EditPath to generate code for just one node mapping.
   *
   * Request body:
   * {
   *   "mapping": {
   *     "inputPath": "/record/title",
   *     "outputPath": "/rdf:RDF/edm:ProvidedCHO/dc:title",
   *     "groovyCode": "optional custom code"
   *   }
   * }
   *
   * Returns:
   * {
   *   "success": true,
   *   "code": "// node mapping code...",
   *   "isGenerated": true/false
   * }
   */
  def generateMappingCode(spec: String) = Action(parse.json) { request =>
    val datasetContext = orgContext.datasetContext(spec)

    // Extract mapping info from request
    val mappingOpt = (request.body \ "mapping").asOpt[JsObject]
    if (mappingOpt.isEmpty) {
      BadRequest(Json.obj("error" -> "Missing 'mapping' object in request body"))
    } else {
      val mapping = mappingOpt.get
      val outputPath = (mapping \ "outputPath").asOpt[String]
      val groovyCode = (mapping \ "groovyCode").asOpt[String]

      if (outputPath.isEmpty) {
        BadRequest(Json.obj("error" -> "Missing 'outputPath' in mapping"))
      } else {
        datasetContext.sipRepo.latestSipOpt.flatMap(_.sipMappingOpt) match {
          case None =>
            NotFound(Json.obj("error" -> "No mapping found for dataset"))
          case Some(sipMapping) =>
            try {
              val recMapping = sipMapping.recMapping
              val path = Path.create(outputPath.get)

              // Find the node mapping at this path
              val recDefNode = recMapping.getRecDefTree.getRecDefNode(path)
              if (recDefNode == null || recDefNode.getNodeMappings.isEmpty) {
                // No mapping exists at this path - return generated skeleton
                Ok(Json.obj(
                  "success" -> true,
                  "code" -> s"// No mapping defined for ${outputPath.get}",
                  "isGenerated" -> true,
                  "hasMapping" -> false
                ))
              } else {
                // Get the first node mapping (usually there's only one per output path)
                val nodeMapping = recDefNode.getNodeMappings.values().asScala.head

                // Create EditPath to generate code for just this mapping
                val editedCode = groovyCode.orNull
                val editPath = new EditPath(nodeMapping, editedCode)

                val codeGenerator = new CodeGenerator(recMapping)
                  .withEditPath(editPath)
                  .withTrace(false)

                val code = codeGenerator.toNodeMappingCode()
                val isGenerated = groovyCode.isEmpty && (nodeMapping.groovyCode == null || nodeMapping.groovyCode.isEmpty)

                Ok(Json.obj(
                  "success" -> true,
                  "code" -> code,
                  "isGenerated" -> isGenerated,
                  "hasMapping" -> true,
                  "inputPath" -> (if (nodeMapping.inputPath != null) nodeMapping.inputPath.toString else JsNull),
                  "outputPath" -> outputPath.get
                ))
              }
            } catch {
              case ex: Exception =>
                logger.error(s"Failed to generate mapping code for $spec at ${outputPath.get}", ex)
                InternalServerError(Json.obj(
                  "error" -> s"Failed to generate code: ${ex.getMessage}",
                  "errorType" -> ex.getClass.getSimpleName
                ))
            }
        }
      }
    }
  }

  /**
   * Preview mapping code execution.
   * Executes the provided Groovy code against a sample record and returns the result.
   *
   * Request body:
   * {
   *   "mapping": {
   *     "inputPath": "/record/title",
   *     "outputPath": "/rdf:RDF/edm:ProvidedCHO/dc:title",
   *     "groovyCode": "optional custom code"
   *   },
   *   "recordIndex": 0
   * }
   *
   * Returns:
   * {
   *   "success": true,
   *   "inputValue": "...",
   *   "outputValue": "...",
   *   "inputXml": "...",
   *   "outputXml": "..."
   * }
   */
  def previewMappingCode(spec: String) = Action(parse.json) { request =>
    val datasetContext = orgContext.datasetContext(spec)
    val recordIndex = (request.body \ "recordIndex").asOpt[Int].getOrElse(0)
    val mappingOpt = (request.body \ "mapping").asOpt[JsObject]

    datasetContext.sourceRepoOpt match {
      case None =>
        NotFound(Json.obj("error" -> "No source repository found for dataset"))
      case Some(sourceRepo) =>
        datasetContext.sipRepo.latestSipOpt.flatMap(_.sipMappingOpt) match {
          case None =>
            NotFound(Json.obj("error" -> "No mapping found for dataset"))
          case Some(sipMapping) =>
            try {
              val idFilter = datasetContext.dsInfo.getIdFilter
              val samplePockets = sourceRepo.getSampleRecords(recordIndex + 1, idFilter)

              if (recordIndex >= samplePockets.size) {
                NotFound(Json.obj("error" -> s"Record index $recordIndex not found"))
              } else {
                val pocket = samplePockets(recordIndex)
                val recMapping = sipMapping.recMapping

                // If custom mapping code is provided, use it
                val codeToExecute = mappingOpt.flatMap { mapping =>
                  val outputPath = (mapping \ "outputPath").asOpt[String]
                  val groovyCode = (mapping \ "groovyCode").asOpt[String]

                  (outputPath, groovyCode) match {
                    case (Some(pathStr), Some(code)) if code.nonEmpty =>
                      // Apply the custom code and regenerate
                      val path = Path.create(pathStr)
                      val recDefNode = recMapping.getRecDefTree.getRecDefNode(path)
                      if (recDefNode != null && !recDefNode.getNodeMappings.isEmpty) {
                        val nodeMapping = recDefNode.getNodeMappings.values().asScala.head
                        val editPath = new EditPath(nodeMapping, code)
                        val codeGenerator = new CodeGenerator(recMapping)
                          .withEditPath(editPath)
                          .withTrace(false)
                        // For preview, we need the full record mapping code, not just node code
                        Some(codeGenerator.toRecordMappingCode())
                      } else {
                        None
                      }
                    case _ => None
                  }
                }.getOrElse {
                  // Use the default generated code
                  new CodeGenerator(recMapping).withTrace(false).toRecordMappingCode()
                }

                // Execute the mapping
                val runner = new BulkMappingRunner(recMapping, codeToExecute)
                val namespaces = sipMapping.recDefTree.getRecDef.namespaces.asScala.map(ns => ns.prefix -> ns.uri).toMap
                val factory = new MetadataRecordFactory(namespaces.asJava)
                val serializer = new XmlSerializer

                try {
                  val metadataRecord = factory.metadataRecordFrom(pocket.getText)
                  val result = new MappingResult(serializer, pocket.id, runner.runMapping(metadataRecord), recMapping.getRecDefTree)
                  val outputXml = serializer.toXml(result.root(), true)

                  // Extract input/output values if mapping info provided
                  val inputPath = mappingOpt.flatMap(m => (m \ "inputPath").asOpt[String])
                  val outputPath = mappingOpt.flatMap(m => (m \ "outputPath").asOpt[String])
                  val groovyCode = mappingOpt.flatMap(m => (m \ "groovyCode").asOpt[String])

                  // Extract variable bindings from the Groovy code
                  val variableBindings: Map[String, String] = groovyCode.map { code =>
                    extractVariableBindings(code, metadataRecord.getRootNode)
                  }.getOrElse(Map.empty)

                  Ok(Json.obj(
                    "success" -> true,
                    "spec" -> spec,
                    "recordIndex" -> recordIndex,
                    "recordId" -> pocket.id,
                    "inputXml" -> pocket.text,
                    "outputXml" -> outputXml,
                    "mappingPrefix" -> sipMapping.prefix,
                    "inputPath" -> (inputPath match { case Some(p) => JsString(p) case None => JsNull }),
                    "outputPath" -> (outputPath match { case Some(p) => JsString(p) case None => JsNull }),
                    "variableBindings" -> Json.toJson(variableBindings)
                  ))
                } catch {
                  case ex: Exception =>
                    Ok(Json.obj(
                      "success" -> false,
                      "spec" -> spec,
                      "recordIndex" -> recordIndex,
                      "recordId" -> pocket.id,
                      "inputXml" -> pocket.text,
                      "error" -> ex.getMessage,
                      "errorType" -> ex.getClass.getSimpleName,
                      "mappingPrefix" -> sipMapping.prefix
                    ))
                }
              }
            } catch {
              case ex: Exception =>
                logger.error(s"Failed to preview mapping code for $spec", ex)
                InternalServerError(Json.obj(
                  "error" -> s"Failed to execute preview: ${ex.getMessage}",
                  "errorType" -> ex.getClass.getSimpleName
                ))
            }
        }
    }
  }

  /**
   * Upload a processed file from an external source (e.g., sip-creator).
   * This allows bypassing the processing step in narthex when the same
   * processing has already been done externally.
   *
   * Request: POST /narthex/app/dataset/:spec/upload-processed
   * Content-Type: multipart/form-data
   * Body:
   *   - file: ZSTD compressed RDF/XML file (required)
   *   - errorFile: Error log file (optional)
   *   - validCount: Number of valid records (required)
   *   - invalidCount: Number of invalid records (required)
   *   - source: Source of the processed file (optional, defaults to "sip-creator")
   *
   * Response: { "success": true, "validCount": 12345, "invalidCount": 5 }
   */
  def uploadProcessedFile(spec: String) = Action(parse.multipartFormData) { request =>
    import java.nio.file.{Files, StandardCopyOption}
    import dataset.DsInfo.DsState._

    val datasetContext = orgContext.datasetContext(spec)

    request.body.file("file").map { filePart =>
      try {
        val uploadedFile = filePart.ref.path.toFile

        // Get counts from form data (sent by sip-creator)
        val validCount = request.body.dataParts.get("validCount")
          .flatMap(_.headOption).map(_.toInt).getOrElse(0)
        val invalidCount = request.body.dataParts.get("invalidCount")
          .flatMap(_.headOption).map(_.toInt).getOrElse(0)
        val source = request.body.dataParts.get("source")
          .flatMap(_.headOption).getOrElse("sip-creator")

        logger.info(s"Uploading processed file for $spec: validCount=$validCount, invalidCount=$invalidCount, source=$source")

        // 1. Clear existing processed data
        datasetContext.processedRepo.clear()

        // 2. Copy processed file to processed/00000.xml.zst
        val output = datasetContext.processedRepo.createOutput
        Files.copy(uploadedFile.toPath, output.xmlFile.toPath, StandardCopyOption.REPLACE_EXISTING)
        logger.info(s"Copied processed file to ${output.xmlFile.getAbsolutePath}")

        // 3. Handle optional error file
        request.body.file("errorFile").foreach { errorPart =>
          Files.copy(errorPart.ref.path, output.errorFile.toPath, StandardCopyOption.REPLACE_EXISTING)
          logger.info(s"Copied error file to ${output.errorFile.getAbsolutePath}")
        }

        // 4. Update dataset state - mark as processed externally
        datasetContext.dsInfo.setState(PROCESSED)
        datasetContext.dsInfo.setProcessedRecordCounts(validCount, invalidCount)
        datasetContext.dsInfo.setSingularLiteralProps(
          processedExternally -> source
        )

        // 5. Clear stale analysis if present (analysis is stale after new processing)
        datasetContext.dsInfo.removeState(ANALYZED)

        // 6. Broadcast update via WebSocket so UI refreshes
        sendRefresh(spec)

        logger.info(s"Successfully uploaded processed file for $spec")
        Ok(Json.obj(
          "success" -> true,
          "validCount" -> validCount,
          "invalidCount" -> invalidCount,
          "source" -> source
        ))
      } catch {
        case e: Exception =>
          logger.error(s"Failed to upload processed file for $spec: ${e.getMessage}", e)
          InternalServerError(Json.obj(
            "success" -> false,
            "error" -> e.getMessage
          ))
      }
    }.getOrElse {
      BadRequest(Json.obj("success" -> false, "error" -> "No file provided"))
    }
  }

  // Memory monitoring endpoints
  def memoryStats = Action {
    import memoryMonitorService.MemoryStats._
    Ok(Json.toJson(memoryMonitorService.getStats))
  }

  def resetMemoryStats = Action {
    memoryMonitorService.resetStats()
    Ok(Json.obj("success" -> true, "message" -> "Memory statistics reset"))
  }

  def forceGC = Action {
    val freedMB = memoryMonitorService.forceGC()
    Ok(Json.obj("success" -> true, "freedMB" -> freedMB))
  }

  /**
   * Extract variable bindings from Groovy code by parsing variable references
   * and looking up their values in the source record.
   *
   * Variables in Groovy code follow the pattern _varName (e.g., _title, _dc_creator, _image)
   * These map back to XML tags (title, dc:creator, image)
   */
  private def extractVariableBindings(groovyCode: String, rootNode: eu.delving.groovy.GroovyNode): Map[String, String] = {
    // Regex to find variable references like _title, _dc_creator, _image, etc.
    // Matches underscore followed by word characters, but not special vars like _input, _facts, _absent_, _optLookup
    val variablePattern = """_([a-zA-Z][a-zA-Z0-9_]*)""".r
    val specialVars = Set("input", "facts", "absent_", "optLookup", "uniqueIdentifier", "M1", "M2", "M3", "M4", "M5")

    // Find all unique variable names
    val variableNames = variablePattern.findAllMatchIn(groovyCode).map(_.group(1)).toSet
      .filterNot(v => specialVars.contains(v) || v.startsWith("M"))

    // Convert variable names back to tag names and look up values
    variableNames.flatMap { varName =>
      // Convert underscore back to colon for namespace prefixes (e.g., dc_title -> dc:title)
      val tagName = if (varName.contains("_")) {
        // Check if it looks like a namespace prefix (e.g., dc_title, edm_type)
        val parts = varName.split("_", 2)
        if (parts.length == 2 && parts(0).length <= 4) {
          // Likely a namespace prefix
          s"${parts(0)}:${parts(1)}"
        } else {
          varName
        }
      } else {
        varName
      }

      // Try to find the value in the source record
      try {
        val values = rootNode.getValueNodes(tagName).asScala.toSeq
        if (values.nonEmpty) {
          // Get the text values, joining multiple if present
          val textValues = values.flatMap { obj =>
            val node = obj.asInstanceOf[eu.delving.groovy.GroovyNode]
            Option(node.text()).filter(_.trim.nonEmpty)
          }
          if (textValues.nonEmpty) {
            Some(s"_$varName" -> textValues.mkString("; "))
          } else {
            Some(s"_$varName" -> "(empty)")
          }
        } else {
          // Try without namespace
          val simpleValues = rootNode.getValueNodes(varName).asScala.toSeq
          if (simpleValues.nonEmpty) {
            val textValues = simpleValues.flatMap { obj =>
              val node = obj.asInstanceOf[eu.delving.groovy.GroovyNode]
              Option(node.text()).filter(_.trim.nonEmpty)
            }
            if (textValues.nonEmpty) {
              Some(s"_$varName" -> textValues.mkString("; "))
            } else {
              Some(s"_$varName" -> "(empty)")
            }
          } else {
            Some(s"_$varName" -> "(not found)")
          }
        }
      } catch {
        case _: Exception => Some(s"_$varName" -> "(error)")
      }
    }.toMap
  }

}

