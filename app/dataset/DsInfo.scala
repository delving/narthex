//===========================================================================
//
//    Copyright 2015 Delving B.V.
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

package dataset

import java.io.{File, FileInputStream, StringWriter}
import com.github.luben.zstd.ZstdInputStream

import dataset.SourceRepo.{IdFilter, VERBATIM_FILTER}
import harvest.Harvesting.{HarvestCron, HarvestType}
import mapping.{SkosVocabulary, TermMappingStore, VocabInfo}
import organization.OrgActor.DatasetMessage
import org.apache.jena.rdf.model._
import org.apache.jena.riot.{RDFDataMgr, RDFFormat}
import org.joda.time.{DateTime, Minutes}
import organization.OrgContext
import play.api.Logger
import play.api.libs.json.{JsObject, JsValue, Json, Writes}
import play.api.libs.ws.WSResponse
import services.StringHandling.{createGraphName, urlEncodeValue}
import services.Temporal._
import services.TrendTrackingService
import triplestore.GraphProperties._
import triplestore.Sparql._
import triplestore.{GraphProperties, SkosGraph, TripleStore}

import scala.jdk.CollectionConverters._
import scala.concurrent._
import scala.concurrent.duration._
import scala.util.{Failure, Success}
import scala.io.Source

object DsInfo {

  private val logger = Logger(getClass)

  val patience = 1.minute

  val cacheTime = 10.minutes

  case class DsCharacter(name: String)

  val CharacterMapped = DsCharacter("character-mapped")

  def getCharacter(characterString: String) =
    List(CharacterMapped).find(_.name == characterString)

  case class DsState(prop: NXProp) {
    override def toString = prop.name
  }

  object DsState extends Enumeration {

    type DsState = Value

    val EMPTY = Value("stateEmpty")
    val DISABLED = Value("stateDisabled")
    val RAW = Value("stateRaw")
    val RAW_ANALYZED = Value("stateRawAnalyzed")
    val SOURCED = Value("stateSourced")
    val SOURCE_ANALYZED = Value("stateSourceAnalyzed")
    val MAPPABLE = Value("stateMappable")
    val PROCESSABLE = Value("stateProcessable")
    val PROCESSED = Value("stateProcessed")
    val ANALYZED = Value("stateAnalyzed")
    val SAVED = Value("stateSaved")
    val INCREMENTAL_SAVED = Value("stateIncrementalSaved")
    val SYNCED = Value("stateSynced")
  }

  case class DsMetadata(name: String,
                        description: String,
                        aggregator: String,
                        owner: String,
                        dataProviderURL: String,
                        language: String,
                        rights: String,
                        dataType: String,
                        edmType: String)

  implicit val dsInfoWrites: Writes[DsInfo] = new Writes[DsInfo] {
    // Phase D2 (decision 2026-07-10): flat JSON straight from the props
    // row — the JSON-LD/Jena serialization is gone with Fuseki.
    def writes(dsInfo: DsInfo): JsValue = {
      import play.api.libs.json.{JsObject => PJsObject, JsString => PJsString}
      val props = dsInfo.orgContext.datasetsDb.props(dsInfo.spec)
      PJsObject(
        List("datasetSpec" -> PJsString(dsInfo.spec), "uri" -> PJsString(dsInfo.uri)) ++
          props.toList.sortBy(_._1).map { case (k, v) => k -> (PJsString(v): JsValue) }
      )
    }
  }

  /**
   * Exception thrown when Hub3 bulk API rejects a request.
   * Contains structured error information for better debugging and user feedback.
   *
   * @param message Human-readable error message
   * @param statusCode HTTP status code from Hub3
   * @param hub3ErrorMessage Error message extracted from Hub3 response (if parseable)
   * @param hub3FullResponse Full response body from Hub3 for debugging
   * @param affectedRecordIds List of record IDs that were in the failed batch (if extractable)
   */
  case class Hub3BulkApiException(
    message: String,
    statusCode: Int,
    hub3ErrorMessage: Option[String],
    hub3FullResponse: String,
    affectedRecordIds: scala.collection.immutable.Seq[String]
  ) extends Exception(message)

  /**
   * Extract record IDs (hubId) from bulk action JSON lines.
   * Each line is a JSON object with a "hubId" field.
   */
  def extractRecordIdsFromBulkActions(bulkActions: String, maxIds: Int = 10): scala.collection.immutable.Seq[String] = {
    bulkActions.split("\n")
      .filter(_.nonEmpty)
      .take(maxIds)
      .flatMap { line =>
        try {
          (Json.parse(line) \ "hubId").asOpt[String]
        } catch {
          case _: Exception => None
        }
      }
      .toSeq
  }

  /**
   * Parse Hub3 error response to extract meaningful error message.
   * Hub3 can return errors in various formats, this tries to handle them all.
   */
  def parseHub3ErrorResponse(responseBody: String): Option[String] = {
    try {
      val json = Json.parse(responseBody)

      // Extract error and detail fields - detail usually contains the actual parsing error
      val errorField = (json \ "error").asOpt[String]
      val detailField = (json \ "detail").asOpt[String]

      // Combine error and detail if both present (detail has the specific error like "invalid language tag")
      (errorField, detailField) match {
        case (Some(error), Some(detail)) =>
          Some(s"$error - $detail")
        case (Some(error), None) =>
          Some(error)
        case (None, Some(detail)) =>
          Some(detail)
        case (None, None) =>
          // Fall back to other common formats
          (json \ "error" \ "message").asOpt[String]
            .orElse((json \ "message").asOpt[String])
            .orElse((json \ "errors").asOpt[scala.collection.immutable.Seq[String]].map(_.mkString("; ")))
      }
    } catch {
      case _: Exception => None
    }
  }

  // ============================================================================
  // Field Registry for WebSocket Serialization
  // ============================================================================
  //
  // This is the SINGLE SOURCE OF TRUTH for fields that should appear in WebSocket
  // updates (toSimpleJson). Adding a new field to WebSocket updates requires:
  // 1. Add the GraphProperty import
  // 2. Add a FieldSpec entry to webSocketFields below
  //
  // Tests in DsInfoSerializationSpec validate that all required fields are covered.
  // ============================================================================

  /** Sealed trait for field specifications in WebSocket serialization */
  sealed trait FieldSpec {
    def jsonName: String
    def getValue(dsInfo: DsInfo): Option[JsValue]
  }

  /** String field - serializes as JsString */
  case class StringField(jsonName: String, prop: NXProp) extends FieldSpec {
    def getValue(dsInfo: DsInfo): Option[JsValue] =
      dsInfo.getLiteralProp(prop).map(v => play.api.libs.json.JsString(v))
  }

  /** Integer field - serializes as JsNumber, returns None if parse fails */
  case class IntField(jsonName: String, prop: NXProp) extends FieldSpec {
    def getValue(dsInfo: DsInfo): Option[JsValue] =
      dsInfo.getLiteralProp(prop).flatMap(v => scala.util.Try(play.api.libs.json.JsNumber(v.toInt)).toOption)
  }

  /** Boolean field - serializes as JsBoolean */
  case class BoolField(jsonName: String, prop: NXProp) extends FieldSpec {
    def getValue(dsInfo: DsInfo): Option[JsValue] =
      dsInfo.getLiteralProp(prop).flatMap(v => scala.util.Try(play.api.libs.json.JsBoolean(v.toBoolean)).toOption)
  }

  /** NonEmpty field - returns JsBoolean(true/false) based on whether value is non-empty */
  case class NonEmptyField(jsonName: String, prop: NXProp) extends FieldSpec {
    def getValue(dsInfo: DsInfo): Option[JsValue] =
      dsInfo.getLiteralProp(prop).map(v => play.api.libs.json.JsBoolean(v.nonEmpty))
  }

