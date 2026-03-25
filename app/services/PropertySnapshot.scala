package services

import java.time.Instant
import java.time.format.DateTimeFormatter

/** Builds a Map[String, String] keyed by NXProp name from PostgreSQL records.
  *
  * This snapshot replaces the Fuseki RDF graph fetch for DsInfo.getLiteralProp.
  * One snapshot is loaded per DsInfo instance at construction time. All property
  * values are stored as strings to match the NXProp string-based interface.
  */
object PropertySnapshot {

  private val isoFormatter = DateTimeFormatter.ISO_INSTANT

  /** Load all dataset properties from PostgreSQL for a given spec.
    *
    * Queries 6 tables (datasets, dataset_state, dataset_harvest_config,
    * dataset_harvest_schedule, dataset_mapping_config, dataset_indexing)
    * and returns a flat map keyed by NXProp name.
    *
    * Returns an empty map if the dataset doesn't exist.
    */
  def load(repo: DatasetRepository, spec: String): Map[String, String] = {
    val props = Map.newBuilder[String, String]

    // datasets table
    repo.getDataset(spec).foreach { ds =>
      ds.name.foreach(v => props += "datasetName" -> v)
      ds.description.foreach(v => props += "datasetDescription" -> v)
      ds.owner.foreach(v => props += "datasetOwner" -> v)
      ds.datasetType.foreach(v => props += "datasetType" -> v)
      ds.character.foreach(v => props += "datasetCharacter" -> v)
      ds.language.foreach(v => props += "datasetLanguage" -> v)
      ds.rights.foreach(v => props += "datasetRights" -> v)
      ds.aggregator.foreach(v => props += "datasetAggregator" -> v)
      ds.dataProviderUrl.foreach(v => props += "datasetDataProviderURL" -> v)
      ds.edmType.foreach(v => props += "edmType" -> v)
      props += "orgId" -> ds.orgId
      props += "datasetSpec" -> ds.spec
      if (ds.tags.nonEmpty) props += "datasetTags" -> ds.tags.mkString(",")
    }

    // dataset_state table
    repo.getState(spec).foreach { st =>
      props += "datasetRecordCount" -> st.recordCount.toString
      props += "acquiredRecordCount" -> st.acquiredCount.toString
      props += "deletedRecordCount" -> st.deletedCount.toString
      props += "sourceRecordCount" -> st.sourceCount.toString
      props += "processedValid" -> st.processedValid.toString
      props += "processedInvalid" -> st.processedInvalid.toString
      props += "processedIncrementalValid" -> st.processedIncrementalValid.toString
      props += "processedIncrementalInvalid" -> st.processedIncrementalInvalid.toString
      st.errorMessage.foreach(v => props += "datasetErrorMessage" -> v)
      st.errorTime.foreach(v => props += "datasetErrorTime" -> fmtInstant(v))
      st.currentOperation.foreach(v => props += "datasetCurrentOperation" -> v)
      st.operationStart.foreach(v => props += "datasetOperationStartTime" -> fmtInstant(v))
      st.operationTrigger.foreach(v => props += "datasetOperationTrigger" -> v)
      st.operationStatus.foreach(v => props += "datasetOperationStatus" -> v)
      st.acquisitionMethod.foreach(v => props += "acquisitionMethod" -> v)
      st.delimiterSet.foreach(v => props += "delimitersSet" -> fmtInstant(v))
      // Retry state
      st.retryMessage.foreach(v => props += "harvestRetryMessage" -> v)
      props += "harvestInRetry" -> st.inRetry.toString
      props += "harvestRetryCount" -> st.retryCount.toString
      st.lastRetryAt.foreach(v => props += "harvestLastRetryTime" -> fmtInstant(v))
      // State timestamp — only the current state gets a timestamp
      addStateTimestamp(props, st.state, st.stateChangedAt)
    }

    // dataset_harvest_config table
    repo.getHarvestConfig(spec).foreach { hc =>
      hc.harvestType.foreach(v => props += "harvestType" -> v)
      hc.harvestUrl.foreach(v => props += "harvestURL" -> v)
      hc.harvestDataset.foreach(v => props += "harvestDataset" -> v)
      hc.harvestPrefix.foreach(v => props += "harvestPrefix" -> v)
      hc.harvestRecord.foreach(v => props += "harvestRecord" -> v)
      hc.harvestSearch.foreach(v => props += "harvestSearch" -> v)
      hc.harvestDownloadUrl.foreach(v => props += "harvestDownloadURL" -> v)
      hc.harvestUsername.foreach(v => props += "harvestUsername" -> v)
      hc.harvestPassword.foreach(v => props += "harvestPassword" -> v)
      hc.harvestApiKey.foreach(v => props += "harvestApiKey" -> v)
      hc.harvestApiKeyParam.foreach(v => props += "harvestApiKeyParam" -> v)
      hc.recordRoot.foreach(v => props += "recordRoot" -> v)
      hc.uniqueId.foreach(v => props += "uniqueId" -> v)
      props += "harvestContinueOnError" -> hc.continueOnError.toString
      hc.errorThreshold.foreach(v => props += "harvestErrorThreshold" -> v.toString)
      hc.idFilterType.foreach(v => props += "idFilterType" -> v)
      hc.idFilterExpression.foreach(v => props += "idFilterExpression" -> v)
      // JSON harvest fields from harvest_json JSONB column
      hc.harvestJson.foreach { jsonStr =>
        try {
          val json = play.api.libs.json.Json.parse(jsonStr)
          (json \ "itemsPath").asOpt[String].foreach(v => props += "harvestJsonItemsPath" -> v)
          (json \ "idPath").asOpt[String].foreach(v => props += "harvestJsonIdPath" -> v)
          (json \ "totalPath").asOpt[String].foreach(v => props += "harvestJsonTotalPath" -> v)
          (json \ "pageParam").asOpt[String].foreach(v => props += "harvestJsonPageParam" -> v)
          (json \ "pageSizeParam").asOpt[String].foreach(v => props += "harvestJsonPageSizeParam" -> v)
          (json \ "pageSize").asOpt[Int].foreach(v => props += "harvestJsonPageSize" -> v.toString)
          (json \ "detailPath").asOpt[String].foreach(v => props += "harvestJsonDetailPath" -> v)
          (json \ "skipDetail").asOpt[Boolean].foreach(v => props += "harvestJsonSkipDetail" -> v.toString)
          (json \ "xmlRoot").asOpt[String].foreach(v => props += "harvestJsonXmlRoot" -> v)
          (json \ "xmlRecord").asOpt[String].foreach(v => props += "harvestJsonXmlRecord" -> v)
        } catch {
          case _: Exception => // malformed JSON — skip
        }
      }
    }

    // dataset_harvest_schedule table
    repo.getHarvestSchedule(spec).foreach { hs =>
      hs.delay.foreach(v => props += "harvestDelay" -> v)
      hs.delayUnit.foreach(v => props += "harvestDelayUnit" -> v)
      // Both NXProps map to the same `incremental` boolean column:
      // - harvestIncremental (string prop, used in DsInfo.getHarvestCron)
      // - harvestIncrementalMode (boolean prop, used in DatasetActor.processIncremental)
      props += "harvestIncremental" -> hs.incremental.toString
      props += "harvestIncrementalMode" -> hs.incremental.toString
      hs.previousTime.foreach(v => props += "harvestPreviousTime" -> fmtInstant(v))
      hs.lastFullHarvest.foreach(v => props += "lastFullHarvestTime" -> fmtInstant(v))
      hs.lastIncrementalHarvest.foreach(v => props += "lastIncrementalHarvestTime" -> fmtInstant(v))
    }

    // dataset_mapping_config table
    repo.getMappingConfig(spec).foreach { mc =>
      mc.mapToPrefix.foreach(v => props += "datasetMapToPrefix" -> v)
      mc.mappingSource.foreach(v => props += "datasetMappingSource" -> v)
      mc.defaultMappingPrefix.foreach(v => props += "datasetDefaultMappingPrefix" -> v)
      mc.defaultMappingName.foreach(v => props += "datasetDefaultMappingName" -> v)
      mc.defaultMappingVersion.foreach(v => props += "datasetDefaultMappingVersion" -> v)
      props += "publishOAIPMH" -> mc.publishOaipmh.toString
      props += "publishIndex" -> mc.publishIndex.toString
      props += "publishLOD" -> mc.publishLod.toString
      props += "categoriesInclude" -> mc.categoriesInclude.toString
      mc.processedExternally.foreach(v => props += "processedExternally" -> v)
    }

    // dataset_indexing table
    repo.getIndexing(spec).foreach { idx =>
      idx.recordsIndexed.foreach(v => props += "indexingRecordsIndexed" -> v.toString)
      idx.recordsExpected.foreach(v => props += "indexingRecordsExpected" -> v.toString)
      idx.orphansDeleted.foreach(v => props += "indexingOrphansDeleted" -> v.toString)
      idx.errorCount.foreach(v => props += "indexingErrorCount" -> v.toString)
      idx.lastStatus.foreach(v => props += "indexingLastStatus" -> v)
      idx.lastMessage.foreach(v => props += "indexingLastMessage" -> v)
      idx.lastTimestamp.foreach(v => props += "indexingLastTimestamp" -> fmtInstant(v))
      idx.lastRevision.foreach(v => props += "indexingLastRevision" -> v.toString)
    }

    props.result()
  }

  /** Map the current state name to the corresponding NXProp timestamp key. */
  private def addStateTimestamp(
      props: scala.collection.mutable.Builder[(String, String), Map[String, String]],
      state: String,
      changedAt: Instant
  ): Unit = {
    // state column stores values like "stateSaved", "stateRaw", etc.
    // These map directly to NXProp names
    if (validStates.contains(state)) {
      props += state -> fmtInstant(changedAt)
    }
  }

  private def fmtInstant(i: Instant): String = isoFormatter.format(i)

  private val validStates = Set(
    "stateRaw", "stateRawAnalyzed", "stateSourced", "stateSourceAnalyzed",
    "stateMappable", "stateProcessable", "stateProcessed", "stateAnalyzed",
    "stateSaved", "stateIncrementalSaved", "stateSynced", "stateDisabled"
  )

  /** NXProp names that are SKOS/vocab properties — these fall through to Fuseki. */
  val skosProperties: Set[String] = Set(
    "skosField", "skosFieldTag", "skosSpec", "skosName", "skosOwner",
    "skosUploadTime", "proxyLiteralValue", "proxyLiteralField",
    "belongsToCategory", "mappingConcept", "mappingVocabulary",
    "mappingDeleted", "mappingTime"
  )
}
