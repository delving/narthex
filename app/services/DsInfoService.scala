package services

import java.time.Instant
import java.time.format.DateTimeFormatter
import play.api.libs.json._

/** PostgreSQL-backed read service for dataset information.
  *
  * Provides the same data as [[dataset.DsInfo.toSimpleJson]] and listing methods,
  * but reads from PostgreSQL instead of Fuseki. Field names match exactly so the
  * frontend does not require changes.
  *
  * This is a parallel read path — the existing DsInfo stays for writes and for any
  * reads that haven't been migrated yet.
  */
class DsInfoService(repo: DatasetRepository) {

  /** Get dataset info as JSON (equivalent to DsInfo.toSimpleJson).
    *
    * Returns the same field names so the frontend doesn't need changes.
    */
  def getDatasetInfoJson(spec: String): Option[JsValue] = {
    for {
      ds <- repo.getDataset(spec)
    } yield {
      val state = repo.getState(spec)
      val harvestConfig = repo.getHarvestConfig(spec)
      val schedule = repo.getHarvestSchedule(spec)
      val mappingConfig = repo.getMappingConfig(spec)
      val indexing = repo.getIndexing(spec)
      buildJson(ds, state, harvestConfig, schedule, mappingConfig, indexing)
    }
  }

  /** List all active datasets as lightweight JSON.
    *
    * Equivalent to DsInfo.listDsInfoLight — contains spec, name, state,
    * recordCount, and harvestType.
    */
  def listDatasetsJson(orgId: String): List[JsValue] = {
    repo.listActiveDatasets(orgId).map { ds =>
      val state = repo.getState(ds.spec)
      val harvestConfig = repo.getHarvestConfig(ds.spec)
      buildLightJson(ds, state, harvestConfig)
    }
  }

  /** List all active datasets with full info.
    *
    * Equivalent to calling getDatasetInfoJson for every active dataset.
    */
  def listDatasetsFullJson(orgId: String): List[JsValue] = {
    repo.listActiveDatasets(orgId).map { ds =>
      val state = repo.getState(ds.spec)
      val harvestConfig = repo.getHarvestConfig(ds.spec)
      val schedule = repo.getHarvestSchedule(ds.spec)
      val mappingConfig = repo.getMappingConfig(ds.spec)
      val indexing = repo.getIndexing(ds.spec)
      buildJson(ds, state, harvestConfig, schedule, mappingConfig, indexing)
    }
  }

  // ---------------------------------------------------------------------------
  // JSON builders
  // ---------------------------------------------------------------------------

  private def buildJson(
      ds: DatasetRecord,
      state: Option[DatasetStateRecord],
      harvestConfig: Option[HarvestConfigRecord],
      schedule: Option[HarvestScheduleRecord],
      mappingConfig: Option[MappingConfigRecord],
      indexing: Option[IndexingRecord]
  ): JsValue = {
    // Core identity fields (always present, same as toSimpleJson)
    val core: Seq[(String, JsValue)] = Seq(
      "datasetSpec" -> JsString(ds.spec),
      "spec" -> JsString(ds.spec),
      "orgId" -> JsString(ds.orgId)
    )

    val datasetFields = buildDatasetFields(ds)
    val stateFields = state.map(buildStateFields).getOrElse(Nil)
    val harvestFields = harvestConfig.map(buildHarvestConfigFields).getOrElse(Nil)
    val scheduleFields = schedule.map(buildScheduleFields).getOrElse(Nil)
    val mappingFields = mappingConfig.map(buildMappingConfigFields).getOrElse(Nil)
    val indexingFields = indexing.map(buildIndexingFields).getOrElse(Nil)
    val computedFields = buildComputedFields(state, mappingConfig)

    JsObject(
      core ++ datasetFields ++ stateFields ++ harvestFields ++
        scheduleFields ++ mappingFields ++ indexingFields ++ computedFields
    )
  }