  /**
   * Registry of all fields to include in WebSocket updates (toSimpleJson).
   *
   * Grouped by category for maintainability. When adding new fields:
   * 1. Add the GraphProperty to the appropriate group
   * 2. Use the correct FieldSpec type (StringField, IntField, BoolField, NonEmptyField)
   */
  val webSocketFields: scala.collection.immutable.Seq[FieldSpec] = {
    import triplestore.GraphProperties._

    scala.collection.immutable.Seq(
      // Dataset metadata
      StringField("datasetName", datasetName),
      StringField("datasetDescription", datasetDescription),
      StringField("datasetAggregator", datasetAggregator),
      StringField("datasetOwner", datasetOwner),
      StringField("datasetLanguage", datasetLanguage),
      StringField("datasetRights", datasetRights),
      StringField("datasetType", datasetType),
      StringField("datasetTags", datasetTags),
      StringField("edmType", edmType),
      StringField("datasetDataProviderURL", datasetDataProviderURL),

      // Record counts
      IntField("datasetRecordCount", datasetRecordCount),
      IntField("processedValid", processedValid),
      IntField("processedInvalid", processedInvalid),
      IntField("processedIncrementalValid", processedIncrementalValid),
      IntField("processedIncrementalInvalid", processedIncrementalInvalid),
      StringField("processedExternally", processedExternally),
      IntField("acquiredRecordCount", acquiredRecordCount),
      IntField("deletedRecordCount", deletedRecordCount),
      IntField("sourceRecordCount", sourceRecordCount),
      StringField("acquisitionMethod", acquisitionMethod),

      // State timestamps
      StringField("stateDisabled", stateDisabled),
      StringField("stateRaw", stateRaw),
      StringField("stateRawAnalyzed", stateRawAnalyzed),
      StringField("stateSourced", stateSourced),
      StringField("stateSourceAnalyzed", stateSourceAnalyzed),
      StringField("stateMappable", stateMappable),
      StringField("stateProcessable", stateProcessable),
      StringField("stateAnalyzed", stateAnalyzed),
      StringField("stateProcessed", stateProcessed),
      StringField("stateSaved", stateSaved),
      StringField("stateIncrementalSaved", stateIncrementalSaved),

      // Operation status
      StringField("currentOperation", datasetCurrentOperation),
      StringField("operationStatus", datasetOperationStatus),
      StringField("datasetErrorMessage", datasetErrorMessage),

      // Delimiters
      StringField("delimitersSet", delimitersSet),
      StringField("recordRoot", recordRoot),
      StringField("uniqueId", uniqueId),

      // Harvest configuration
      StringField("harvestType", harvestType),
      StringField("harvestURL", harvestURL),
      StringField("harvestDataset", harvestDataset),
      StringField("harvestPrefix", harvestPrefix),
      StringField("harvestSearch", harvestSearch),
      StringField("harvestRecord", harvestRecord),
      StringField("harvestDownloadURL", harvestDownloadURL),
      StringField("harvestIncrementalMode", harvestIncrementalMode),
      StringField("harvestDelay", harvestDelay),
      StringField("harvestDelayUnit", harvestDelayUnit),
      StringField("harvestPreviousTime", harvestPreviousTime),
      StringField("harvestIncremental", harvestIncremental),
      BoolField("harvestContinueOnError", harvestContinueOnError),
      BoolField("harvestDateOnly", harvestDateOnly),
      IntField("harvestErrorThreshold", harvestErrorThreshold),

      // Harvest credentials
      StringField("harvestUsername", harvestUsername),
      NonEmptyField("harvestPasswordSet", harvestPassword),
      NonEmptyField("harvestApiKeySet", harvestApiKey),
      StringField("harvestApiKeyParam", harvestApiKeyParam),

      // JSON harvest fields
      StringField("harvestJsonItemsPath", harvestJsonItemsPath),
      StringField("harvestJsonIdPath", harvestJsonIdPath),
      StringField("harvestJsonTotalPath", harvestJsonTotalPath),
      StringField("harvestJsonPageParam", harvestJsonPageParam),
      StringField("harvestJsonPageSizeParam", harvestJsonPageSizeParam),
      IntField("harvestJsonPageSize", harvestJsonPageSize),
      StringField("harvestJsonDetailPath", harvestJsonDetailPath),
      BoolField("harvestJsonSkipDetail", harvestJsonSkipDetail),
      StringField("harvestJsonXmlRoot", harvestJsonXmlRoot),
      StringField("harvestJsonXmlRecord", harvestJsonXmlRecord),

      // Harvest retry status
      BoolField("harvestInRetry", harvestInRetry),
      IntField("harvestRetryCount", harvestRetryCount),
      StringField("harvestLastRetryTime", harvestLastRetryTime),
      StringField("harvestRetryMessage", harvestRetryMessage),

      // ID filter
      StringField("idFilterType", idFilterType),
      StringField("idFilterExpression", idFilterExpression),

      // Publish flags
      BoolField("publishOAIPMH", publishOAIPMH),
      BoolField("publishIndex", publishIndex),
      BoolField("publishLOD", publishLOD),

      // Indexing results from Hub3 webhook
      IntField("indexingRecordsIndexed", indexingRecordsIndexed),
      IntField("indexingRecordsExpected", indexingRecordsExpected),
      IntField("indexingOrphansDeleted", indexingOrphansDeleted),
      IntField("indexingErrorCount", indexingErrorCount),
      StringField("indexingLastStatus", indexingLastStatus),
      StringField("indexingLastMessage", indexingLastMessage),
      StringField("indexingLastTimestamp", indexingLastTimestamp),
      IntField("indexingLastRevision", indexingLastRevision)
    )
  }

  /**
   * Lightweight dataset info containing only fields needed for collapsed list view.
   * This avoids expensive RDF model serialization for the initial dataset list load.
   */
  case class DsInfoLight(
    spec: String,
    name: Option[String],
    processedValid: Option[Int],
    processedInvalid: Option[Int],
    recordCount: Option[Int],
    // Acquisition tracking fields
    acquiredRecordCount: Option[Int],
    deletedRecordCount: Option[Int],
    sourceRecordCount: Option[Int],
    acquisitionMethod: Option[String],
    // State fields
    stateDisabled: Option[String],
    stateRaw: Option[String],
    stateRawAnalyzed: Option[String],
    stateSourced: Option[String],
    stateMappable: Option[String],
    stateProcessable: Option[String],
    stateAnalyzed: Option[String],
    stateProcessed: Option[String],
    stateSaved: Option[String],
    stateIncrementalSaved: Option[String],
    currentOperation: Option[String],
    operationStatus: Option[String],
    errorMessage: Option[String],
    errorTime: Option[String],
    harvestType: Option[String],
    harvestDownloadURL: Option[String],
    harvestIncrementalMode: Option[String],
    processedIncrementalValid: Option[Int],
    processedIncrementalInvalid: Option[Int],
    delimitersSet: Option[String],
    recordRootValue: Option[String],
    uniqueIdValue: Option[String],
    mappingSource: Option[String],
    harvestUsername: Option[String],
    harvestPasswordSet: Option[Boolean],
    harvestApiKeySet: Option[Boolean],
    mapToPrefix: Option[String]
  )

