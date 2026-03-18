package services

import java.io.File
import java.time.Instant
import dataset.DsInfo
import dataset.SourceRepo
import discovery.OaiSourceConfig.OaiSource
import discovery.OaiSourceRepo
import organization.OrgContext
import play.api.Logging
import triplestore.GraphProperties._
import triplestore.TripleStore

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

/** Report produced by the Fuseki-to-PostgreSQL migration.
  *
  * All counters start at zero.  Errors accumulate as human-readable strings.
  */
case class MigrationReport(
    datasetsCreated: Int = 0,
    statesCreated: Int = 0,
    harvestConfigsCreated: Int = 0,
    schedulesCreated: Int = 0,
    mappingConfigsCreated: Int = 0,
    indexingCreated: Int = 0,
    oaiSourcesMigrated: Int = 0,
    sourceFactsMerged: Int = 0,
    errors: List[String] = Nil
) {

  def withError(msg: String): MigrationReport = copy(errors = errors :+ msg)

  def summary: String = {
    val lines = List(
      s"  datasets:        $datasetsCreated",
      s"  states:          $statesCreated",
      s"  harvest configs: $harvestConfigsCreated",
      s"  schedules:       $schedulesCreated",
      s"  mapping configs: $mappingConfigsCreated",
      s"  indexing:        $indexingCreated",
      s"  oai sources:     $oaiSourcesMigrated",
      s"  source facts:    $sourceFactsMerged",
      s"  errors:          ${errors.size}"
    )
    lines.mkString("\n")
  }
}

/** One-shot migration from Fuseki triple store and filesystem to PostgreSQL.
  *
  * Design constraints:
  *   - Idempotent: all writes use upserts so re-running is safe
  *   - Reports errors per-dataset rather than aborting
  *   - NOT unit-testable (requires live Fuseki); correctness is verified via the MigrationReport
  */
