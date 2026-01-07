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
    def writes(dsInfo: DsInfo): JsValue = {
      val out = new StringWriter()
      RDFDataMgr.write(out, dsInfo.getModel, RDFFormat.JSONLD_FLAT)
      Json.parse(out.toString)
    }
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
    harvestApiKeySet: Option[Boolean]
  )

  implicit val dsInfoLightWrites: Writes[DsInfoLight] = new Writes[DsInfoLight] {
    def writes(ds: DsInfoLight): JsValue = Json.obj(
      "spec" -> ds.spec,
      "name" -> ds.name,
      "processedValid" -> ds.processedValid,
      "processedInvalid" -> ds.processedInvalid,
      "recordCount" -> ds.recordCount,
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
  def listRetryStatus()(
      implicit ec: ExecutionContext,
      ts: TripleStore): Future[Map[String, RetryStatus]] = {
    ts.query(selectDatasetsInRetryQ).map { results =>
      results.map { row =>
        val spec = row("spec").text
        spec -> RetryStatus(
          spec = spec,
          retryCount = row.get("retryCount").map(_.text.toInt),
          lastRetryTime = row.get("lastRetryTime").map(_.text),
          retryMessage = row.get("retryMessage").map(_.text)
        )
      }.toMap
    }
  }

  /**
   * List all datasets with minimal data for initial page load.
   * Only fetches ~10 essential fields instead of full RDF models.
   */
  def listDsInfoLight(orgContext: OrgContext)(
      implicit ec: ExecutionContext,
      ts: TripleStore): Future[List[DsInfoLight]] = {
    ts.query(selectDatasetsLightQ).map { results =>
      results.map { row =>
        DsInfoLight(
          spec = row("spec").text,
          name = row.get("name").map(_.text),
          processedValid = row.get("processedValid").map(_.text.toInt),
          processedInvalid = row.get("processedInvalid").map(_.text.toInt),
          recordCount = row.get("recordCount").map(_.text.toInt),
          stateDisabled = row.get("stateDisabled").map(_.text),
          stateRaw = row.get("stateRaw").map(_.text),
          stateRawAnalyzed = row.get("stateRawAnalyzed").map(_.text),
          stateSourced = row.get("stateSourced").map(_.text),
          stateMappable = row.get("stateMappable").map(_.text),
          stateProcessable = row.get("stateProcessable").map(_.text),
          stateAnalyzed = row.get("stateAnalyzed").map(_.text),
          stateProcessed = row.get("stateProcessed").map(_.text),
          stateSaved = row.get("stateSaved").map(_.text),
          stateIncrementalSaved = row.get("stateIncrementalSaved").map(_.text),
          currentOperation = row.get("currentOperation").map(_.text),
          operationStatus = row.get("operationStatus").map(_.text),
          errorMessage = row.get("errorMessage").map(_.text),
          errorTime = row.get("errorTime").map(_.text),
          harvestType = row.get("harvestType").map(_.text),
          harvestDownloadURL = row.get("harvestDownloadURL").map(_.text),
          harvestIncrementalMode = row.get("harvestIncrementalMode").map(_.text),
          processedIncrementalValid = row.get("processedIncrementalValid").map(_.text.toInt),
          processedIncrementalInvalid = row.get("processedIncrementalInvalid").map(_.text.toInt),
          delimitersSet = row.get("delimitersSet").map(_.text),
          recordRootValue = row.get("recordRoot").map(_.text),
          uniqueIdValue = row.get("uniqueId").map(_.text),
          mappingSource = row.get("mappingSource").map(_.text),
          harvestUsername = row.get("harvestUsername").map(_.text),
          harvestPasswordSet = row.get("harvestPasswordSet").map(_.text.toBoolean),
          harvestApiKeySet = row.get("harvestApiKeySet").map(_.text.toBoolean)
        )
      }
    }
  }

  /**
   * List datasets with retry status merged in.
   * Runs both queries in parallel and merges results.
   */
  def listDsInfoLightWithRetry(orgContext: OrgContext)(
      implicit ec: ExecutionContext,
      ts: TripleStore): Future[List[JsValue]] = {
    for {
      datasets <- listDsInfoLight(orgContext)
      retryStatus <- listRetryStatus()
    } yield {
      datasets.map { ds =>
        val baseJson = Json.toJson(ds).as[JsObject]
        retryStatus.get(ds.spec) match {
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
      ts: TripleStore): Future[List[DsInfo]] = {

    ts.query(selectDatasetsInRetryQ).map { list =>
      list.map { entry =>
        val spec = entry("spec").text
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
  }

  /**
   * List all datasets with incomplete operations (for restart recovery).
   */
  def listDsInfoWithIncompleteOperations(orgContext: OrgContext)(
      implicit ec: ExecutionContext,
      ts: TripleStore): Future[List[DsInfo]] = {

    ts.query(selectDatasetsWithIncompleteOperationsQ).map { list =>
      list.map { entry =>
        val spec = entry("spec").text
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
  }

  def listDsInfoWithStateFilter(orgContext: OrgContext, allowedStates: List[String])(
      implicit ec: ExecutionContext,
      ts: TripleStore): Future[List[DsInfo]] = {
    val query = if (allowedStates.nonEmpty) {
      ts.query(selectDatasetSpecsWithStateFilterQ(allowedStates))
    } else {
      ts.query(selectDatasetSpecsQ)
    }
    
    query.flatMap { list =>
      val specs = list.map(entry => entry("spec").text)
      
      // Batch check existence for filtered datasets only - prevents error dataset timestamp updates
      val graphUris = specs.map(spec => getGraphName(spec, orgContext.appConfig.nxUriPrefix))
      
      ts.batchCheckGraphExistence(graphUris).map { existenceMap =>
        // Store batch results in Play cache for future DsInfo instances to use
        existenceMap.foreach { case (graphUri, exists) =>
          val spec = specs.find(s => getGraphName(s, orgContext.appConfig.nxUriPrefix) == graphUri)
          spec.foreach { s =>
            val cacheKey = s"dataset_existence_$s"
            orgContext.cacheApi.set(cacheKey, exists, 30.seconds) // Shorter cache for more responsive updates
          }
        }

        specs.map { spec =>
          val graphUri = getGraphName(spec, orgContext.appConfig.nxUriPrefix)
          val dataExists = existenceMap.getOrElse(graphUri, false)

          val dsInfo = new DsInfo(
            spec,
            orgContext.appConfig.nxUriPrefix,
            orgContext.appConfig.naveApiAuthToken,
            orgContext.appConfig.naveApiUrl,
            orgContext,
            orgContext.appConfig.mockBulkApi
          )

          // Cache the existence result to avoid individual dataGet() calls
          dsInfo.cacheDataExists(dataExists)
          dsInfo
        }
      }
    }
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
    val m = ModelFactory.createDefaultModel()
    val subject =
      m.getResource(getDsInfoUri(spec, orgContext.appConfig.nxUriPrefix))
    m.add(subject, m.getProperty(rdfType), m.getResource(datasetEntity))
    m.add(subject, m.getProperty(datasetSpec.uri), m.createLiteral(spec))
    m.add(subject,
          m.getProperty(orgId.uri),
          m.createLiteral(orgContext.appConfig.orgId))
    m.add(subject,
          m.getProperty(datasetCharacter.uri),
          m.createLiteral(character.name))
    val trueLiteral = m.createLiteral("true")
    m.add(subject, m.getProperty(publishOAIPMH.uri), trueLiteral)
    m.add(subject, m.getProperty(publishIndex.uri), trueLiteral)
    m.add(subject, m.getProperty(publishLOD.uri), trueLiteral)
    if (mapToPrefix != "-")
      m.add(subject,
            m.getProperty(datasetMapToPrefix.uri),
            m.createLiteral(mapToPrefix))
    ts.up
      .dataPost(getGraphName(spec, orgContext.appConfig.nxUriPrefix), m)
      .map { ok =>
        val cacheName = getDsInfoUri(spec, orgContext.appConfig.nxUriPrefix)
        val dsInfo = new DsInfo(
          spec,
          orgContext.appConfig.nxUriPrefix,
          orgContext.appConfig.naveApiAuthToken,
          orgContext.appConfig.naveApiUrl,
          orgContext,
          orgContext.appConfig.mockBulkApi
        )
        orgContext.cacheApi.set(cacheName, dsInfo, cacheTime)
        dsInfo
      }
  }

  def freshDsInfo(spec: String, orgContext: OrgContext)(
      implicit ec: ExecutionContext,
      ts: TripleStore): Future[Option[DsInfo]] = {

    // Check if we have cached existence result first to avoid expensive ASK query
    val cacheKey = s"dataset_existence_$spec"
    orgContext.cacheApi.get[Boolean](cacheKey) match {
      case Some(exists) =>
        logger.debug(s"Using cached existence result for $spec: $exists")
        if (exists) {
          val dsInfo = new DsInfo(
            spec,
            orgContext.appConfig.nxUriPrefix,
            orgContext.appConfig.naveApiAuthToken,
            orgContext.appConfig.naveApiUrl,
            orgContext,
            orgContext.appConfig.mockBulkApi
          )
          dsInfo.cacheDataExists(exists)
          Future.successful(Some(dsInfo))
        } else {
          Future.successful(None)
        }

      case None =>
        // Fallback to original ASK query if no cache available
        logger.debug(s"No cached existence for $spec, using ASK query")
        ts.ask(askIfDatasetExistsQ(getDsInfoUri(spec, orgContext.appConfig.nxUriPrefix)))
          .map { answer =>
            if (answer) {
              val dsInfo = new DsInfo(
                spec,
                orgContext.appConfig.nxUriPrefix,
                orgContext.appConfig.naveApiAuthToken,
                orgContext.appConfig.naveApiUrl,
                orgContext,
                orgContext.appConfig.mockBulkApi
              )
              // Cache this result for future use
              orgContext.cacheApi.set(cacheKey, answer, 30.seconds)
              dsInfo.cacheDataExists(answer)
              Some(dsInfo)
            } else {
              orgContext.cacheApi.set(cacheKey, answer, 30.seconds)
              None
            }
          }
    }
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

  // Caching to avoid repeated expensive dataGet() calls
  private var cachedDataExists: Option[Boolean] = None
  private var cachedModel: Option[Model] = None

  // Check for cached existence result on creation
  private def loadCachedExistence(): Unit = {
    if (cachedDataExists.isEmpty) {
      val cacheKey = s"dataset_existence_$spec"
      cachedDataExists = orgContext.cacheApi.get[Boolean](cacheKey)
      if (cachedDataExists.isDefined) {
        logger.debug(s"Loaded cached existence result for dataset $spec: ${cachedDataExists.get}")
      }
    }
  }

  def cacheDataExists(exists: Boolean): Unit = {
    cachedDataExists = Some(exists)
  }

  /** Invalidate the cached model to force fresh data to be read from triplestore.
    * This should be called when properties are changed externally.
    */
  def invalidateCachedModel(): Unit = {
    cachedModel = None
    logger.debug(s"Invalidated cached model for dataset $spec")
  }

  // Initialize cache on construction
  loadCachedExistence()
  
  def futureModel: Future[Model] = {
    cachedDataExists match {
      case Some(false) =>
        // We know data doesn't exist - return empty model immediately
        logger.debug(s"Using cached 'no data' result for dataset $spec")
        Future.successful(ModelFactory.createDefaultModel())
        
      case Some(true) =>
        // We know data exists - fetch it (but could also cache the model)
        cachedModel match {
          case Some(model) =>
            Future.successful(model)
          case None =>
            logger.debug(s"Fetching data for dataset $spec (existence confirmed)")
            val future = ts.dataGet(graphName)
            future.onComplete {
              case Success(model) => cachedModel = Some(model)
              case Failure(e) => logger.warn(s"Failed to fetch data for dataset $spec", e)
            }
            future
        }
        
      case None =>
        // No cache - use original logic (fallback)
        logger.warn(s"CACHE MISS: No cache available for dataset $spec - using expensive dataGet call. This indicates caching is not working properly.")
        val future = ts.dataGet(graphName)
        future.onComplete {
          case Success(_) => ()
          case Failure(e) => logger.warn(s"No data found for dataset $spec", e)
        }
        future
    }
  }

  def getModel = Await.result(futureModel, patience)

  def getLiteralProp(prop: NXProp): Option[String] = {
    val m = getModel
    val res = m.getResource(uri)
    val objects = m.listObjectsOfProperty(res, m.getProperty(prop.uri))
    if (objects.hasNext) Some(objects.next().asLiteral().getString) else None
  }

  def getTimeProp(prop: NXProp): Option[DateTime] =
    getLiteralProp(prop).map(stringToTime)

  def getBooleanProp(prop: NXProp): Boolean =
    getLiteralProp(prop).exists(_ == "true")

  def setSingularLiteralProps(propVals: (NXProp, String)*): Unit = {
    val sparqlPerPropQ =
      propVals.map(pv => updatePropertyQ(graphName, uri, pv._1, pv._2)).toList
    val withSynced = updateSyncedFalseQ(graphName, uri) :: sparqlPerPropQ
    val sparql = withSynced.mkString(";\n")
    val futureUpdate = ts.up.sparqlUpdate(sparql)
    Await.ready(futureUpdate, patience)
    // Invalidate cached model so next read gets fresh data with updated properties
    cachedModel = None
  }

  def removeLiteralProp(prop: NXProp): Unit = {
    val futureUpdate =
      ts.up.sparqlUpdate(removeLiteralPropertyQ(graphName, uri, prop))
    Await.ready(futureUpdate, patience)
    // Invalidate cached model so next read gets fresh data
    cachedModel = None
  }

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

  def getLiteralPropList(prop: NXProp): List[String] = {
    val m = getModel
    m.listObjectsOfProperty(m.getResource(uri), m.getProperty(prop.uri)).asScala
      .map(_.asLiteral().toString)
      .toList
  }

  def addLiteralPropToList(prop: NXProp, uriValueString: String): Unit = {
    val futureUpdate = ts.up.sparqlUpdate(
      addLiteralPropertyToListQ(graphName, uri, prop, uriValueString))
    Await.ready(futureUpdate, patience)
    // Invalidate cached model so next read gets fresh data
    cachedModel = None
  }

  def removeLiteralPropFromList(prop: NXProp, uriValueString: String): Unit = {
    val futureUpdate = ts.up.sparqlUpdate(
      deleteLiteralPropertyFromListQ(graphName, uri, prop, uriValueString))
    Await.ready(futureUpdate, patience)
    // Invalidate cached model so next read gets fresh data
    cachedModel = None
  }

  def getUriProp(prop: NXProp): Option[String] = {
    val m = getModel
    m.listObjectsOfProperty(m.getResource(uri), m.getProperty(prop.uri))
      .toList.asScala
      .headOption
      .map(_.asResource().toString)
  }

  def dropDataset = {
    removeNaveDataSet()
    ts.up
      .sparqlUpdate(deleteDatasetQ(graphName, uri, skosGraphName))
      .map(ok => true)
  }

  def dropDatasetRecords = {
    ts.up.sparqlUpdate(deleteDatasetRecordsQ(uri)).map(ok => true)
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

  def getState(): DsState.Value = {

    def max(lh: (DsState.Value, DateTime), rh: (DsState.Value, DateTime)) =
      if (lh._2.isAfter(rh._2)) lh else rh

    val statesAndTimestamps = DsState.values
      .map(state =>
        (state, getTimeProp(NXProp(state.toString, GraphProperties.timeProp))))
      .filter(stateAndTimeStampOpt => stateAndTimeStampOpt._2.isDefined)
      .map(stateAndTimestamp =>
        (stateAndTimestamp._1, stateAndTimestamp._2.get))

    val actualState =
      if (statesAndTimestamps.isEmpty) DsState.EMPTY
      else statesAndTimestamps.reduceLeft(max)._1

    DsState.withName(actualState.toString)

  }

  def setState(state: DsState.Value) = {
    setSingularLiteralProps(
      NXProp(state.toString, GraphProperties.timeProp) -> now)

    // Update existence cache immediately with new state - don't just invalidate
    val cacheKey = s"dataset_existence_$spec"
    // Note: DISABLED datasets still have metadata in triplestore - only EMPTY means no data
    // Bug fix: Previously DISABLED was treated as non-existent, which broke property lookups
    val dataExists = state match {
      case DsState.EMPTY => false // Only truly empty datasets don't exist
      case _ => true // DISABLED and all other states still have metadata that needs to be readable
    }
    orgContext.cacheApi.set(cacheKey, dataExists, 30.seconds)
    cachedDataExists = Some(dataExists)
    logger.debug(s"Updated existence cache for $spec due to state change to $state (dataExists: $dataExists)")
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

  def setError(message: String) = {
    if (message.isEmpty) {
      removeLiteralProp(datasetErrorMessage)
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
    removeLiteralProp(datasetErrorMessage)

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

  def toTurtle = {
    val sw = new StringWriter()
    getModel.write(sw, "TURTLE")
    sw.toString
  }

  lazy val vocabulary = new SkosVocabulary(spec, skosGraphName)

  def orUnknown(nxProp: NXProp) = getLiteralProp(nxProp).getOrElse("Unknown")

  def processedValidVal = orUnknown(processedValid)

  def processedInvalidVal = orUnknown(processedInvalid)

  override def toString = spec

  val bulkApi = s"${naveApiUrl}/api/index/bulk/"

  private def checkUpdateResponse(response: WSResponse,
                                  logString: String): Unit = {
    if (response.status != 201) {
      logger.error(logString)
      throw new Exception(s"${response.statusText}: ${response.body}:")
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
   * Uses the same field names as DsInfoLight so frontend can handle it.
   */
  def toSimpleJson: JsValue = {
    import triplestore.GraphProperties.{datasetName => dsName, processedValid => pValid,
      processedInvalid => pInvalid, datasetRecordCount => dsRecordCount,
      stateDisabled => sDisabled, stateRaw => sRaw, stateRawAnalyzed => sRawAnalyzed,
      stateSourced => sSourced, stateMappable => sMappable, stateProcessable => sProcessable,
      stateAnalyzed => sAnalyzed, stateProcessed => sProcessed, stateSaved => sSaved,
      stateIncrementalSaved => sIncrementalSaved, datasetCurrentOperation => curOperation,
      datasetOperationStatus => opStatus, datasetErrorMessage => dsErrorMessage,
      harvestType => hType, harvestURL => hURL, harvestDataset => hDataset,
      harvestPrefix => hPrefix, harvestSearch => hSearch, harvestIncrementalMode => hIncrementalMode,
      harvestDelay => hDelay, harvestDelayUnit => hDelayUnit, harvestPreviousTime => hPreviousTime,
      harvestIncremental => hIncremental,
      processedIncrementalValid => pIncrementalValid, processedIncrementalInvalid => pIncrementalInvalid,
      delimitersSet => dsDelimitersSet, recordRoot => dsRecordRoot, uniqueId => dsUniqueId,
      indexingRecordsIndexed => idxRecordsIndexed, indexingRecordsExpected => idxRecordsExpected,
      indexingOrphansDeleted => idxOrphansDeleted, indexingErrorCount => idxErrorCount,
      indexingLastStatus => idxLastStatus, indexingLastMessage => idxLastMessage,
      indexingLastTimestamp => idxLastTimestamp, indexingLastRevision => idxLastRevision,
      harvestInRetry => hInRetry, harvestRetryCount => hRetryCount,
      harvestLastRetryTime => hLastRetryTime, harvestRetryMessage => hRetryMessage}
    Json.obj(
      "datasetSpec" -> spec,
      "spec" -> spec,
      "datasetName" -> getLiteralProp(dsName),
      "orgId" -> this.orgId,
      "processedValid" -> getLiteralProp(pValid).map(_.toInt),
      "processedInvalid" -> getLiteralProp(pInvalid).map(_.toInt),
      "datasetRecordCount" -> getLiteralProp(dsRecordCount).map(_.toInt),
      "stateDisabled" -> getLiteralProp(sDisabled),
      "stateRaw" -> getLiteralProp(sRaw),
      "stateRawAnalyzed" -> getLiteralProp(sRawAnalyzed),
      "stateSourced" -> getLiteralProp(sSourced),
      "stateMappable" -> getLiteralProp(sMappable),
      "stateProcessable" -> getLiteralProp(sProcessable),
      "stateAnalyzed" -> getLiteralProp(sAnalyzed),
      "stateProcessed" -> getLiteralProp(sProcessed),
      "stateSaved" -> getLiteralProp(sSaved),
      "stateIncrementalSaved" -> getLiteralProp(sIncrementalSaved),
      "currentOperation" -> getLiteralProp(curOperation),
      "operationStatus" -> getLiteralProp(opStatus),
      "datasetErrorMessage" -> getLiteralProp(dsErrorMessage),
      "errorMessage" -> getLiteralProp(dsErrorMessage),
      "harvestType" -> getLiteralProp(hType),
      "harvestURL" -> getLiteralProp(hURL),
      "harvestDataset" -> getLiteralProp(hDataset),
      "harvestPrefix" -> getLiteralProp(hPrefix),
      "harvestSearch" -> getLiteralProp(hSearch),
      "harvestIncrementalMode" -> getLiteralProp(hIncrementalMode),
      "harvestDelay" -> getLiteralProp(hDelay),
      "harvestDelayUnit" -> getLiteralProp(hDelayUnit),
      "harvestPreviousTime" -> getLiteralProp(hPreviousTime),
      "harvestIncremental" -> getLiteralProp(hIncremental),
      "processedIncrementalValid" -> getLiteralProp(pIncrementalValid).map(_.toInt),
      "processedIncrementalInvalid" -> getLiteralProp(pIncrementalInvalid).map(_.toInt),
      "delimitersSet" -> getLiteralProp(dsDelimitersSet),
      "recordRoot" -> getLiteralProp(dsRecordRoot),
      "uniqueId" -> getLiteralProp(dsUniqueId),
      // Indexing results from Hub3 webhook
      "indexingRecordsIndexed" -> getLiteralProp(idxRecordsIndexed).map(_.toInt),
      "indexingRecordsExpected" -> getLiteralProp(idxRecordsExpected).map(_.toInt),
      "indexingOrphansDeleted" -> getLiteralProp(idxOrphansDeleted).map(_.toInt),
      "indexingErrorCount" -> getLiteralProp(idxErrorCount).map(_.toInt),
      "indexingLastStatus" -> getLiteralProp(idxLastStatus),
      "indexingLastMessage" -> getLiteralProp(idxLastMessage),
      "indexingLastTimestamp" -> getLiteralProp(idxLastTimestamp),
      "indexingLastRevision" -> getLiteralProp(idxLastRevision).map(_.toInt),
      // Retry status
      "harvestInRetry" -> getLiteralProp(hInRetry).map(_.toBoolean),
      "harvestRetryCount" -> getLiteralProp(hRetryCount).map(_.toInt),
      "harvestLastRetryTime" -> getLiteralProp(hLastRetryTime),
      "harvestRetryMessage" -> getLiteralProp(hRetryMessage)
    )
  }

}