  implicit val dsInfoLightWrites: Writes[DsInfoLight] = new Writes[DsInfoLight] {
    def writes(ds: DsInfoLight): JsValue = Json.obj(
      "spec" -> ds.spec,
      "name" -> ds.name,
      "processedValid" -> ds.processedValid,
      "processedInvalid" -> ds.processedInvalid,
      "recordCount" -> ds.recordCount,
      // Acquisition tracking fields
      "acquiredRecordCount" -> ds.acquiredRecordCount,
      "deletedRecordCount" -> ds.deletedRecordCount,
      "sourceRecordCount" -> ds.sourceRecordCount,
      "acquisitionMethod" -> ds.acquisitionMethod,
      // State fields
      "stateDisabled" -> ds.stateDisabled,
      "stateRaw" -> ds.stateRaw,
      "stateRawAnalyzed" -> ds.stateRawAnalyzed,
      "stateSourced" -> ds.stateSourced,
      "stateMappable" -> ds.stateMappable,
      "stateProcessable" -> ds.stateProcessable,
      "stateAnalyzed" -> ds.stateAnalyzed,
      "stateProcessed" -> ds.stateProcessed,
      "stateSaved" -> ds.stateSaved,
      "stateIncrementalSaved" -> ds.stateIncrementalSaved,
      "currentOperation" -> ds.currentOperation,
      "operationStatus" -> ds.operationStatus,
      "errorMessage" -> ds.errorMessage,
      "errorTime" -> ds.errorTime,
      "harvestType" -> ds.harvestType,
      "harvestDownloadURL" -> ds.harvestDownloadURL,
      "harvestIncrementalMode" -> ds.harvestIncrementalMode,
      "processedIncrementalValid" -> ds.processedIncrementalValid,
      "processedIncrementalInvalid" -> ds.processedIncrementalInvalid,
      "delimitersSet" -> ds.delimitersSet,
      "recordRoot" -> ds.recordRootValue,
      "uniqueId" -> ds.uniqueIdValue,
      "mappingSource" -> ds.mappingSource,
      "harvestUsername" -> ds.harvestUsername,
      "harvestPasswordSet" -> ds.harvestPasswordSet,
      "harvestApiKeySet" -> ds.harvestApiKeySet
    )
  }

  /**
   * Lightweight retry status for datasets in retry mode.
   */
  case class RetryStatus(
    spec: String,
    retryCount: Option[Int],
    lastRetryTime: Option[String],
    retryMessage: Option[String]
  )

  /**
   * List datasets in retry mode with lightweight data.
   */

  /**
   * List all datasets with minimal data for initial page load.
   * Only fetches ~10 essential fields instead of full RDF models.
   */
  def listDsInfoLight(orgContext: OrgContext)(
      implicit ec: ExecutionContext,
      ts: TripleStore): Future[List[DsInfoLight]] = Future.successful {
    import triplestore.GraphProperties._
    orgContext.datasetsDb.allProps().toList.sortBy(_._1).map { case (spec, p) =>
      def s(prop: NXProp): Option[String] = p.get(prop.name)
      def i(prop: NXProp): Option[Int] = p.get(prop.name).flatMap(v => scala.util.Try(v.toInt).toOption)
      DsInfoLight(
        spec = spec,
        name = s(datasetName),
        processedValid = i(processedValid),
        processedInvalid = i(processedInvalid),
        recordCount = i(datasetRecordCount),
        acquiredRecordCount = i(acquiredRecordCount),
        deletedRecordCount = i(deletedRecordCount),
        sourceRecordCount = i(sourceRecordCount),
        acquisitionMethod = s(acquisitionMethod),
        stateDisabled = s(stateDisabled),
        stateRaw = s(stateRaw),
        stateRawAnalyzed = s(stateRawAnalyzed),
        stateSourced = s(stateSourced),
        stateMappable = s(stateMappable),
        stateProcessable = s(stateProcessable),
        stateAnalyzed = s(stateAnalyzed),
        stateProcessed = s(stateProcessed),
        stateSaved = s(stateSaved),
        stateIncrementalSaved = s(stateIncrementalSaved),
        currentOperation = s(datasetCurrentOperation),
        operationStatus = s(datasetOperationStatus),
        errorMessage = s(datasetErrorMessage),
        errorTime = s(datasetErrorTime),
        harvestType = s(harvestType),
        harvestDownloadURL = s(harvestDownloadURL),
        harvestIncrementalMode = s(harvestIncrementalMode),
        processedIncrementalValid = i(processedIncrementalValid),
        processedIncrementalInvalid = i(processedIncrementalInvalid),
        delimitersSet = s(delimitersSet),
        recordRootValue = s(recordRoot),
        uniqueIdValue = s(uniqueId),
        mappingSource = s(datasetMappingSource),
        harvestUsername = s(harvestUsername),
        harvestPasswordSet = p.get(harvestPassword.name).map(_.nonEmpty),
        harvestApiKeySet = p.get(harvestApiKey.name).map(_.nonEmpty),
        mapToPrefix = s(datasetMapToPrefix)
      )
    }
  }

  def listRetryStatus(orgContext: OrgContext)(
      implicit ec: ExecutionContext): Future[Map[String, RetryStatus]] = Future.successful {
    import triplestore.GraphProperties._
    orgContext.datasetsDb.allProps().collect {
      case (spec, p) if p.get(harvestInRetry.name).contains("true") && !p.contains(stateDisabled.name) =>
        spec -> RetryStatus(
          spec = spec,
          retryCount = p.get(harvestRetryCount.name).flatMap(v => scala.util.Try(v.toInt).toOption),
          lastRetryTime = p.get(harvestLastRetryTime.name),
          retryMessage = p.get(harvestRetryMessage.name)
        )
    }
  }

  /**
   * List datasets with retry status and trend data merged in.
   * Runs both queries in parallel and merges results.
   */
  def listDsInfoLightWithRetry(orgContext: OrgContext)(
      implicit ec: ExecutionContext,
      ts: TripleStore): Future[List[JsValue]] = {
    for {
      datasets <- listDsInfoLight(orgContext)
      retryStatus <- listRetryStatus(orgContext)
    } yield {
      datasets.map { ds =>
        // Phase A4b: lifecycle state comes from the projector (disk +
        // registry truth); the stored state props in the SPARQL row serve
        // only as legacy fallbacks for saved/incrementalSaved/disabled.
        val projected = DatasetStatusProjector.project(
          orgContext,
          spec = ds.spec,
          targetPrefix = ds.mapToPrefix.getOrElse(""),
          savedFallback = ds.stateSaved,
          incrementalSavedFallback = ds.stateIncrementalSaved,
          disabledFallback = ds.stateDisabled
        )
        val staleStateKeys = Json.toJson(ds).as[JsObject].keys.filter(_.startsWith("state"))
        // Phase C1: phase / actions / lastStep / run — the status document
        // fields the UI renders without deciding anything itself.
        val docFacts = DatasetStatusDoc.Facts(
          delimitersSet = ds.delimitersSet,
          errorMessage = ds.errorMessage,
          inRetry = retryStatus.contains(ds.spec),
          errorTime = ds.errorTime
        )
        val baseJson = JsObject(
          (Json.toJson(ds).as[JsObject].value -- staleStateKeys).toSeq
        ) ++ JsObject(projected.stateFields.map { case (k, v) => k -> Json.toJson(v) }) ++
          JsObject(DatasetStatusDoc.fields(orgContext, ds.spec, projected, docFacts))

        // Add retry status
        val withRetry = retryStatus.get(ds.spec) match {
          case Some(retry) =>
            baseJson ++ Json.obj(
              "harvestInRetry" -> true,
              "harvestRetryCount" -> retry.retryCount,
              "harvestLastRetryTime" -> retry.lastRetryTime,
              "harvestRetryMessage" -> retry.retryMessage
            )
          case None =>
            baseJson ++ Json.obj("harvestInRetry" -> false)
        }

        // Add trend data (24h delta for indexed records)
        // Prefer daily summaries (accurate), fall back to raw snapshots (legacy)
        val trendsLog = new java.io.File(orgContext.datasetsDir, s"${ds.spec}/trends.jsonl")
        val dailyLog = new java.io.File(orgContext.datasetsDir, s"${ds.spec}/trends-daily.jsonl")
        val trendData = TrendTrackingService.getDatasetTrendSummaryFromDaily(dailyLog, trendsLog, ds.spec) match {
          case Some(summary) =>
            Json.obj(
              "trend24h" -> Json.obj(
                "source" -> summary.delta24h.source,
                "indexed" -> summary.delta24h.indexed
              )
            )
          case None =>
            Json.obj("trend24h" -> Json.obj("source" -> 0, "indexed" -> 0))
        }

        withRetry ++ trendData
      }
    }
  }