class FusekiMigration(
    orgContext: OrgContext,
    repo: DatasetRepository,
    dbService: DatabaseService
)(implicit ec: ExecutionContext, ts: TripleStore)
    extends Logging {

  private val orgId = orgContext.appConfig.orgId

  // -------------------------------------------------------------------------
  // Public entry point
  // -------------------------------------------------------------------------

  def run(): Future[MigrationReport] = {
    logger.info("Starting Fuseki-to-PostgreSQL migration...")
    for {
      datasetSpecs <- listDatasetSpecs()
      report <- migrateDatasets(datasetSpecs)
      finalReport = migrateOaiSources(report)
    } yield {
      logger.info(s"Migration complete:\n${finalReport.summary}")
      if (finalReport.errors.nonEmpty) {
        logger.warn(s"Migration errors:\n${finalReport.errors.mkString("\n  - ", "\n  - ", "")}")
      }
      finalReport
    }
  }

  // -------------------------------------------------------------------------
  // Step 1: List all dataset specs from Fuseki
  // -------------------------------------------------------------------------

  private def listDatasetSpecs(): Future[List[String]] = {
    ts.query(triplestore.Sparql.selectDatasetSpecsQ).map { results =>
      val specs = results.map(_("spec").text)
      logger.info(s"Found ${specs.size} datasets in Fuseki")
      specs
    }
  }

  // -------------------------------------------------------------------------
  // Step 2-5: Migrate each dataset, then OAI sources
  // -------------------------------------------------------------------------

  private def migrateDatasets(specs: List[String]): Future[MigrationReport] = {
    // Process sequentially to keep Fuseki load manageable
    specs.foldLeft(Future.successful(MigrationReport())) { (futReport, spec) =>
      futReport.flatMap { report =>
        migrateOneDataset(spec, report)
      }
    }
  }

  private def migrateOneDataset(spec: String, report: MigrationReport): Future[MigrationReport] = {
    logger.info(s"Migrating dataset: $spec")
    try {
      val dsInfo = DsInfo.getDsInfo(spec, orgContext)
      val r1 = migrateDatasetRecord(dsInfo, report)
      val r2 = migrateStateRecord(dsInfo, r1)
      val r3 = migrateHarvestConfig(dsInfo, r2)
      val r4 = migrateHarvestSchedule(dsInfo, r3)
      val r5 = migrateMappingConfig(dsInfo, r4)
      val r6 = migrateIndexing(dsInfo, r5)
      val r7 = migrateSourceFacts(dsInfo, r6)
      Future.successful(r7)
    } catch {
      case e: Exception =>
        val msg = s"[$spec] Migration failed: ${e.getMessage}"
        logger.error(msg, e)
        Future.successful(report.withError(msg))
    }
  }

  // -------------------------------------------------------------------------
  // Dataset core identity
  // -------------------------------------------------------------------------

  private def migrateDatasetRecord(dsInfo: DsInfo, report: MigrationReport): MigrationReport = {
    try {
      val tags = dsInfo.getLiteralProp(datasetTags).toList.flatMap(_.split(",").map(_.trim)).filter(_.nonEmpty)
      val ds = DatasetRecord(
        spec = dsInfo.spec,
        orgId = orgId,
        name = dsInfo.getLiteralProp(datasetName),
        description = dsInfo.getLiteralProp(datasetDescription),
        owner = dsInfo.getLiteralProp(datasetOwner),
        datasetType = dsInfo.getLiteralProp(datasetType),
        character = dsInfo.getLiteralProp(datasetCharacter),
        language = dsInfo.getLiteralProp(datasetLanguage),
        rights = dsInfo.getLiteralProp(datasetRights),
        tags = tags,
        aggregator = dsInfo.getLiteralProp(datasetAggregator),
        dataProviderUrl = dsInfo.getLiteralProp(datasetDataProviderURL),
        edmType = dsInfo.getLiteralProp(edmType),
        createdAt = Instant.now(),
        updatedAt = Instant.now()
      )
      // Upsert: try create, fall back to update
      repo.getDataset(dsInfo.spec) match {
        case Some(_) => repo.updateDataset(ds)
        case None    => repo.createDataset(ds)
      }
      report.copy(datasetsCreated = report.datasetsCreated + 1)
    } catch {
      case e: Exception =>
        report.withError(s"[${dsInfo.spec}] Dataset record: ${e.getMessage}")
    }
  }

  // -------------------------------------------------------------------------
  // Dataset state (FSM state, counters, error info)
  // -------------------------------------------------------------------------

  private def migrateStateRecord(dsInfo: DsInfo, report: MigrationReport): MigrationReport = {
    try {
      // Determine current state from the DsInfo state machine
      val currentState = try {
        dsInfo.getState().toString
      } catch {
        case _: Exception => "CREATED"
      }

      val stateChangedAt = mostRecentStateTimestamp(dsInfo).getOrElse(Instant.now())

      val state = DatasetStateRecord(
        spec = dsInfo.spec,
        state = currentState,
        stateChangedAt = stateChangedAt,
        errorMessage = dsInfo.getLiteralProp(datasetErrorMessage),
        errorTime = dsInfo.getLiteralProp(datasetErrorTime).flatMap(parseInstant),
        currentOperation = dsInfo.getLiteralProp(datasetCurrentOperation),
        operationStart = dsInfo.getLiteralProp(datasetOperationStartTime).flatMap(parseInstant),
        operationTrigger = dsInfo.getLiteralProp(datasetOperationTrigger),
        recordCount = dsInfo.getLiteralProp(datasetRecordCount).flatMap(safeInt).getOrElse(0),
        acquiredCount = dsInfo.getLiteralProp(acquiredRecordCount).flatMap(safeInt).getOrElse(0),
        deletedCount = dsInfo.getLiteralProp(deletedRecordCount).flatMap(safeInt).getOrElse(0),
        sourceCount = dsInfo.getLiteralProp(sourceRecordCount).flatMap(safeInt).getOrElse(0),
        processedValid = dsInfo.getLiteralProp(processedValid).flatMap(safeInt).getOrElse(0),
        processedInvalid = dsInfo.getLiteralProp(processedInvalid).flatMap(safeInt).getOrElse(0),
        processedIncrementalValid = dsInfo.getLiteralProp(processedIncrementalValid).flatMap(safeInt).getOrElse(0),
        processedIncrementalInvalid = dsInfo.getLiteralProp(processedIncrementalInvalid).flatMap(safeInt).getOrElse(0),
        acquisitionMethod = dsInfo.getLiteralProp(acquisitionMethod),
        delimiterSet = dsInfo.getLiteralProp(delimitersSet).flatMap(parseInstant)
      )
      repo.upsertState(state)
      report.copy(statesCreated = report.statesCreated + 1)
    } catch {
      case e: Exception =>
        report.withError(s"[${dsInfo.spec}] State record: ${e.getMessage}")
    }
  }

  // -------------------------------------------------------------------------
  // Harvest configuration
  // -------------------------------------------------------------------------

  private def migrateHarvestConfig(dsInfo: DsInfo, report: MigrationReport): MigrationReport = {
    try {
      // Only migrate if there's at least a harvest type or URL
      val hasHarvestInfo = dsInfo.getLiteralProp(harvestType).isDefined ||
        dsInfo.getLiteralProp(harvestURL).isDefined

      if (!hasHarvestInfo) return report

      val config = HarvestConfigRecord(
        spec = dsInfo.spec,
        harvestType = dsInfo.getLiteralProp(harvestType),
        harvestUrl = dsInfo.getLiteralProp(harvestURL),
        harvestDataset = dsInfo.getLiteralProp(harvestDataset),
        harvestPrefix = dsInfo.getLiteralProp(harvestPrefix),
        harvestRecord = dsInfo.getLiteralProp(harvestRecord),
        harvestSearch = dsInfo.getLiteralProp(harvestSearch),
        harvestDownloadUrl = dsInfo.getLiteralProp(harvestDownloadURL),
        harvestUsername = dsInfo.getLiteralProp(harvestUsername),
        harvestPassword = dsInfo.getLiteralProp(harvestPassword),
        harvestApiKey = dsInfo.getLiteralProp(harvestApiKey),
        harvestApiKeyParam = dsInfo.getLiteralProp(harvestApiKeyParam),
        recordRoot = dsInfo.getLiteralProp(recordRoot),
        uniqueId = dsInfo.getLiteralProp(uniqueId),
        continueOnError = dsInfo.getBooleanProp(harvestContinueOnError),
        errorThreshold = dsInfo.getLiteralProp(harvestErrorThreshold).flatMap(safeInt),
        idFilterType = dsInfo.getLiteralProp(idFilterType),
        idFilterExpression = dsInfo.getLiteralProp(idFilterExpression)
      )
      repo.upsertHarvestConfig(config)
      report.copy(harvestConfigsCreated = report.harvestConfigsCreated + 1)
    } catch {
      case e: Exception =>
        report.withError(s"[${dsInfo.spec}] Harvest config: ${e.getMessage}")
    }
  }

  // -------------------------------------------------------------------------
  // Harvest schedule
  // -------------------------------------------------------------------------

  private def migrateHarvestSchedule(dsInfo: DsInfo, report: MigrationReport): MigrationReport = {
    try {
      // Only migrate if scheduling info exists
      val hasSchedule = dsInfo.getLiteralProp(harvestDelay).isDefined ||
        dsInfo.getLiteralProp(harvestPreviousTime).isDefined

      if (!hasSchedule) return report

      val schedule = HarvestScheduleRecord(
        spec = dsInfo.spec,
        delay = dsInfo.getLiteralProp(harvestDelay),
        delayUnit = dsInfo.getLiteralProp(harvestDelayUnit),
        incremental = dsInfo.getBooleanProp(harvestIncrementalMode),
        previousTime = dsInfo.getLiteralProp(harvestPreviousTime).flatMap(parseInstant),
        lastFullHarvest = dsInfo.getLiteralProp(lastFullHarvestTime).flatMap(parseInstant),
        lastIncrementalHarvest = dsInfo.getLiteralProp(lastIncrementalHarvestTime).flatMap(parseInstant)
      )
      repo.upsertHarvestSchedule(schedule)
      report.copy(schedulesCreated = report.schedulesCreated + 1)
    } catch {
      case e: Exception =>
        report.withError(s"[${dsInfo.spec}] Harvest schedule: ${e.getMessage}")
    }
  }

  // -------------------------------------------------------------------------
  // Mapping and publishing configuration
  // -------------------------------------------------------------------------

  private def migrateMappingConfig(dsInfo: DsInfo, report: MigrationReport): MigrationReport = {
    try {
      val config = MappingConfigRecord(
        spec = dsInfo.spec,
        mapToPrefix = dsInfo.getLiteralProp(datasetMapToPrefix),
        mappingSource = Some(dsInfo.getMappingSource),
        defaultMappingPrefix = dsInfo.getDefaultMappingPrefix,
        defaultMappingName = dsInfo.getDefaultMappingName,
        defaultMappingVersion = dsInfo.getDefaultMappingVersion,
        publishOaipmh = dsInfo.getBooleanProp(publishOAIPMH),
        publishIndex = dsInfo.getBooleanProp(publishIndex),
        publishLod = dsInfo.getBooleanProp(publishLOD),
        categoriesInclude = dsInfo.getBooleanProp(categoriesInclude)
      )
      repo.upsertMappingConfig(config)
      report.copy(mappingConfigsCreated = report.mappingConfigsCreated + 1)
    } catch {
      case e: Exception =>
        report.withError(s"[${dsInfo.spec}] Mapping config: ${e.getMessage}")
    }
  }

  // -------------------------------------------------------------------------
  // Indexing results (from Hub3 webhook)
  // -------------------------------------------------------------------------

  private def migrateIndexing(dsInfo: DsInfo, report: MigrationReport): MigrationReport = {
    try {
      // Only migrate if we have indexing data
      val hasIndexing = dsInfo.getLiteralProp(indexingLastStatus).isDefined ||
        dsInfo.getLiteralProp(indexingRecordsIndexed).isDefined

      if (!hasIndexing) return report

      val indexing = IndexingRecord(
        spec = dsInfo.spec,
        recordsIndexed = dsInfo.getLiteralProp(indexingRecordsIndexed).flatMap(safeInt),
        recordsExpected = dsInfo.getLiteralProp(indexingRecordsExpected).flatMap(safeInt),
        orphansDeleted = dsInfo.getLiteralProp(indexingOrphansDeleted).flatMap(safeInt),
        errorCount = dsInfo.getLiteralProp(indexingErrorCount).flatMap(safeInt),
        lastStatus = dsInfo.getLiteralProp(indexingLastStatus),
        lastMessage = dsInfo.getLiteralProp(indexingLastMessage),
        lastTimestamp = dsInfo.getLiteralProp(indexingLastTimestamp).flatMap(parseInstant),
        lastRevision = dsInfo.getLiteralProp(indexingLastRevision).flatMap(safeInt)
      )
      repo.upsertIndexing(indexing)
      report.copy(indexingCreated = report.indexingCreated + 1)
    } catch {
      case e: Exception =>
        report.withError(s"[${dsInfo.spec}] Indexing: ${e.getMessage}")
    }
  }

  // -------------------------------------------------------------------------
  // Source facts (merge into harvest config)
  // -------------------------------------------------------------------------

  private def migrateSourceFacts(dsInfo: DsInfo, report: MigrationReport): MigrationReport = {
    try {
      val sourceDir = new File(orgContext.datasetsDir, s"${dsInfo.spec}/source")
      val factsFile = SourceRepo.sourceFactsFile(sourceDir)
      if (!factsFile.exists()) return report

      val source = scala.io.Source.fromFile(factsFile, "UTF-8")
      val facts = try {
        SourceRepo.readSourceFacts(source)
      } finally {
        source.close()
      }

      // Merge source_facts into the harvest config (create if doesn't exist)
      val existing = repo.getHarvestConfig(dsInfo.spec).getOrElse(
        HarvestConfigRecord(spec = dsInfo.spec)
      )
      val merged = existing.copy(
        sourceType = Some(facts.sourceType),
        recordRoot = existing.recordRoot.orElse(Some(facts.recordRoot)),
        uniqueId = existing.uniqueId.orElse(Some(facts.uniqueId)),
        recordContainer = existing.recordContainer.orElse(facts.recordContainer)
      )
      repo.upsertHarvestConfig(merged)
      report.copy(sourceFactsMerged = report.sourceFactsMerged + 1)
    } catch {
      case e: Exception =>
        report.withError(s"[${dsInfo.spec}] Source facts: ${e.getMessage}")
    }
  }

  // -------------------------------------------------------------------------
  // OAI sources (sources.json → direct JDBC)
  // -------------------------------------------------------------------------

  private def migrateOaiSources(report: MigrationReport): MigrationReport = {
    try {
      val oaiSourceRepo = new OaiSourceRepo(orgContext.orgRoot)
      val sources = oaiSourceRepo.listSources()
      if (sources.isEmpty) return report

      logger.info(s"Migrating ${sources.size} OAI sources...")
      var count = 0
      sources.foreach { src =>
        try {
          upsertOaiSource(src)
          count += 1
        } catch {
          case e: Exception =>
            logger.error(s"Failed to migrate OAI source ${src.id}: ${e.getMessage}", e)
        }
      }
      report.copy(oaiSourcesMigrated = count)
    } catch {
      case e: Exception =>
        report.withError(s"OAI sources: ${e.getMessage}")
    }
  }

  /** Insert/update an OAI source using direct JDBC (one-shot migration, no dedicated repo yet). */
  private def upsertOaiSource(src: OaiSource): Unit = {
    val conn = dbService.getConnection()
    try {
      val sql =
        """INSERT INTO oai_sources (
          |  id, org_id, name, url, default_metadata_prefix, default_aggregator,
          |  default_prefix, default_edm_type, harvest_delay, harvest_delay_unit,
          |  harvest_incremental, mapping_rules, ignored_sets, enabled,
          |  last_checked, created_at
          |) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)
          |ON CONFLICT (id) DO UPDATE SET
          |  name = EXCLUDED.name,
          |  url = EXCLUDED.url,
          |  default_metadata_prefix = EXCLUDED.default_metadata_prefix,
          |  default_aggregator = EXCLUDED.default_aggregator,
          |  default_prefix = EXCLUDED.default_prefix,
          |  default_edm_type = EXCLUDED.default_edm_type,
          |  harvest_delay = EXCLUDED.harvest_delay,
          |  harvest_delay_unit = EXCLUDED.harvest_delay_unit,
          |  harvest_incremental = EXCLUDED.harvest_incremental,
          |  mapping_rules = EXCLUDED.mapping_rules,
          |  ignored_sets = EXCLUDED.ignored_sets,
          |  enabled = EXCLUDED.enabled,
          |  last_checked = EXCLUDED.last_checked""".stripMargin

      val ps = conn.prepareStatement(sql)
      try {
        import play.api.libs.json.Json

        ps.setString(1, src.id)
        ps.setString(2, orgId)
        ps.setString(3, src.name)
        ps.setString(4, src.url)
        ps.setString(5, src.defaultMetadataPrefix)
        ps.setString(6, src.defaultAggregator)
        ps.setString(7, src.defaultPrefix)
        setOptStringJdbc(ps, 8, src.defaultEdmType)
        setOptIntJdbc(ps, 9, src.harvestDelay)
        setOptStringJdbc(ps, 10, src.harvestDelayUnit)
        ps.setBoolean(11, src.harvestIncremental)
        ps.setString(12, Json.toJson(src.mappingRules).toString())
        ps.setArray(13, conn.createArrayOf("text", src.ignoredSets.toArray))
        ps.setBoolean(14, src.enabled)
        src.lastChecked match {
          case Some(dt) =>
            ps.setTimestamp(15, new java.sql.Timestamp(dt.getMillis))
          case None =>
            ps.setNull(15, java.sql.Types.TIMESTAMP)
        }
        ps.setTimestamp(16, new java.sql.Timestamp(src.createdAt.getMillis))
        ps.executeUpdate()
      } finally ps.close()
    } finally conn.close()
  }

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

  /** Parse a Joda-style ISO timestamp string to java.time.Instant. */
  private def parseInstant(s: String): Option[Instant] =
    Try {
      org.joda.time.format.ISODateTimeFormat
        .dateOptionalTimeParser()
        .parseDateTime(s)
        .toDate
        .toInstant
    } match {
      case Success(instant) => Some(instant)
      case Failure(_)       => None
    }

  /** Safely parse an integer string. */
  private def safeInt(s: String): Option[Int] =
    Try(s.toInt).toOption

  /** Find the most recent state timestamp across all DsState values. */
  private def mostRecentStateTimestamp(dsInfo: DsInfo): Option[Instant] = {
    import dataset.DsInfo.DsState
    val timestamps = DsState.values.toList.flatMap { state =>
      dsInfo
        .getLiteralProp(NXProp(state.toString, triplestore.GraphProperties.timeProp))
        .flatMap(s => parseInstant(s))
    }
    if (timestamps.isEmpty) None else Some(timestamps.maxBy(_.toEpochMilli))
  }

  private def setOptStringJdbc(ps: java.sql.PreparedStatement, idx: Int, v: Option[String]): Unit =
    v match {
      case Some(s) => ps.setString(idx, s)
      case None    => ps.setNull(idx, java.sql.Types.VARCHAR)
    }

  private def setOptIntJdbc(ps: java.sql.PreparedStatement, idx: Int, v: Option[Int]): Unit =
    v match {
      case Some(i) => ps.setInt(idx, i)
      case None    => ps.setNull(idx, java.sql.Types.INTEGER)
    }
}