  private def buildLightJson(
      ds: DatasetRecord,
      state: Option[DatasetStateRecord],
      harvestConfig: Option[HarvestConfigRecord]
  ): JsValue = {
    val core: Seq[(String, JsValue)] = Seq(
      "datasetSpec" -> JsString(ds.spec),
      "spec" -> JsString(ds.spec),
      "orgId" -> JsString(ds.orgId)
    )

    val datasetFields = buildDatasetFields(ds)
    val stateFields = state.map(buildStateFields).getOrElse(Nil)
    val harvestFields = harvestConfig.map(buildHarvestConfigFields).getOrElse(Nil)

    JsObject(core ++ datasetFields ++ stateFields ++ harvestFields)
  }

  // ---------------------------------------------------------------------------
  // Field builders — each produces only non-None fields
  // ---------------------------------------------------------------------------

  /** Dataset metadata fields matching DsInfo.webSocketFields names. */
  private def buildDatasetFields(ds: DatasetRecord): Seq[(String, JsValue)] = {
    optStr("datasetName", ds.name) ++
      optStr("datasetDescription", ds.description) ++
      optStr("datasetAggregator", ds.aggregator) ++
      optStr("datasetOwner", ds.owner) ++
      optStr("datasetLanguage", ds.language) ++
      optStr("datasetRights", ds.rights) ++
      optStr("datasetType", ds.datasetType) ++
      optStr("edmType", ds.edmType) ++
      optStr("datasetDataProviderURL", ds.dataProviderUrl)
  }

  /** State and counter fields. */
  private def buildStateFields(state: DatasetStateRecord): Seq[(String, JsValue)] = {
    Seq(
      "currentState" -> JsString(state.state),
      "datasetRecordCount" -> JsNumber(state.recordCount),
      "sourceRecordCount" -> JsNumber(state.sourceCount),
      "acquiredRecordCount" -> JsNumber(state.acquiredCount),
      "deletedRecordCount" -> JsNumber(state.deletedCount),
      "processedValid" -> JsNumber(state.processedValid),
      "processedInvalid" -> JsNumber(state.processedInvalid),
      "processedIncrementalValid" -> JsNumber(state.processedIncrementalValid),
      "processedIncrementalInvalid" -> JsNumber(state.processedIncrementalInvalid)
    ) ++
      optStr("acquisitionMethod", state.acquisitionMethod) ++
      optStr("datasetErrorMessage", state.errorMessage) ++
      optInstant("datasetErrorTime", state.errorTime) ++
      optStr("datasetCurrentOperation", state.currentOperation) ++
      optInstant("datasetOperationStartTime", state.operationStart) ++
      optStr("datasetOperationTrigger", state.operationTrigger) ++
      optInstant("delimitersSet", state.delimiterSet)
  }

  /** Harvest configuration fields. */
  private def buildHarvestConfigFields(hc: HarvestConfigRecord): Seq[(String, JsValue)] = {
    optStr("harvestType", hc.harvestType) ++
      optStr("harvestURL", hc.harvestUrl) ++
      optStr("harvestDataset", hc.harvestDataset) ++
      optStr("harvestPrefix", hc.harvestPrefix) ++
      optStr("harvestRecord", hc.harvestRecord) ++
      optStr("harvestSearch", hc.harvestSearch) ++
      optStr("harvestDownloadURL", hc.harvestDownloadUrl) ++
      optStr("recordRoot", hc.recordRoot) ++
      optStr("uniqueId", hc.uniqueId) ++
      optBool("harvestContinueOnError", Some(hc.continueOnError)) ++
      optInt("harvestErrorThreshold", hc.errorThreshold) ++
      optStr("idFilterType", hc.idFilterType) ++
      optStr("idFilterExpression", hc.idFilterExpression)
  }

  /** Harvest schedule fields. */
  private def buildScheduleFields(sched: HarvestScheduleRecord): Seq[(String, JsValue)] = {
    optStr("harvestDelay", sched.delay) ++
      optStr("harvestDelayUnit", sched.delayUnit) ++
      Seq("harvestIncrementalMode" -> JsBoolean(sched.incremental)) ++
      optInstant("harvestPreviousTime", sched.previousTime) ++
      optInstant("lastFullHarvestTime", sched.lastFullHarvest) ++
      optInstant("lastIncrementalHarvestTime", sched.lastIncrementalHarvest)
  }