  def listDsInfo(orgContext: OrgContext)(
      implicit ec: ExecutionContext,
      ts: TripleStore): Future[List[DsInfo]] = {
    listDsInfoWithStateFilter(orgContext, List.empty)
  }

  /**
   * List all datasets currently in retry mode.
   */
  def listDsInfoInRetry(orgContext: OrgContext)(
      implicit ec: ExecutionContext,
      ts: TripleStore): Future[List[DsInfo]] = Future.successful {
    orgContext.datasetsDb.allProps().collect {
      case (spec, props) if props.get(harvestInRetry.name).contains("true") &&
        !props.contains(stateDisabled.name) => getDsInfo(spec, orgContext)
    }.toList
  }

  /**
   * List all datasets with incomplete operations (for restart recovery).
   */
  def listDsInfoWithIncompleteOperations(orgContext: OrgContext)(
      implicit ec: ExecutionContext,
      ts: TripleStore): Future[List[DsInfo]] = Future.successful {
    orgContext.datasetsDb.allProps().collect {
      case (spec, props) if props.contains(datasetCurrentOperation.name) =>
        getDsInfo(spec, orgContext)
    }.toList
  }

  def listDsInfoWithStateFilter(orgContext: OrgContext, allowedStates: List[String])(
      implicit ec: ExecutionContext,
      ts: TripleStore): Future[List[DsInfo]] = Future.successful {
    // D2: lifecycle state is projected, not stored — the filter arg is
    // legacy (all live callers pass an empty list).
    orgContext.datasetsDb.allSpecs().map(spec => getDsInfo(spec, orgContext)).toList
  }

  def getDsInfoUri(spec: String, uriPrefix: String) =
    s"${uriPrefix}/dataset/${urlEncodeValue(spec)}"

  def getGraphName(spec: String, uriPrefix: String) =
    createGraphName(getDsInfoUri(spec, uriPrefix))

  def getSkosGraphName(datasetUri: String) =
    createGraphName(s"$datasetUri/skos")

  def createDsInfo(spec: String,
                   character: DsCharacter,
                   mapToPrefix: String,
                   orgContext: OrgContext)(implicit ec: ExecutionContext,
                                           ts: TripleStore): Future[DsInfo] = {
    val db = orgContext.datasetsDb
    db.createDataset(spec)
    val base = List(
      datasetSpec.name -> spec,
      orgId.name -> orgContext.appConfig.orgId,
      datasetCharacter.name -> character.name,
      publishOAIPMH.name -> "true",
      publishIndex.name -> "true",
      publishLOD.name -> "true"
    ) ++ (if (mapToPrefix != "-") List(datasetMapToPrefix.name -> mapToPrefix) else Nil)
    db.setProps(spec, base: _*)
    Future.successful(getDsInfo(spec, orgContext))
  }

  def freshDsInfo(spec: String, orgContext: OrgContext)(
      implicit ec: ExecutionContext,
      ts: TripleStore): Future[Option[DsInfo]] = {
    if (orgContext.datasetsDb.exists(spec))
      Future.successful(Some(getDsInfo(spec, orgContext)))
    else
      Future.successful(None)
  }

  def withDsInfo[T](spec: String, orgContext: OrgContext)(
      block: DsInfo => T)(implicit ec: ExecutionContext, ts: TripleStore) = {
    // Cache removed to fix harvest storm bug and ensure fresh data
    val dsInfo = Await
      .result(freshDsInfo(spec, orgContext: OrgContext), 30.seconds)
      .getOrElse {
        throw new RuntimeException(s"No dataset info for $spec")
      }
    block(dsInfo)
  }
  def getDsInfo(spec: String, orgContext: OrgContext)(
      implicit ec: ExecutionContext,
      ts: TripleStore) = {
    new DsInfo(
      spec,
      orgContext.appConfig.nxUriPrefix,
      orgContext.appConfig.naveApiAuthToken,
      orgContext.appConfig.naveApiUrl,
      orgContext,
      orgContext.appConfig.mockBulkApi
    )
  }
}

