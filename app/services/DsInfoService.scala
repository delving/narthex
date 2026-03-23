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
    val harvestable = repo.listHarvestableDatasets(orgId)
    val harvestableMap = harvestable.map(h => h.spec -> h).toMap
    repo.listActiveDatasets(orgId).map { ds =>
      val state = repo.getState(ds.spec)
      val harvestConfig = repo.getHarvestConfig(ds.spec)
      val mappingConfig = repo.getMappingConfig(ds.spec)
      val baseJson = buildLightJsonFull(ds, state, harvestConfig, mappingConfig)
      val extra = harvestableMap.get(ds.spec) match {
        case Some(h) if h.errorMessage.isDefined =>
          val retryTime: JsValue = h.nextRetryAt
            .map(t => JsString(DateTimeFormatter.ISO_INSTANT.format(t)))
            .getOrElse(JsNull)
          Json.obj(
            "harvestInRetry" -> true,
            "harvestRetryCount" -> h.retryCount,
            "harvestLastRetryTime" -> retryTime,
            "harvestRetryMessage" -> h.errorMessage.get
          )
        case _ =>
          Json.obj("harvestInRetry" -> false)
      }
      JsObject(baseJson.as[JsObject].fields ++ extra.fields).as[JsObject]
    }
  }

  /** Full list JSON matching DsInfoLight JSON shape exactly.
    *
    * Includes all state timestamp fields (stateSaved, stateRaw, etc.),
    * harvest credentials flags, mapping fields, and retry status.
    */
  def listDatasetsLightJson(orgId: String): List[JsValue] = {
    val harvestable = repo.listHarvestableDatasets(orgId)
    val harvestableMap = harvestable.map(h => h.spec -> h).toMap
    repo.listActiveDatasets(orgId).map { ds =>
      val state = repo.getState(ds.spec)
      val harvestConfig = repo.getHarvestConfig(ds.spec)
      val mappingConfig = repo.getMappingConfig(ds.spec)
      val baseJson = buildLightJsonFull(ds, state, harvestConfig, mappingConfig)
      val extra = harvestableMap.get(ds.spec) match {
        case Some(h) if h.errorMessage.isDefined =>
          val retryTime: JsValue = h.nextRetryAt
            .map(t => JsString(DateTimeFormatter.ISO_INSTANT.format(t)))
            .getOrElse(JsNull)
          Json.obj(
            "harvestInRetry" -> true,
            "harvestRetryCount" -> h.retryCount,
            "harvestLastRetryTime" -> retryTime,
            "harvestRetryMessage" -> h.errorMessage.get
          )
        case _ =>
          Json.obj("harvestInRetry" -> false)
      }
      JsObject(baseJson.as[JsObject].fields ++ extra.fields).as[JsObject]
    }
  }

  /** List all datasets (including deleted) with full light JSON shape. */
  def listAllDatasetsLightJson(orgId: String): List[JsValue] = {
    repo.listDatasets(orgId).map { ds =>
      val state = repo.getState(ds.spec)
      val harvestConfig = repo.getHarvestConfig(ds.spec)
      val mappingConfig = repo.getMappingConfig(ds.spec)
      buildLightJsonFull(ds, state, harvestConfig, mappingConfig)
    }
  }

  /** List active datasets (non-deleted) matching listDatasetsLightJson shape. */
  def listActiveDatasetsLightJson(orgId: String): List[JsValue] =
    listDatasetsLightJson(orgId)

  /** Datasets ready for periodic harvest scheduling.
    *
    * Replaces DsInfo.listDsInfoWithStateFilter. One SQL query — no RDF reads.
    * Only datasets where:
    * - state is SAVED or INCREMENTAL_SAVED
    * - no active workflow (running/pending/retry)
    * - schedule interval has passed
    */
  def listHarvestableNow(orgId: String): List[HarvestableDataset] =
    repo.listHarvestableDatasets(orgId)

  /** Datasets in error state eligible for retry.
    *
    * Replaces DsInfo.listDsInfoInRetry. One SQL query.
    */
  def listRetryableNow(orgId: String, retryIntervalMinutes: Int): List[HarvestableDataset] =
    repo.listRetryableDatasets(orgId, retryIntervalMinutes)

  /** List all datasets with state for index statistics.
    *
    * Replaces IndexStatsService.fetchNarthexDatasets which used SPARQL.
    * Returns all datasets (including deleted) with their state, counts, and deleted flag.
    */
  def listDatasetsWithState(orgId: String): List[DatasetWithState] =
    repo.listDatasetsWithState(orgId)

  /** List all distinct harvest dataset identifiers.
    *
    * Replaces DatasetDiscoveryService.getExistingHarvestDatasets which used SPARQL.
    */
  def listAllHarvestDatasets(orgId: String): List[String] =
    repo.listAllHarvestDatasets(orgId)

  // ==========================================================================
  // Write methods — Phase 3: PostgreSQL as primary store for state, errors,
  // operation tracking, record counts, and sync flags.
  // All writes go to PostgreSQL via the repository's upsert methods.
  // ==========================================================================

  /** Update the dataset state (e.g. "SOURCED", "PROCESSED", "SAVED").
    *
    * Equivalent to DsInfo.setState().
    */
  def setState(spec: String, state: String): Unit = {
    repo.getState(spec) match {
      case Some(existing) =>
        repo.upsertState(existing.copy(
          state = state,
          stateChangedAt = Instant.now()
        ))
      case None =>
        repo.upsertState(DatasetStateRecord(
          spec = spec,
          state = state,
          stateChangedAt = Instant.now()
        ))
    }
  }

  /** Remove a workflow state by resetting to CREATED.
    *
    * Equivalent to DsInfo.removeState(). Clears the state timestamp by setting
    * state back to the initial CREATED state.
    */
  def removeState(spec: String, state: String): Unit = {
    repo.getState(spec) match {
      case Some(existing) =>
        repo.upsertState(existing.copy(
          state = "CREATED",
          stateChangedAt = Instant.now()
        ))
      case None =>
        repo.upsertState(DatasetStateRecord(
          spec = spec,
          state = "CREATED",
          stateChangedAt = Instant.now()
        ))
    }
  }

  /** Clear all workflow states by resetting to CREATED.
    *
    * Equivalent to DsInfo.clearWorkflowStates(). Removes timestamps for all
    * post-harvest states (RAW, SOURCED, MAPPABLE, etc.) by resetting to CREATED.
    */
  def clearWorkflowStates(spec: String): Unit = {
    repo.getState(spec) match {
      case Some(existing) =>
        repo.upsertState(existing.copy(
          state = "CREATED",
          stateChangedAt = Instant.now()
        ))
      case None =>
        repo.upsertState(DatasetStateRecord(
          spec = spec,
          state = "CREATED",
          stateChangedAt = Instant.now()
        ))
    }
  }

  /** Clear any error state on a dataset.
    *
    * Equivalent to DsInfo.clearError().
    */
  def clearError(spec: String): Unit = {
    repo.getState(spec).foreach { existing =>
      repo.upsertState(existing.copy(
        errorMessage = None,
        errorTime = None
      ))
    }
  }

  /** Set an error message on a dataset.
    *
    * Equivalent to DsInfo.setError().
    */
  def setError(spec: String, message: String): Unit = {
    repo.getState(spec) match {
      case Some(existing) =>
        repo.upsertState(existing.copy(
          errorMessage = Some(message),
          errorTime = Some(Instant.now())
        ))
      case None =>
        repo.upsertState(DatasetStateRecord(
          spec = spec,
          errorMessage = Some(message),
          errorTime = Some(Instant.now())
        ))
    }
  }

  /** Clear retry state — called when a retry succeeds or is abandoned.
    *
    * Clears the inRetry flag and resets retry counters.
    */
  def clearRetryState(spec: String): Unit = {
    repo.getState(spec).foreach { existing =>
      repo.upsertState(existing.copy(
        currentOperation = None,
        operationStart = None
      ))
    }
  }

  /** Set the current operation (e.g. "HARVEST", "ANALYZE", "SAVE").
    *
    * Equivalent to DsInfo.setCurrentOperation().
    */
  def setCurrentOperation(spec: String, operation: String, trigger: String): Unit = {
    repo.getState(spec) match {
      case Some(existing) =>
        repo.upsertState(existing.copy(
          currentOperation = Some(operation),
          operationStart = Some(Instant.now()),
          operationTrigger = Some(trigger)
        ))
      case None =>
        repo.upsertState(DatasetStateRecord(
          spec = spec,
          currentOperation = Some(operation),
          operationStart = Some(Instant.now()),
          operationTrigger = Some(trigger)
        ))
    }
  }

  /** Clear the current operation — called when a step completes.
    *
    * Equivalent to DsInfo.completeOperation() and clearOperation().
    */
  def clearOperation(spec: String): Unit = {
    repo.getState(spec).foreach { existing =>
      repo.upsertState(existing.copy(
        currentOperation = None,
        operationStart = None,
        operationTrigger = None
      ))
    }
  }

  /** Datasets with active (non-completed) operations — replaces
    * DsInfo.listDsInfoWithIncompleteOperations. Reads from PostgreSQL
    * instead of SPARQL. Used by OrgActor.performStartupRecovery().
    */
  def listDatasetsWithActiveOperation(orgId: String): List[ActiveOperationRecord] =
    repo.listDatasetsWithActiveOperation(orgId)

  /** Set record counts after processing.
    *
    * Equivalent to DsInfo.setRecordCount() and setAcquisitionCounts().
    */
  def setRecordCounts(
      spec: String,
      recordCount: Int,
      acquiredCount: Int,
      deletedCount: Int,
      sourceCount: Int,
      acquisitionMethod: String
  ): Unit = {
    repo.getState(spec) match {
      case Some(existing) =>
        repo.upsertState(existing.copy(
          recordCount = recordCount,
          acquiredCount = acquiredCount,
          deletedCount = deletedCount,
          sourceCount = sourceCount,
          acquisitionMethod = Some(acquisitionMethod)
        ))
      case None =>
        repo.upsertState(DatasetStateRecord(
          spec = spec,
          recordCount = recordCount,
          acquiredCount = acquiredCount,
          deletedCount = deletedCount,
          sourceCount = sourceCount,
          acquisitionMethod = Some(acquisitionMethod)
        ))
    }
  }

  /** Update dataset metadata fields.
    *
    * Equivalent to DsInfo.setMetadata(). Updates name, description, aggregator,
    * owner, dataProviderURL, language, rights, datasetType, and edmType.
    */
  def setMetadata(spec: String, name: String, description: String, aggregator: String,
                  owner: String, dataProviderURL: String, language: String,
                  rights: String, dataType: String, edmType: String): Unit = {
    repo.getDataset(spec) match {
      case Some(existing) =>
        repo.updateDataset(existing.copy(
          name = if (name.isEmpty) None else Some(name),
          description = if (description.isEmpty) None else Some(description),
          aggregator = if (aggregator.isEmpty) None else Some(aggregator),
          owner = if (owner.isEmpty) None else Some(owner),
          dataProviderUrl = if (dataProviderURL.isEmpty) None else Some(dataProviderURL),
          language = if (language.isEmpty) None else Some(language),
          rights = if (rights.isEmpty) None else Some(rights),
          datasetType = if (dataType.isEmpty) None else Some(dataType),
          edmType = if (edmType.isEmpty) None else Some(edmType),
          updatedAt = Instant.now()
        ))
      case None =>
        repo.updateDataset(DatasetRecord(
          spec = spec,
          orgId = "",
          name = if (name.isEmpty) None else Some(name),
          description = if (description.isEmpty) None else Some(description),
          aggregator = if (aggregator.isEmpty) None else Some(aggregator),
          owner = if (owner.isEmpty) None else Some(owner),
          dataProviderUrl = if (dataProviderURL.isEmpty) None else Some(dataProviderURL),
          language = if (language.isEmpty) None else Some(language),
          rights = if (rights.isEmpty) None else Some(rights),
          datasetType = if (dataType.isEmpty) None else Some(dataType),
          edmType = if (edmType.isEmpty) None else Some(edmType)
        ))
    }
  }

  /** Set processed record counts (valid/invalid).
    *
    * Equivalent to DsInfo.setProcessedRecordCounts().
    */
  def setProcessedCounts(spec: String, valid: Int, invalid: Int): Unit = {
    repo.getState(spec) match {
      case Some(existing) =>
        repo.upsertState(existing.copy(
          processedValid = valid,
          processedInvalid = invalid
        ))
      case None =>
        repo.upsertState(DatasetStateRecord(
          spec = spec,
          processedValid = valid,
          processedInvalid = invalid
        ))
    }
  }

  /** Set incremental processed record counts.
    *
    * Equivalent to DsInfo.setIncrementalProcessedRecordCounts().
    */
  def setIncrementalProcessedCounts(spec: String, valid: Int, invalid: Int): Unit = {
    repo.getState(spec) match {
      case Some(existing) =>
        repo.upsertState(existing.copy(
          processedIncrementalValid = valid,
          processedIncrementalInvalid = invalid
        ))
      case None =>
        repo.upsertState(DatasetStateRecord(
          spec = spec,
          processedIncrementalValid = valid,
          processedIncrementalInvalid = invalid
        ))
    }
  }

  // ==========================================================================
  // Harvest config write methods
  // ==========================================================================

  /** Set harvest source configuration.
    *
    * Equivalent to DsInfo.setHarvestInfo() and setDelimiters().
    */
  def upsertHarvestConfig(
      spec: String,
      harvestType: Option[String] = None,
      harvestUrl: Option[String] = None,
      harvestDataset: Option[String] = None,
      harvestPrefix: Option[String] = None,
      harvestRecord: Option[String] = None,
      harvestDownloadUrl: Option[String] = None,
      recordRoot: Option[String] = None,
      uniqueId: Option[String] = None,
      recordContainer: Option[String] = None,
      sourceType: Option[String] = None,
      oaiSourceId: Option[String] = None,
      continueOnError: Boolean = false,
      errorThreshold: Option[Int] = None
  ): Unit = {
    repo.getHarvestConfig(spec) match {
      case Some(existing) =>
        repo.upsertHarvestConfig(existing.copy(
          harvestType = harvestType.orElse(existing.harvestType),
          harvestUrl = harvestUrl.orElse(existing.harvestUrl),
          harvestDataset = harvestDataset.orElse(existing.harvestDataset),
          harvestPrefix = harvestPrefix.orElse(existing.harvestPrefix),
          harvestRecord = harvestRecord.orElse(existing.harvestRecord),
          harvestDownloadUrl = harvestDownloadUrl.orElse(existing.harvestDownloadUrl),
          recordRoot = recordRoot.orElse(existing.recordRoot),
          uniqueId = uniqueId.orElse(existing.uniqueId),
          recordContainer = recordContainer.orElse(existing.recordContainer),
          sourceType = sourceType.orElse(existing.sourceType),
          oaiSourceId = oaiSourceId.orElse(existing.oaiSourceId),
          continueOnError = if (continueOnError) true else existing.continueOnError,
          errorThreshold = errorThreshold.orElse(existing.errorThreshold)
        ))
      case None =>
        repo.upsertHarvestConfig(HarvestConfigRecord(
          spec = spec,
          harvestType = harvestType,
          harvestUrl = harvestUrl,
          harvestDataset = harvestDataset,
          harvestPrefix = harvestPrefix,
          harvestRecord = harvestRecord,
          harvestDownloadUrl = harvestDownloadUrl,
          recordRoot = recordRoot,
          uniqueId = uniqueId,
          recordContainer = recordContainer,
          sourceType = sourceType,
          oaiSourceId = oaiSourceId,
          continueOnError = continueOnError,
          errorThreshold = errorThreshold
        ))
    }
  }

  /** Mark delimiters as set (recordRoot and uniqueId configured).
    *
    * Sets the delimiterSet timestamp.
    */
  def setDelimiters(spec: String, recordRoot: String, uniqueId: String, recordContainer: Option[String] = None): Unit = {
    upsertHarvestConfig(spec, recordRoot = Some(recordRoot), uniqueId = Some(uniqueId), recordContainer = recordContainer)
    repo.getState(spec) match {
      case Some(existing) =>
        repo.upsertState(existing.copy(delimiterSet = Some(Instant.now())))
      case None =>
        repo.upsertState(DatasetStateRecord(spec = spec, delimiterSet = Some(Instant.now())))
    }
  }

  // ==========================================================================
  // Harvest schedule write methods
  // ==========================================================================

  /** Update harvest schedule.
    *
    * Equivalent to DsInfo.setHarvestCron() and setLastHarvestTime().
    */
  def upsertHarvestSchedule(
      spec: String,
      delay: Option[String] = None,
      delayUnit: Option[String] = None,
      incremental: Boolean = false,
      previousTime: Option[Instant] = None,
      lastFullHarvest: Option[Instant] = None,
      lastIncrementalHarvest: Option[Instant] = None
  ): Unit = {
    repo.getHarvestSchedule(spec) match {
      case Some(existing) =>
        repo.upsertHarvestSchedule(existing.copy(
          delay = delay.orElse(existing.delay),
          delayUnit = delayUnit.orElse(existing.delayUnit),
          incremental = if (incremental) true else existing.incremental,
          previousTime = previousTime.orElse(existing.previousTime),
          lastFullHarvest = lastFullHarvest.orElse(existing.lastFullHarvest),
          lastIncrementalHarvest = lastIncrementalHarvest.orElse(existing.lastIncrementalHarvest)
        ))
      case None =>
        repo.upsertHarvestSchedule(HarvestScheduleRecord(
          spec = spec,
          delay = delay,
          delayUnit = delayUnit,
          incremental = incremental,
          previousTime = previousTime,
          lastFullHarvest = lastFullHarvest,
          lastIncrementalHarvest = lastIncrementalHarvest
        ))
    }
  }

  // ==========================================================================
  // Mapping config write methods
  // ==========================================================================

  /** Update mapping and publish configuration.
    *
    * Equivalent to DsInfo.setMappingSource() and setMetadata().
    */
  def upsertMappingConfig(
      spec: String,
      mappingSource: Option[String] = None,
      defaultMappingPrefix: Option[String] = None,
      defaultMappingName: Option[String] = None,
      defaultMappingVersion: Option[String] = None,
      mapToPrefix: Option[String] = None,
      publishOaipmh: Option[Boolean] = None,
      publishIndex: Option[Boolean] = None,
      publishLod: Option[Boolean] = None,
      categoriesInclude: Option[Boolean] = None
  ): Unit = {
    repo.getMappingConfig(spec) match {
      case Some(existing) =>
        repo.upsertMappingConfig(existing.copy(
          mappingSource = mappingSource.orElse(existing.mappingSource),
          defaultMappingPrefix = defaultMappingPrefix.orElse(existing.defaultMappingPrefix),
          defaultMappingName = defaultMappingName.orElse(existing.defaultMappingName),
          defaultMappingVersion = defaultMappingVersion.orElse(existing.defaultMappingVersion),
          mapToPrefix = mapToPrefix.orElse(existing.mapToPrefix),
          publishOaipmh = publishOaipmh.getOrElse(existing.publishOaipmh),
          publishIndex = publishIndex.getOrElse(existing.publishIndex),
          publishLod = publishLod.getOrElse(existing.publishLod),
          categoriesInclude = categoriesInclude.getOrElse(existing.categoriesInclude)
        ))
      case None =>
        repo.upsertMappingConfig(MappingConfigRecord(
          spec = spec,
          mappingSource = mappingSource,
          defaultMappingPrefix = defaultMappingPrefix,
          defaultMappingName = defaultMappingName,
          defaultMappingVersion = defaultMappingVersion,
          mapToPrefix = mapToPrefix,
          publishOaipmh = publishOaipmh.getOrElse(true),
          publishIndex = publishIndex.getOrElse(true),
          publishLod = publishLod.getOrElse(true),
          categoriesInclude = categoriesInclude.getOrElse(false)
        ))
    }
  }

  // ==========================================================================
  // Indexing write methods
  // ==========================================================================

  /** Update indexing result from Hub3 webhook.
    *
    * Equivalent to DsInfo.setIndexingResults().
    */
  def upsertIndexing(
      spec: String,
      recordsIndexed: Option[Int] = None,
      recordsExpected: Option[Int] = None,
      orphansDeleted: Option[Int] = None,
      errorCount: Option[Int] = None,
      lastStatus: Option[String] = None,
      lastMessage: Option[String] = None,
      lastRevision: Option[Int] = None
  ): Unit = {
    repo.getIndexing(spec) match {
      case Some(existing) =>
        repo.upsertIndexing(existing.copy(
          recordsIndexed = recordsIndexed.orElse(existing.recordsIndexed),
          recordsExpected = recordsExpected.orElse(existing.recordsExpected),
          orphansDeleted = orphansDeleted.orElse(existing.orphansDeleted),
          errorCount = errorCount.orElse(existing.errorCount),
          lastStatus = lastStatus.orElse(existing.lastStatus),
          lastMessage = lastMessage.orElse(existing.lastMessage),
          lastTimestamp = Some(Instant.now()),
          lastRevision = lastRevision.orElse(existing.lastRevision)
        ))
      case None =>
        repo.upsertIndexing(IndexingRecord(
          spec = spec,
          recordsIndexed = recordsIndexed,
          recordsExpected = recordsExpected,
          orphansDeleted = orphansDeleted,
          errorCount = errorCount,
          lastStatus = lastStatus,
          lastMessage = lastMessage,
          lastTimestamp = Some(Instant.now()),
          lastRevision = lastRevision
        ))
    }
  }

  /** Get mapping configuration for a dataset from PostgreSQL.
    *
    * Equivalent to DsInfo.getMappingSource(), getDefaultMappingPrefix/Name/Version().
    */
  def getMappingConfig(spec: String): Option[MappingConfigRecord] =
    repo.getMappingConfig(spec)

  /** Get source facts for a dataset from PostgreSQL.
    *
    * Reads from the `dataset_harvest_config` table. Returns `None` if the
    * dataset doesn't exist or if the required fields (sourceType, recordRoot,
    * uniqueId) are not yet set.
    *
    * Equivalent to reading `source_facts.txt` via [[dataset.SourceRepo.SourceFacts]].
    */
  def getSourceFacts(spec: String): Option[SourceFactsRecord] = {
    repo.getHarvestConfig(spec).flatMap { hc =>
      for {
        st <- hc.sourceType
        rr <- hc.recordRoot
        uid <- hc.uniqueId
      } yield SourceFactsRecord(st, rr, uid, hc.recordContainer)
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

  /** Full light JSON matching DsInfoLight output exactly.
    *
    * Includes all per-state timestamp fields (stateSaved, stateRaw, etc.),
    * full harvest config fields, and mapping config fields.
    */
  private def buildLightJsonFull(
      ds: DatasetRecord,
      state: Option[DatasetStateRecord],
      harvestConfig: Option[HarvestConfigRecord],
      mappingConfig: Option[MappingConfigRecord]
  ): JsValue = {
    val core: Seq[(String, JsValue)] = Seq(
      "spec" -> JsString(ds.spec),
      "name" -> ds.name.map(JsString).getOrElse(JsNull)
    )

    val stateFields = state.map(s => buildStateFieldsFull(s)).getOrElse(Nil)
    val harvestFields = harvestConfig.flatMap(h => state.map(s => buildHarvestConfigFieldsFull(h, s))).getOrElse(Nil)
    val mappingFields = mappingConfig.map(buildMappingConfigFieldsFull).getOrElse(Nil)

    JsObject(core ++ stateFields ++ harvestFields ++ mappingFields)
  }

  /** State fields matching DsInfoLight exactly — includes per-state timestamp fields. */
  private def buildStateFieldsFull(state: DatasetStateRecord): Seq[(String, JsValue)] = {
    val changedAtVal: JsValue = JsString(DateTimeFormatter.ISO_INSTANT.format(state.stateChangedAt))
    val allStates = List(
      "stateDisabled", "stateRaw", "stateRawAnalyzed", "stateSourced",
      "stateMappable", "stateProcessable", "stateAnalyzed", "stateProcessed",
      "stateSaved", "stateIncrementalSaved"
    )
    val stateTimestampFields: Seq[(String, JsValue)] = allStates.flatMap { fieldName =>
      if (stateTimestampMatches(state, fieldName))
        Seq(fieldName -> changedAtVal)
      else
        Nil
    }
    val counters: Seq[(String, JsValue)] = Seq(
      "recordCount" -> JsNumber(state.recordCount),
      "processedValid" -> JsNumber(state.processedValid),
      "processedInvalid" -> JsNumber(state.processedInvalid),
      "acquiredRecordCount" -> JsNumber(state.acquiredCount),
      "deletedRecordCount" -> JsNumber(state.deletedCount),
      "sourceRecordCount" -> JsNumber(state.sourceCount),
      "acquisitionMethod" -> state.acquisitionMethod.map(JsString).getOrElse(JsNull),
      "processedIncrementalValid" -> JsNumber(state.processedIncrementalValid),
      "processedIncrementalInvalid" -> JsNumber(state.processedIncrementalInvalid)
    )
    val errorFields: Seq[(String, JsValue)] = Seq(
      "errorMessage" -> state.errorMessage.map(JsString).getOrElse(JsNull),
      "errorTime" -> state.errorTime.map(t => JsString(DateTimeFormatter.ISO_INSTANT.format(t))).getOrElse(JsNull)
    )
    val operationFields: Seq[(String, JsValue)] = Seq(
      "currentOperation" -> state.currentOperation.map(JsString).getOrElse(JsNull),
      "operationStatus" -> JsNull,
      "currentState" -> JsString(state.state)
    )
    stateTimestampFields ++ counters ++ errorFields ++ operationFields
  }

  private def stateTimestampMatches(state: DatasetStateRecord, stateField: String): Boolean = {
    val stateName = stateField.stripPrefix("state")
    state.state == s"state${stateName.capitalize}" ||
    state.state == stateField
  }

  /** Full harvest config fields matching DsInfoLight exactly. */
  private def buildHarvestConfigFieldsFull(hc: HarvestConfigRecord, state: DatasetStateRecord): Seq[(String, JsValue)] = {
    Seq(
      "harvestType" -> hc.harvestType.map(JsString).getOrElse(JsNull),
      "harvestDownloadURL" -> hc.harvestDownloadUrl.map(JsString).getOrElse(JsNull),
      "harvestIncrementalMode" -> JsNull,
      "delimitersSet" -> state.delimiterSet.map(t => JsString(DateTimeFormatter.ISO_INSTANT.format(t))).getOrElse(JsNull),
      "recordRoot" -> hc.recordRoot.map(JsString).getOrElse(JsNull),
      "uniqueId" -> hc.uniqueId.map(JsString).getOrElse(JsNull),
      "harvestUsername" -> JsNull,
      "harvestPasswordSet" -> JsBoolean(false),
      "harvestApiKeySet" -> JsBoolean(false)
    )
  }

  /** Full mapping config fields matching DsInfoLight exactly. */
  private def buildMappingConfigFieldsFull(mc: MappingConfigRecord): Seq[(String, JsValue)] = {
    Seq(
      "mappingSource" -> mc.mappingSource.map(JsString).getOrElse(JsNull),
      "defaultMappingPrefix" -> mc.defaultMappingPrefix.map(JsString).getOrElse(JsNull),
      "defaultMappingName" -> mc.defaultMappingName.map(JsString).getOrElse(JsNull)
    )
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
/** Source facts for a dataset — the 4 fields needed by PocketParser.
  *
  * Equivalent to [[dataset.SourceRepo.SourceFacts]] but read from PostgreSQL
  * (dataset_harvest_config table) instead of source_facts.txt.
  */
case class SourceFactsRecord(
    sourceType: String,
    recordRoot: String,
    uniqueId: String,
    recordContainer: Option[String]
)

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