  /** Mapping and publish configuration fields. */
  private def buildMappingConfigFields(mc: MappingConfigRecord): Seq[(String, JsValue)] = {
    Seq(
      "publishOAIPMH" -> JsBoolean(mc.publishOaipmh),
      "publishIndex" -> JsBoolean(mc.publishIndex),
      "publishLOD" -> JsBoolean(mc.publishLod),
      "categoriesInclude" -> JsBoolean(mc.categoriesInclude)
    ) ++
      optStr("datasetMapToPrefix", mc.mapToPrefix) ++
      optStr("mappingSource", mc.mappingSource) ++
      optStr("defaultMappingPrefix", mc.defaultMappingPrefix) ++
      optStr("defaultMappingName", mc.defaultMappingName)
  }

  /** Indexing result fields. */
  private def buildIndexingFields(idx: IndexingRecord): Seq[(String, JsValue)] = {
    optInt("indexingRecordsIndexed", idx.recordsIndexed) ++
      optInt("indexingRecordsExpected", idx.recordsExpected) ++
      optInt("indexingOrphansDeleted", idx.orphansDeleted) ++
      optInt("indexingErrorCount", idx.errorCount) ++
      optStr("indexingLastStatus", idx.lastStatus) ++
      optStr("indexingLastMessage", idx.lastMessage) ++
      optInstant("indexingLastTimestamp", idx.lastTimestamp) ++
      optInt("indexingLastRevision", idx.lastRevision)
  }

  /** Computed fields for backward compatibility with toSimpleJson.
    *
    * DsInfo.toSimpleJson adds duplicate fields with "dataset" prefix and an
    * additional "errorMessage" alias. We replicate that here.
    */
  private def buildComputedFields(
      state: Option[DatasetStateRecord],
      mappingConfig: Option[MappingConfigRecord]
  ): Seq[(String, JsValue)] = {
    val mappingSrc = mappingConfig.flatMap(_.mappingSource).getOrElse("manual")
    val mappingPrefix = mappingConfig.flatMap(_.defaultMappingPrefix)
    val mappingName = mappingConfig.flatMap(_.defaultMappingName)
    val mappingVersion = mappingConfig.flatMap(_.defaultMappingVersion)
    val errorMsg = state.flatMap(_.errorMessage)

    Seq("datasetMappingSource" -> JsString(mappingSrc)) ++
      optStr("datasetDefaultMappingPrefix", mappingPrefix) ++
      optStr("datasetDefaultMappingName", mappingName) ++
      optStr("datasetDefaultMappingVersion", mappingVersion) ++
      optStr("errorMessage", errorMsg)
  }

  // ---------------------------------------------------------------------------
  // Helpers — produce empty Seq for None values (omit from JSON)
  // ---------------------------------------------------------------------------

  private def optStr(name: String, value: Option[String]): Seq[(String, JsValue)] =
    value.map(v => Seq(name -> JsString(v))).getOrElse(Nil)

  private def optInt(name: String, value: Option[Int]): Seq[(String, JsValue)] =
    value.map(v => Seq(name -> JsNumber(v))).getOrElse(Nil)

  private def optBool(name: String, value: Option[Boolean]): Seq[(String, JsValue)] =
    value.map(v => Seq(name -> JsBoolean(v))).getOrElse(Nil)

  private def optInstant(name: String, value: Option[Instant]): Seq[(String, JsValue)] =
    value.map(v => Seq(name -> JsString(DateTimeFormatter.ISO_INSTANT.format(v)))).getOrElse(Nil)
}

/** Global singleton holder for DsInfoService.
  *
  * Follows the same pattern as [[GlobalDatabaseService]]. Will be replaced by
  * proper Guice DI in Phase 3.
  */
object GlobalDsInfoService {
  @volatile private var instance: Option[DsInfoService] = None

  def set(service: DsInfoService): Unit = {
    instance = Some(service)
  }

  def get(): Option[DsInfoService] = instance

  def getOrThrow(): DsInfoService = instance.getOrElse(
    throw new IllegalStateException("DsInfoService not configured")
  )

  def clear(): Unit = {
    instance = None
  }
}