class DsInfo(
    val spec: String,
    val nxUriPrefix: String,
    val naveApiAuthToken: String,
    val naveApiUrl: String,
    val orgContext: OrgContext,
    val mockBulkApi: Boolean)(implicit ec: ExecutionContext, ts: TripleStore)
    extends SkosGraph {

  import dataset.DsInfo._

  val orgId = orgContext.appConfig.orgId

  def now: String = timeToString(new DateTime())

  val uri = getDsInfoUri(spec, nxUriPrefix)

  val graphName = getGraphName(spec, nxUriPrefix)

  val skosified = true

  val skosGraphName = getSkosGraphName(uri)

  // Phase D2: props live in datasets.db — no Jena model, no existence caching.

  def cacheDataExists(exists: Boolean): Unit = () // D2: existence = row exists

  def invalidateCachedModel(): Unit = () // D2: nothing cached


  def getLiteralProp(prop: NXProp): Option[String] =
    orgContext.datasetsDb.getProp(spec, prop.name)

  def getTimeProp(prop: NXProp): Option[DateTime] =
    getLiteralProp(prop).map(stringToTime)

  def getBooleanProp(prop: NXProp): Boolean =
    getLiteralProp(prop).exists(_ == "true")

  def setSingularLiteralProps(propVals: (NXProp, String)*): Unit = {
    // A null value NPEs deep in the SPARQL escaper and takes the whole
    // pipeline step down (seen live from a harvest completion). Drop it,
    // name the prop, keep the rest of the update.
    val (nullVals, validVals) = propVals.partition(_._2 == null)
    nullVals.foreach { case (prop, _) =>
      logger.warn(s"setSingularLiteralProps: ignoring NULL value for '${prop.name}' on $spec — caller bug worth chasing")
    }
    orgContext.datasetsDb.setProps(spec, validVals.map(pv => pv._1.name -> pv._2): _*)
  }

  def removeLiteralProp(prop: NXProp): Unit =
    orgContext.datasetsDb.removeProp(spec, prop.name)

  /**
   * Store indexing results received from Hub3 webhook notification.
   * Called when Hub3 sends completion notification after orphan control.
   */
  def setIndexingResults(
      status: String,
      recordsIndexed: Int,
      recordsExpected: Int,
      orphansDeleted: Int,
      errorCount: Int,
      revision: Int,
      message: Option[String],
      timestamp: String
  ): Unit = {
    import triplestore.GraphProperties.{
      indexingRecordsIndexed, indexingRecordsExpected, indexingOrphansDeleted,
      indexingErrorCount, indexingLastStatus, indexingLastMessage,
      indexingLastTimestamp, indexingLastRevision
    }

    val baseProps: List[(NXProp, String)] = List(
      indexingRecordsIndexed -> recordsIndexed.toString,
      indexingRecordsExpected -> recordsExpected.toString,
      indexingOrphansDeleted -> orphansDeleted.toString,
      indexingErrorCount -> errorCount.toString,
      indexingLastStatus -> status,
      indexingLastTimestamp -> timestamp,
      indexingLastRevision -> revision.toString
    )

    val allProps = message match {
      case Some(msg) => baseProps :+ (indexingLastMessage -> msg)
      case None => baseProps
    }

    setSingularLiteralProps(allProps: _*)
  }

  def getLiteralPropList(prop: NXProp): List[String] =
    orgContext.datasetsDb.listValues(spec, prop.name)

  def addLiteralPropToList(prop: NXProp, uriValueString: String): Unit =
    orgContext.datasetsDb.addListValue(spec, prop.name, uriValueString)

  def removeLiteralPropFromList(prop: NXProp, uriValueString: String): Unit =
    orgContext.datasetsDb.removeListValue(spec, prop.name, uriValueString)

  def getUriProp(prop: NXProp): Option[String] =
    orgContext.datasetsDb.getProp(spec, prop.name)

  def dropDataset = {
    removeNaveDataSet()
    orgContext.datasetsDb.deleteDataset(spec)
    Future.successful(true)
  }

  def dropDatasetRecords = {
    // Record graphs left Fuseki long ago; nothing to delete anymore.
    Future.successful(true)
  }

  def dropDatasetIndex = {
    disableInNaveIndex()
    true
  }

  def getIdFilter: IdFilter = {
    getLiteralProp(idFilterType).map { filterType =>
      val expressionOpt = getLiteralProp(idFilterExpression).flatMap(ex =>
        if (ex.trim.isEmpty) None else Some(ex))
      IdFilter(filterType, expressionOpt)
    } getOrElse {
      VERBATIM_FILTER
    }
  }

  // Mapping source methods for default mappings feature
  def getMappingSource: String = getLiteralProp(datasetMappingSource).getOrElse("manual")

  def getDefaultMappingPrefix: Option[String] = getLiteralProp(datasetDefaultMappingPrefix)

  def getDefaultMappingName: Option[String] = getLiteralProp(datasetDefaultMappingName)

  def getDefaultMappingVersion: Option[String] = getLiteralProp(datasetDefaultMappingVersion)

  /** Per-dataset rec-def version pin. None = follow the prefix's current version. */
  def getRecDefVersionHash: Option[String] = getLiteralProp(datasetRecDefVersionHash).filter(_.nonEmpty)

  def setRecDefVersionHash(hashOpt: Option[String]): Unit = {
    setSingularLiteralProps(datasetRecDefVersionHash -> hashOpt.getOrElse(""))
  }

  def setMappingSource(
    source: String,
    prefix: Option[String] = None,
    name: Option[String] = None,
    version: Option[String] = None
  ): Unit = {
    source match {
      case "manual" =>
        // Clear default mapping settings when switching to manual
        setSingularLiteralProps(datasetMappingSource -> "manual")
        // Remove default mapping properties by setting empty strings
        setSingularLiteralProps(
          datasetDefaultMappingPrefix -> "",
          datasetDefaultMappingName -> "",
          datasetDefaultMappingVersion -> ""
        )
      case "default" =>
        val actualPrefix = prefix.getOrElse("")
        val actualName = name.getOrElse("default")
        val actualVersion = version.getOrElse("latest")
        setSingularLiteralProps(
          datasetMappingSource -> "default",
          datasetDefaultMappingPrefix -> actualPrefix,
          datasetDefaultMappingName -> actualName,
          datasetDefaultMappingVersion -> actualVersion
        )
      case _ =>
        logger.warn(s"Unknown mapping source: $source, defaulting to manual")
        setSingularLiteralProps(datasetMappingSource -> "manual")
    }
  }

  def usesDefaultMapping: Boolean = getMappingSource == "default"

  // Phase A4b: getState()-by-max-timestamp is gone — lifecycle state is
  // computed by DatasetStatusProjector from disk + registry facts. setState/
  // removeState remain only for the DISABLED admin flag (and legacy cleanup).

  def setState(state: DsState.Value) = {
    setSingularLiteralProps(
      NXProp(state.toString, GraphProperties.timeProp) -> now)
  }

  def setHarvestIncrementalMode(enabled: Boolean = false) = {
    val mode = if (enabled) "true" else "false"
    setSingularLiteralProps(harvestIncrementalMode -> mode)
  }

  def setLastHarvestTime(incremental: Boolean = false) = {
    if (incremental) {
      // todo: fix this by setting the start date
      setSingularLiteralProps(lastIncrementalHarvestTime -> now)
      setHarvestIncrementalMode(true)
    } else {
      setSingularLiteralProps(lastFullHarvestTime -> now)
      setHarvestIncrementalMode(false)
      setIncrementalProcessedRecordCounts(0, 0)
      removeLiteralProp(lastIncrementalHarvestTime)
    }
  }

  def setRecordsSync(state: Boolean = false) = {
    val syncState = state match {
      case true  => "true"
      case false => "false"
    }
    removeLiteralProp(datasetRecordsInSync)
    setSingularLiteralProps(datasetRecordsInSync -> syncState)
  }

  def setProxyResourcesSync(state: Boolean = false) = {
    val syncState = state match {
      case true  => "true"
      case false => "false"
    }
    removeLiteralProp(datasetResourcePropertiesInSync)
    setSingularLiteralProps(datasetResourcePropertiesInSync -> syncState)
  }

  def removeState(state: DsState.Value) =
    removeLiteralProp(NXProp(state.toString, GraphProperties.timeProp))

  def clearError(): Unit = {
    removeLiteralProp(datasetErrorMessage)
    removeLiteralProp(datasetErrorTime)
  }

  def setError(message: String) = {
    if (message.isEmpty) {
      clearError()
    } else {
      setSingularLiteralProps(
        datasetErrorMessage -> message,
        datasetErrorTime -> now
      )
    }
  }

  /**
   * Set the dataset into retry mode after a harvest failure.
   * This clears any existing error state and initializes retry tracking.
   */
  def setInRetry(message: String, retryCount: Int = 0): Unit = {
    // Clear existing error message (don't block UI)
    clearError()

    // Set retry state
    setSingularLiteralProps(
      harvestInRetry -> "true",
      harvestRetryCount -> retryCount.toString,
      harvestLastRetryTime -> now,
      harvestRetryMessage -> message
    )
  }

  /**
   * Increment the retry count and update last retry time.
   * Called before each retry attempt.
   * @return The new retry count
   */
  def incrementRetryCount(): Int = {
    val currentCount = getLiteralProp(harvestRetryCount).map(_.toInt).getOrElse(0)
    val newCount = currentCount + 1
    setSingularLiteralProps(
      harvestRetryCount -> newCount.toString,
      harvestLastRetryTime -> now
    )
    newCount
  }

  /**
   * Clear all retry state. Called on successful harvest or manual stop.
   */
  def clearRetryState(): Unit = {
    removeLiteralProp(harvestInRetry)
    removeLiteralProp(harvestRetryCount)
    removeLiteralProp(harvestLastRetryTime)
    removeLiteralProp(harvestRetryMessage)
  }

  /**
   * Check if dataset is currently in retry mode.
   */
  def isInRetry: Boolean =
    getLiteralProp(harvestInRetry).exists(_ == "true")

  /**
   * Get current retry count.
   */
  def getRetryCount: Int =
    getLiteralProp(harvestRetryCount).map(_.toInt).getOrElse(0)

  /**
   * Get timestamp of last retry attempt.
   */
  def getLastRetryTime: Option[DateTime] =
    getTimeProp(harvestLastRetryTime)

  /**
   * Set the current operation for restart recovery tracking.
   */
  def setCurrentOperation(operation: String, trigger: String = "automatic"): Unit = {
    setSingularLiteralProps(
      datasetCurrentOperation -> operation,
      datasetOperationStartTime -> now,
      datasetOperationTrigger -> trigger,
      datasetOperationStatus -> "in_progress"
    )
  }

  /**
   * Mark current operation as completed.
   */
  def completeOperation(): Unit = {
    setSingularLiteralProps(datasetOperationStatus -> "completed")
  }

  /**
   * Clear operation tracking (called when returning to Idle).
   */
  def clearOperation(): Unit = {
    removeLiteralProp(datasetCurrentOperation)
    removeLiteralProp(datasetOperationStartTime)
    removeLiteralProp(datasetOperationTrigger)
    removeLiteralProp(datasetOperationStatus)
  }

  /**
   * Get current operation if any.
   */
  def getCurrentOperation: Option[String] =
    getLiteralProp(datasetCurrentOperation)

  /**
   * Get operation start time.
   */
  def getOperationStartTime: Option[DateTime] =
    getTimeProp(datasetOperationStartTime)

  /**
   * Get operation trigger (automatic or manual).
   */
  def getOperationTrigger: Option[String] =
    getLiteralProp(datasetOperationTrigger)

  /**
   * Get operation status.
   */
  def getOperationStatus: Option[String] =
    getLiteralProp(datasetOperationStatus)

  /**
   * Check if operation is stale (started more than given minutes ago).
   */
  def isOperationStale(thresholdMinutes: Int): Boolean = {
    getOperationStartTime match {
      case Some(startTime) =>
        val now = DateTime.now()
        val minutesAgo = Minutes.minutesBetween(startTime, now).getMinutes
        minutesAgo > thresholdMinutes
      case None => false
    }
  }

  /**
   * Get the error message that triggered retry mode.
   */
  def getRetryMessage: Option[String] =
    getLiteralProp(harvestRetryMessage)

  /**
   * Calculate when the next retry should occur.
   * @param intervalMinutes The configured retry interval
   */
  def getNextRetryTime(intervalMinutes: Int): DateTime = {
    getLastRetryTime match {
      case Some(lastRetry) => lastRetry.plusMinutes(intervalMinutes)
      case None => new DateTime() // Retry immediately if no last time
    }
  }

  /**
   * Check if enough time has passed for next retry.
   * @param intervalMinutes The configured retry interval
   */
  def isTimeForRetry(intervalMinutes: Int): Boolean = {
    getNextRetryTime(intervalMinutes).isBeforeNow
  }

  val LineId = "<!--<([^>]+)__([^>]+)>-->".r

  def uniqueCounterSource(sourceDirectory: String): Map[String, Int] = {
    val sourceFiles = new File(sourceDirectory)
    if (!sourceFiles.exists()) {
      return Map.empty[String, Int]
    }
    sourceFiles.listFiles
      .filter(_.getName.endsWith(".ids"))
      .flatMap { file =>
        val source = Source.fromFile(file)
        try {
          source.getLines().toList  // Collect to avoid keeping the source open
        } finally {
          source.close()
        }
      }
      .foldLeft(Map.empty[String, Int]) { (count, keyword) =>
        count + (keyword -> (count.getOrElse(keyword, 0) + 1))
      }
  }

  def uniqueCounterProcessed(
      processedRecordDiretory: String): Map[String, Int] = {
    val processedFiles = new File(processedRecordDiretory)
    if (!processedFiles.exists()) {
      return Map.empty[String, Int]
    }
    // Accept both .xml (legacy) and .xml.zst (ZSTD compressed) files
    processedFiles.listFiles
      .filter(f => f.getName.endsWith(".xml") || f.getName.endsWith(".xml.zst"))
      .flatMap { fname =>
        {
          // Create appropriate Source based on file type
          val source = if (fname.getName.endsWith(".xml.zst")) {
            val fis = new FileInputStream(fname)
            val zis = new ZstdInputStream(fis)
            Source.fromInputStream(zis, "UTF-8")
          } else {
            Source.fromFile(fname)
          }
          try {
            source
              .getLines()
              .filter { line =>
                line match {
                  case LineId(graphName, currentHash) => true
                  case _                              => false
                }
              }
              .map {
                case LineId(graphName, currentHash) => graphName
                case _                              =>
              }
              .toList  // Materialize before closing source
          } finally {
            source.close()
          }
        }
      }
      .foldLeft(Map.empty[String, Int]) { (count, word) =>
        val keyword = word.asInstanceOf[String]
        count + (keyword -> (count.getOrElse(keyword, 0) + 1))
      }
  }

  def updatedSpecCountFromFile(specName: String = spec,
                               narthexBaseDir: File,
                               orgId: String): (Int, Int, Int) = {

    val spec_source_dir =
      s"""${narthexBaseDir.toString}/$orgId/datasets/$specName"""
    val processed_dir = s"$spec_source_dir/processed"
    val source_dir = s"$spec_source_dir/source"
    val valid: Int = uniqueCounterProcessed(processed_dir).size
    val total: Int = uniqueCounterSource(source_dir).size
    val invalid = total - valid
    setRecordCount(total)
    setProcessedRecordCounts(valid, invalid)
    (invalid, valid, total)
  }

  def setRecordCount(count: Int) =
    setSingularLiteralProps(datasetRecordCount -> count.toString)

  def setProcessedRecordCounts(validCount: Int, invalidCount: Int) =
    setSingularLiteralProps(
      processedValid -> validCount.toString,
      processedInvalid -> invalidCount.toString
    )

  def setIncrementalProcessedRecordCounts(validCount: Int, invalidCount: Int) =
    setSingularLiteralProps(
      processedIncrementalValid -> validCount.toString,
      processedIncrementalInvalid -> invalidCount.toString
    )

  /**
   * Set acquisition counts for tracking record pipeline from harvest/upload to source.
   * @param acquired Total records acquired (harvested or uploaded)
   * @param deleted Records with status="deleted" in OAI-PMH (0 for uploads)
   * @param source Active records that made it to source.xml (acquired - deleted)
   * @param method How the source was acquired: "harvest" or "upload"
   */
  def setAcquisitionCounts(acquired: Int, deleted: Int, source: Int, method: String): Unit = {
    logger.info(s"[$spec] Setting acquisition counts: acquired=$acquired, deleted=$deleted, source=$source, method=$method")

    setSingularLiteralProps(
      acquiredRecordCount -> acquired.toString,
      deletedRecordCount -> deleted.toString,
      sourceRecordCount -> source.toString,
      acquisitionMethod -> method
    )

    // Verify the update succeeded by reading back the values
    val savedAcquired = getLiteralProp(acquiredRecordCount)
    val savedSource = getLiteralProp(sourceRecordCount)
    val savedDeleted = getLiteralProp(deletedRecordCount)

    if (savedAcquired != Some(acquired.toString)) {
      logger.error(s"[$spec] Failed to save acquiredRecordCount: expected $acquired, got $savedAcquired")
    }
    if (savedSource != Some(source.toString)) {
      logger.error(s"[$spec] Failed to save sourceRecordCount: expected $source, got $savedSource")
    }
    if (savedDeleted != Some(deleted.toString)) {
      logger.error(s"[$spec] Failed to save deletedRecordCount: expected $deleted, got $savedDeleted")
    }

    logger.debug(s"[$spec] Acquisition counts verified: acquired=$savedAcquired, deleted=$savedDeleted, source=$savedSource")
  }

  def setHarvestInfo(harvestTypeEnum: HarvestType,
                     url: String,
                     dataset: String,
                     prefix: String,
                     recordId: String) = setSingularLiteralProps(
    harvestType -> harvestTypeEnum.name,
    harvestURL -> url,
    harvestDataset -> dataset,
    harvestRecord -> recordId,
    harvestPrefix -> prefix
  )

  def setDelimiters(recordRootValue: String, uniqueIdValue: String) = setSingularLiteralProps(
    recordRoot -> recordRootValue,
    uniqueId -> uniqueIdValue,
    delimitersSet -> timeToLocalString(DateTime.now())
  )

  def setHarvestCron(harvestCron: HarvestCron = currentHarvestCron) =
    setSingularLiteralProps(
      harvestPreviousTime -> timeToLocalString(harvestCron.previous),
      harvestDelay -> harvestCron.delay.toString,
      harvestDelayUnit -> harvestCron.unit.toString
    )

  def setMetadata(metadata: DsMetadata) = setSingularLiteralProps(
    datasetName -> metadata.name,
    datasetDescription -> metadata.description,
    datasetAggregator -> metadata.aggregator,
    datasetOwner -> metadata.owner,
    datasetDataProviderURL -> metadata.dataProviderURL,
    datasetLanguage -> metadata.language,
    datasetRights -> metadata.rights,
    datasetType -> metadata.dataType,
    edmType -> metadata.edmType
  )

  def toggleNaveSkosField(datasetUri: String,
                          propertyUri: String,
                          delete: Boolean = false) = {

    val skosFieldApi = s"${naveApiUrl}/api/index/narthex/toggle/proxyfield/"
    val request = orgContext.wsApi
      .url(s"$skosFieldApi")
      .withHttpHeaders(
        "Content-Type" -> "application/json; charset=utf-8",
        "Accept" -> "application/json",
        "Authorization" -> s"Token ${naveApiAuthToken}"
      )
    val json = Json.obj(
      "dataset_uri" -> datasetUri,
      "property_uri" -> propertyUri,
      "delete" -> delete
    )
    request.post(json) // .map(checkUpdateResponse(_, json))
  }

  def hasPreviousTime() = getLiteralProp(harvestPreviousTime).getOrElse("") != ""

  def getPreviousHarvestTime() = {
    try {
      stringToTime(getLiteralProp(harvestPreviousTime).getOrElse("2100-01-01"))
    } catch {
      case iae: IllegalArgumentException =>
        stringToTime("2100-01-01")
    }
  }

  def currentHarvestCron = {
    (getLiteralProp(harvestPreviousTime),
     getLiteralProp(harvestDelay),
     getLiteralProp(harvestDelayUnit),
     getLiteralProp(harvestIncremental)) match {
      case (Some(previousString),
            Some(delayString),
            Some(unitString),
            Some(incrementalString)) =>
        val previousTime = try {
          stringToTime(previousString)
        } catch {
          case iae: IllegalArgumentException =>
            stringToTime("1970-01-01")
        }
        val delay = try {
          delayString.toInt
        } catch {
          case ine: NumberFormatException =>
            0
        }
        if (delay == 0) {
          HarvestCron(new DateTime(), 1, DelayUnit.WEEKS, incremental = false)
        } else {
          HarvestCron(
            previous = previousTime,
            delay = delay,
            unit = DelayUnit.fromString(unitString).getOrElse(DelayUnit.WEEKS),
            incremental = incrementalString.toBoolean
          )
        }
      case _ =>
        HarvestCron(new DateTime(), 1, DelayUnit.WEEKS, incremental = false)
    }
  }

  def termCategoryMap(
      categoryVocabularyInfo: VocabInfo): Map[String, List[String]] = {
    val mappingStore = new TermMappingStore(this, orgContext, orgContext.wsApi)
    val mappings =
      Await.result(mappingStore.getMappings(categories = true), 1.minute)
    val uriLabelMap = categoryVocabularyInfo.vocabulary.uriLabelMap
    val termUriLabels = mappings.flatMap { mapping =>
      val termUri = mapping.head
      val categoryUri = mapping(1)
      uriLabelMap.get(categoryUri).map(label => (termUri, label))
    }
    termUriLabels.groupBy(_._1).map(group => group._1 -> group._2.map(_._2))
  }

  // for actors

  def createMessage(payload: AnyRef) = DatasetMessage(spec, payload)

  def toTurtle: String =
    orgContext.datasetsDb.props(spec).map { case (k, v) => s"# $k: $v" }.mkString("\n")

  lazy val vocabulary = new SkosVocabulary(spec, skosGraphName)

  def orUnknown(nxProp: NXProp) = getLiteralProp(nxProp).getOrElse("Unknown")

  def processedValidVal = orUnknown(processedValid)

  def processedInvalidVal = orUnknown(processedInvalid)

  override def toString = spec

  val bulkApi = s"${naveApiUrl}/api/index/bulk/"

  private def checkUpdateResponse(response: WSResponse,
                                  bulkActions: String): Unit = {
    if (response.status != 201) {
      // Log full details at ERROR level for debugging
      logger.error(s"Hub3 bulk API error for dataset $spec")
      logger.error(s"  HTTP Status: ${response.status} ${response.statusText}")
      logger.error(s"  Response Body: ${response.body}")

      // Extract record IDs from the bulk actions for context
      val affectedIds = DsInfo.extractRecordIdsFromBulkActions(bulkActions)
      if (affectedIds.nonEmpty) {
        logger.error(s"  Affected records (first ${affectedIds.size}): ${affectedIds.mkString(", ")}")
      }

      // Log sample of the request that failed (first 2000 chars to avoid huge logs)
      val requestSample = if (bulkActions.length > 2000) bulkActions.take(2000) + "..." else bulkActions
      logger.error(s"  Request sample: $requestSample")

      // Try to parse structured error from Hub3
      val hub3ErrorMessage = DsInfo.parseHub3ErrorResponse(response.body)

      // Create user-friendly error message
      val userMessage = hub3ErrorMessage match {
        case Some(msg) => s"Hub3 rejected records: $msg"
        case None => s"Hub3 bulk API error (${response.status}): ${response.body.take(500)}"
      }

      throw DsInfo.Hub3BulkApiException(
        message = userMessage,
        statusCode = response.status,
        hub3ErrorMessage = hub3ErrorMessage,
        hub3FullResponse = response.body,
        affectedRecordIds = affectedIds
      )
    }
  }

  def bulkApiUpdate(bulkActions: String) = {
    // logger.debug(bulkActions)
    val request = orgContext.wsApi
      .url(s"$bulkApi")
      .withHttpHeaders(
        "Content-Type" -> "text/plain; charset=utf-8",
        "Accept" -> "application/json",
        "Authorization" -> s"Token ${naveApiAuthToken}"
      )
    if (mockBulkApi) {
      logger.debug(s"Mocked $request")
      Future.successful(true)
    } else {
      // define your success condition
      implicit val success =
        new retry.Success[WSResponse](r => !((500 to 599) contains r.status))
      // retry 4 times with a delay of 1 second which will be multipled
      // by 2 on every attempt
      val wsResponseFuture = retry.Backoff(6, 1.seconds).apply { () =>
        request.post(bulkActions)
      }
      wsResponseFuture.map(checkUpdateResponse(_, bulkActions))
    }
  }

  def extractSpecIdFromGraphName(id: String): (String, String) = {
    if (id contains "/doc/") {
	  val localId = id.stripSuffix("/graph").split("/doc/").last.split("/").last.trim
      // logger.info(s"localID: $localId")
	  return (toString, localId)
    }
    // Handle URN format: urn:{orgId}_{spec}_{localId}/graph
    if (id.startsWith("urn:")) {
      val UrnIdExtractor = "urn:([^_]+)_(.*)_([^_]+)/graph".r
      id match {
        case UrnIdExtractor(_, spec, localId) => return (spec, localId)
        case _ => throw new RuntimeException(s"Unable to extract spec/localId from URN graph name: $id")
      }
    }
    // Handle URL format: http(s)://.../{spec}/{localId}/graph
	val SpecIdExtractor =
	  "http[s]{0,1}://.*?/([^/]+)/([^/]+)/graph".r
	val SpecIdExtractor(spec, localId) = id
	(spec, localId)
  }



  def updateDatasetRevision() = {
    val dataType = getLiteralProp(datasetType)
    val edmDataType = getLiteralProp(edmType)
    val dataProviderURL = getLiteralProp(datasetDataProviderURL)

    val actionMap = Json.obj(
      "dataset" -> spec,
      "orgId" -> orgContext.appConfig.orgId,
      "dataType" -> dataType,
      "dataProviderURL" -> dataProviderURL,
      "type" -> edmDataType,
      "action" -> "increment_revision"
    )
    bulkApiUpdate(s"${actionMap.toString()}\n")
    // Thread.sleep(333)
  }

  def removeNaveOrphans(timeStamp: String) = {
    val actionMap = Json.obj(
      "dataset" -> spec,
      "orgId" -> orgContext.appConfig.orgId,
      "action" -> "clear_orphans",
      "modification_date" -> timeStamp
    )
    bulkApiUpdate(s"${actionMap.toString()}\n")
  }

  // Explicit per-record delete. Emitted as a single bulk-action line with an
  // array of local_ids, chunked to stay under chunkMaxBytes so very large
  // delete batches do not overflow the HTTP body. Hub3 must recognise
  // `action = drop_records` with an `ids` array — the matching Hub3 change
  // lands separately.
  def dropRecordsByIds(localIds: scala.collection.Seq[String])(
      implicit ec: scala.concurrent.ExecutionContext
  ): scala.concurrent.Future[Any] = {
    if (localIds.isEmpty) scala.concurrent.Future.successful(())
    else {
      // ~6 MB body budget to match ProcessedRepo.chunkMaxBytes. Assume ids
      // are well under 512 bytes each on average; 10_000 ids per batch is
      // comfortably under the ceiling and keeps the single JSON line small.
      val batchSize = 10000
      val batches = localIds.grouped(batchSize).toList

      def sendOne(ids: scala.collection.Seq[String]): scala.concurrent.Future[Any] = {
        val actionMap = Json.obj(
          "dataset" -> spec,
          "orgId" -> orgContext.appConfig.orgId,
          "action" -> "drop_records",
          "ids" -> ids.toList
        )
        bulkApiUpdate(s"${actionMap.toString()}\n")
      }

      batches.foldLeft[scala.concurrent.Future[Any]](scala.concurrent.Future.successful(())) {
        (acc, batch) => acc.flatMap(_ => sendOne(batch))
      }
    }
  }

  def disableInNaveIndex() = {
    val actionMap = Json.obj(
      "dataset" -> spec,
      "orgId" -> orgContext.appConfig.orgId,
      "action" -> "disable_index"
    )
    bulkApiUpdate(s"${actionMap.toString()}\n")
  }

  def removeNaveDataSet() = {
    val actionMap = Json.obj(
      "dataset" -> spec,
      "orgId" -> orgContext.appConfig.orgId,
      "action" -> "drop_dataset"
    )
    bulkApiUpdate(s"${actionMap.toString()}\n")
  }

  /**
   * Convert DsInfo to simple JSON format for WebSocket updates.
   * Uses the webSocketFields registry from DsInfo companion object as the single source of truth.
   *
   * This method is used for real-time WebSocket broadcasts when dataset state changes.
   * All fields that need to survive WebSocket updates MUST be in the webSocketFields registry.
   */
  def toSimpleJson: JsValue = {
    import play.api.libs.json.{JsNull, JsObject, JsString}

    // Core fields that aren't from graph properties
    val coreFields: scala.collection.immutable.Seq[(String, JsValue)] = scala.collection.immutable.Seq(
      "datasetSpec" -> JsString(spec),
      "spec" -> JsString(spec),
      "orgId" -> JsString(orgId)
    )

    // All fields from the registry - uses getValue which handles type conversion.
    // Phase A4b: lifecycle state* fields are excluded here — they come from
    // the projector below (disk + registry truth), not from stored props.
    val registryFields: scala.collection.immutable.Seq[(String, JsValue)] = DsInfo.webSocketFields
      .filterNot(_.jsonName.startsWith("state"))
      .flatMap { field =>
        field.getValue(this).map(value => field.jsonName -> value)
      }

    val projectedStateFields: scala.collection.immutable.Seq[(String, JsValue)] = {
      val projected = DatasetStatusProjector.project(
        orgContext,
        spec = spec,
        targetPrefix = getLiteralProp(triplestore.GraphProperties.datasetMapToPrefix).getOrElse(""),
        savedFallback = getLiteralProp(triplestore.GraphProperties.stateSaved),
        incrementalSavedFallback = getLiteralProp(triplestore.GraphProperties.stateIncrementalSaved),
        disabledFallback = getLiteralProp(triplestore.GraphProperties.stateDisabled)
      )
      val docFacts = DatasetStatusDoc.Facts(
        delimitersSet = getLiteralProp(triplestore.GraphProperties.delimitersSet),
        errorMessage = getLiteralProp(triplestore.GraphProperties.datasetErrorMessage),
        inRetry = isInRetry,
        errorTime = getLiteralProp(triplestore.GraphProperties.datasetErrorTime)
      )
      projected.stateFields.map { case (k, v) => k -> (JsString(v): JsValue) }.toList ++
        DatasetStatusDoc.fields(orgContext, spec, projected, docFacts).toList
    }

    // Computed fields that require method calls (not simple property lookups)
    // These are fields derived from multiple properties or require special logic
    val computedFields: scala.collection.immutable.Seq[(String, JsValue)] = scala.collection.immutable.Seq(
      // Mapping configuration - duplicated with "dataset" prefix for backward compatibility
      // getMappingSource returns String (not Option), defaults to "manual"
      "datasetMappingSource" -> JsString(getMappingSource),
      "mappingSource" -> JsString(getMappingSource),
      // getDefaultMappingPrefix/Name/Version return Option[String]
      "datasetDefaultMappingPrefix" -> getDefaultMappingPrefix.map(JsString(_)).getOrElse(JsNull),
      "defaultMappingPrefix" -> getDefaultMappingPrefix.map(JsString(_)).getOrElse(JsNull),
      "datasetDefaultMappingName" -> getDefaultMappingName.map(JsString(_)).getOrElse(JsNull),
      "defaultMappingName" -> getDefaultMappingName.map(JsString(_)).getOrElse(JsNull),
      "datasetDefaultMappingVersion" -> getDefaultMappingVersion.map(JsString(_)).getOrElse(JsNull),
      "defaultMappingVersion" -> getDefaultMappingVersion.map(JsString(_)).getOrElse(JsNull),
      "datasetRecDefVersionHash" -> getRecDefVersionHash.map(JsString(_)).getOrElse(JsNull),
      "recDefVersionHash" -> getRecDefVersionHash.map(JsString(_)).getOrElse(JsNull),
      // Duplicate error message field for backward compatibility
      "errorMessage" -> getLiteralProp(triplestore.GraphProperties.datasetErrorMessage).map(JsString(_)).getOrElse(JsNull)
    ).filterNot(_._2 == JsNull)

    JsObject(coreFields ++ registryFields ++ projectedStateFields ++ computedFields)
  }

}
