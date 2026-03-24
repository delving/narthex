package services

import java.sql.{Connection, PreparedStatement, ResultSet, Statement, Timestamp, Types}
import java.time.Instant
import play.api.Logging

/** JDBC-backed [[DatasetRepository]] for PostgreSQL.
  *
  * Uses plain JDBC with the HikariCP pool managed by [[DatabaseService]].
  * All public methods borrow a connection, execute SQL, and return it to the pool.
  */
class PostgresDatasetRepository(db: DatabaseService) extends DatasetRepository with Logging {

  // ---------------------------------------------------------------------------
  // Connection helper
  // ---------------------------------------------------------------------------

  private def withConnection[T](f: Connection => T): T = {
    val conn = db.getConnection()
    try f(conn)
    finally conn.close()
  }

  /** Execute a block within a single transaction. Commits on success, rolls back on failure. */
  def withTransaction[T](f: Connection => T): T = {
    val conn = db.getConnection()
    val autoCommit = conn.getAutoCommit
    conn.setAutoCommit(false)
    try {
      val result = f(conn)
      conn.commit()
      result
    } catch {
      case e: Exception =>
        conn.rollback()
        throw e
    } finally {
      conn.setAutoCommit(autoCommit)
      conn.close()
    }
  }

  // ---------------------------------------------------------------------------
  // Null-safe parameter helpers
  // ---------------------------------------------------------------------------

  private def setOptString(ps: PreparedStatement, idx: Int, v: Option[String]): Unit =
    v match {
      case Some(s) => ps.setString(idx, s)
      case None    => ps.setNull(idx, Types.VARCHAR)
    }

  private def setOptInt(ps: PreparedStatement, idx: Int, v: Option[Int]): Unit =
    v match {
      case Some(i) => ps.setInt(idx, i)
      case None    => ps.setNull(idx, Types.INTEGER)
    }

  private def setOptTimestamp(ps: PreparedStatement, idx: Int, v: Option[Instant]): Unit =
    v match {
      case Some(i) => ps.setTimestamp(idx, Timestamp.from(i))
      case None    => ps.setNull(idx, Types.TIMESTAMP)
    }

  private def getOptString(rs: ResultSet, col: String): Option[String] =
    Option(rs.getString(col))

  private def getOptInt(rs: ResultSet, col: String): Option[Int] = {
    val v = rs.getInt(col)
    if (rs.wasNull()) None else Some(v)
  }

  private def getOptInstant(rs: ResultSet, col: String): Option[Instant] =
    Option(rs.getTimestamp(col)).map(_.toInstant)

  // ---------------------------------------------------------------------------
  // Datasets
  // ---------------------------------------------------------------------------

  override def createDataset(ds: DatasetRecord): Unit = withConnection { conn =>
    val sql =
      """INSERT INTO datasets (
        |  spec, org_id, name, description, owner, dataset_type, character,
        |  language, rights, tags, aggregator, data_provider_url, edm_type,
        |  created_at, updated_at
        |) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""".stripMargin
    val ps = conn.prepareStatement(sql)
    try {
      ps.setString(1, ds.spec)
      ps.setString(2, ds.orgId)
      setOptString(ps, 3, ds.name)
      setOptString(ps, 4, ds.description)
      setOptString(ps, 5, ds.owner)
      setOptString(ps, 6, ds.datasetType)
      setOptString(ps, 7, ds.character)
      setOptString(ps, 8, ds.language)
      setOptString(ps, 9, ds.rights)
      ps.setArray(10, conn.createArrayOf("text", ds.tags.toArray))
      setOptString(ps, 11, ds.aggregator)
      setOptString(ps, 12, ds.dataProviderUrl)
      setOptString(ps, 13, ds.edmType)
      ps.setTimestamp(14, Timestamp.from(ds.createdAt))
      ps.setTimestamp(15, Timestamp.from(ds.updatedAt))
      ps.executeUpdate()
    } finally ps.close()
  }

  override def getDataset(spec: String): Option[DatasetRecord] = withConnection { conn =>
    val ps = conn.prepareStatement("SELECT * FROM datasets WHERE spec = ?")
    try {
      ps.setString(1, spec)
      val rs = ps.executeQuery()
      try {
        if (rs.next()) Some(readDataset(rs)) else None
      } finally rs.close()
    } finally ps.close()
  }

  override def listDatasets(orgId: String): List[DatasetRecord] = withConnection { conn =>
    val ps = conn.prepareStatement(
      "SELECT * FROM datasets WHERE org_id = ? ORDER BY spec"
    )
    try {
      ps.setString(1, orgId)
      val rs = ps.executeQuery()
      try {
        readList(rs)(readDataset)
      } finally rs.close()
    } finally ps.close()
  }

  override def listActiveDatasets(orgId: String): List[DatasetRecord] = withConnection { conn =>
    val ps = conn.prepareStatement(
      "SELECT * FROM datasets WHERE org_id = ? AND deleted_at IS NULL ORDER BY spec"
    )
    try {
      ps.setString(1, orgId)
      val rs = ps.executeQuery()
      try {
        readList(rs)(readDataset)
      } finally rs.close()
    } finally ps.close()
  }

  override def updateDataset(ds: DatasetRecord): Unit = withConnection { conn =>
    val sql =
      """UPDATE datasets SET
        |  org_id = ?, name = ?, description = ?, owner = ?, dataset_type = ?,
        |  character = ?, language = ?, rights = ?, tags = ?, aggregator = ?,
        |  data_provider_url = ?, edm_type = ?, updated_at = ?
        |WHERE spec = ?""".stripMargin
    val ps = conn.prepareStatement(sql)
    try {
      ps.setString(1, ds.orgId)
      setOptString(ps, 2, ds.name)
      setOptString(ps, 3, ds.description)
      setOptString(ps, 4, ds.owner)
      setOptString(ps, 5, ds.datasetType)
      setOptString(ps, 6, ds.character)
      setOptString(ps, 7, ds.language)
      setOptString(ps, 8, ds.rights)
      ps.setArray(9, conn.createArrayOf("text", ds.tags.toArray))
      setOptString(ps, 10, ds.aggregator)
      setOptString(ps, 11, ds.dataProviderUrl)
      setOptString(ps, 12, ds.edmType)
      ps.setTimestamp(13, Timestamp.from(ds.updatedAt))
      ps.setString(14, ds.spec)
      ps.executeUpdate()
    } finally ps.close()
  }

  override def softDeleteDataset(spec: String): Unit = withConnection { conn =>
    val ps = conn.prepareStatement("UPDATE datasets SET deleted_at = now() WHERE spec = ?")
    try {
      ps.setString(1, spec)
      ps.executeUpdate()
    } finally ps.close()
  }

  private def readDataset(rs: ResultSet): DatasetRecord = {
    val tagsArray = Option(rs.getArray("tags"))
      .map(_.getArray.asInstanceOf[Array[String]].toList)
      .getOrElse(Nil)
    DatasetRecord(
      spec = rs.getString("spec"),
      orgId = rs.getString("org_id"),
      name = getOptString(rs, "name"),
      description = getOptString(rs, "description"),
      owner = getOptString(rs, "owner"),
      datasetType = getOptString(rs, "dataset_type"),
      character = getOptString(rs, "character"),
      language = getOptString(rs, "language"),
      rights = getOptString(rs, "rights"),
      tags = tagsArray,
      aggregator = getOptString(rs, "aggregator"),
      dataProviderUrl = getOptString(rs, "data_provider_url"),
      edmType = getOptString(rs, "edm_type"),
      createdAt = rs.getTimestamp("created_at").toInstant,
      updatedAt = rs.getTimestamp("updated_at").toInstant,
      deletedAt = getOptInstant(rs, "deleted_at")
    )
  }

  // ---------------------------------------------------------------------------
  // Dataset State
  // ---------------------------------------------------------------------------

  override def upsertState(state: DatasetStateRecord): Unit = withConnection { conn =>
    val sql =
      """INSERT INTO dataset_state (
        |  spec, state, state_changed_at, error_message, error_time,
        |  current_operation, operation_start, operation_trigger,
        |  record_count, acquired_count, deleted_count, source_count,
        |  processed_valid, processed_invalid,
        |  processed_incremental_valid, processed_incremental_invalid,
        |  acquisition_method, delimiters_set, updated_at
        |) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
        |ON CONFLICT (spec) DO UPDATE SET
        |  state = EXCLUDED.state,
        |  state_changed_at = EXCLUDED.state_changed_at,
        |  error_message = EXCLUDED.error_message,
        |  error_time = EXCLUDED.error_time,
        |  current_operation = EXCLUDED.current_operation,
        |  operation_start = EXCLUDED.operation_start,
        |  operation_trigger = EXCLUDED.operation_trigger,
        |  record_count = EXCLUDED.record_count,
        |  acquired_count = EXCLUDED.acquired_count,
        |  deleted_count = EXCLUDED.deleted_count,
        |  source_count = EXCLUDED.source_count,
        |  processed_valid = EXCLUDED.processed_valid,
        |  processed_invalid = EXCLUDED.processed_invalid,
        |  processed_incremental_valid = EXCLUDED.processed_incremental_valid,
        |  processed_incremental_invalid = EXCLUDED.processed_incremental_invalid,
        |  acquisition_method = EXCLUDED.acquisition_method,
        |  delimiters_set = EXCLUDED.delimiters_set,
        |  updated_at = now()""".stripMargin
    val ps = conn.prepareStatement(sql)
    try {
      ps.setString(1, state.spec)
      ps.setString(2, state.state)
      ps.setTimestamp(3, Timestamp.from(state.stateChangedAt))
      setOptString(ps, 4, state.errorMessage)
      setOptTimestamp(ps, 5, state.errorTime)
      setOptString(ps, 6, state.currentOperation)
      setOptTimestamp(ps, 7, state.operationStart)
      setOptString(ps, 8, state.operationTrigger)
      ps.setInt(9, state.recordCount)
      ps.setInt(10, state.acquiredCount)
      ps.setInt(11, state.deletedCount)
      ps.setInt(12, state.sourceCount)
      ps.setInt(13, state.processedValid)
      ps.setInt(14, state.processedInvalid)
      ps.setInt(15, state.processedIncrementalValid)
      ps.setInt(16, state.processedIncrementalInvalid)
      setOptString(ps, 17, state.acquisitionMethod)
      setOptTimestamp(ps, 18, state.delimiterSet)
      ps.executeUpdate()
    } finally ps.close()
  }

  override def getState(spec: String): Option[DatasetStateRecord] = withConnection { conn =>
    val ps = conn.prepareStatement("SELECT * FROM dataset_state WHERE spec = ?")
    try {
      ps.setString(1, spec)
      val rs = ps.executeQuery()
      try {
        if (rs.next()) Some(readState(rs)) else None
      } finally rs.close()
    } finally ps.close()
  }

  private def readState(rs: ResultSet): DatasetStateRecord =
    DatasetStateRecord(
      spec = rs.getString("spec"),
      state = rs.getString("state"),
      stateChangedAt = rs.getTimestamp("state_changed_at").toInstant,
      errorMessage = getOptString(rs, "error_message"),
      errorTime = getOptInstant(rs, "error_time"),
      currentOperation = getOptString(rs, "current_operation"),
      operationStart = getOptInstant(rs, "operation_start"),
      operationTrigger = getOptString(rs, "operation_trigger"),
      recordCount = rs.getInt("record_count"),
      acquiredCount = rs.getInt("acquired_count"),
      deletedCount = rs.getInt("deleted_count"),
      sourceCount = rs.getInt("source_count"),
      processedValid = rs.getInt("processed_valid"),
      processedInvalid = rs.getInt("processed_invalid"),
      processedIncrementalValid = rs.getInt("processed_incremental_valid"),
      processedIncrementalInvalid = rs.getInt("processed_incremental_invalid"),
      acquisitionMethod = getOptString(rs, "acquisition_method"),
      delimiterSet = getOptInstant(rs, "delimiters_set")
    )

  override def listDatasetsWithActiveOperation(orgId: String): List[ActiveOperationRecord] =
    withConnection { conn =>
      val sql =
        """SELECT ds.spec, ds.current_operation, ds.operation_start, ds.operation_trigger
          |FROM dataset_state ds
          |JOIN datasets d ON ds.spec = d.spec
          |WHERE d.org_id = ?
          |  AND d.deleted_at IS NULL
          |  AND ds.current_operation IS NOT NULL
          |ORDER BY ds.operation_start ASC NULLS LAST""".stripMargin
      val ps = conn.prepareStatement(sql)
      try {
        ps.setString(1, orgId)
        val rs = ps.executeQuery()
        try {
          val buf = List.newBuilder[ActiveOperationRecord]
          while (rs.next()) {
            buf += ActiveOperationRecord(
              spec = rs.getString("spec"),
              currentOperation = rs.getString("current_operation"),
              operationStart = getOptInstant(rs, "operation_start"),
              operationTrigger = getOptString(rs, "operation_trigger")
            )
          }
          buf.result()
        }
        finally rs.close()
      } finally ps.close()
    }

  override def getCurrentWorkflowInfo(spec: String): Option[CurrentWorkflowInfo] = withConnection { conn =>
    val sql =
      """SELECT
        |  w.id as workflow_id,
        |  w.trigger,
        |  w.status,
        |  w.started_at,
        |  s.step_name,
        |  s.status as step_status,
        |  s.records_processed,
        |  s.error_message as step_error
        |FROM workflows w
        |LEFT JOIN LATERAL (
        |  SELECT step_name, status, records_processed, error_message
        |  FROM workflow_steps
        |  WHERE workflow_id = w.id
        |  ORDER BY id DESC
        |  LIMIT 1
        |) s ON true
        |WHERE w.spec = ? AND w.status = 'running'
        |ORDER BY w.started_at DESC
        |LIMIT 1""".stripMargin
    val ps = conn.prepareStatement(sql)
    try {
      ps.setString(1, spec)
      val rs = ps.executeQuery()
      try {
        if (rs.next()) {
          Some(CurrentWorkflowInfo(
            workflowId = rs.getString("workflow_id"),
            trigger = rs.getString("trigger"),
            status = rs.getString("status"),
            stepName = getOptString(rs, "step_name"),
            stepStatus = getOptString(rs, "step_status"),
            stepRecordsProcessed = getOptInt(rs, "records_processed"),
            stepError = getOptString(rs, "step_error"),
            startedAt = rs.getTimestamp("started_at").toInstant
          ))
        } else {
          None
        }
      } finally rs.close()
    } finally ps.close()
  }

  // ---------------------------------------------------------------------------
  // Harvest Config
  // ---------------------------------------------------------------------------
  override def upsertHarvestConfig(config: HarvestConfigRecord): Unit = withConnection { conn =>
    val sql =
      """INSERT INTO dataset_harvest_config (
        |  spec, harvest_type, harvest_url, harvest_dataset, harvest_prefix,
        |  harvest_record, harvest_search, harvest_download_url,
        |  harvest_username, harvest_password, harvest_api_key, harvest_api_key_param,
        |  source_type, record_root, unique_id, record_container, oai_source_id,
        |  continue_on_error, error_threshold, id_filter_type, id_filter_expression,
        |  updated_at
        |) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
        |ON CONFLICT (spec) DO UPDATE SET
        |  harvest_type = EXCLUDED.harvest_type,
        |  harvest_url = EXCLUDED.harvest_url,
        |  harvest_dataset = EXCLUDED.harvest_dataset,
        |  harvest_prefix = EXCLUDED.harvest_prefix,
        |  harvest_record = EXCLUDED.harvest_record,
        |  harvest_search = EXCLUDED.harvest_search,
        |  harvest_download_url = EXCLUDED.harvest_download_url,
        |  harvest_username = EXCLUDED.harvest_username,
        |  harvest_password = EXCLUDED.harvest_password,
        |  harvest_api_key = EXCLUDED.harvest_api_key,
        |  harvest_api_key_param = EXCLUDED.harvest_api_key_param,
        |  source_type = EXCLUDED.source_type,
        |  record_root = EXCLUDED.record_root,
        |  unique_id = EXCLUDED.unique_id,
        |  record_container = EXCLUDED.record_container,
        |  oai_source_id = EXCLUDED.oai_source_id,
        |  continue_on_error = EXCLUDED.continue_on_error,
        |  error_threshold = EXCLUDED.error_threshold,
        |  id_filter_type = EXCLUDED.id_filter_type,
        |  id_filter_expression = EXCLUDED.id_filter_expression,
        |  updated_at = now()""".stripMargin
    val ps = conn.prepareStatement(sql)
    try {
      ps.setString(1, config.spec)
      setOptString(ps, 2, config.harvestType)
      setOptString(ps, 3, config.harvestUrl)
      setOptString(ps, 4, config.harvestDataset)
      setOptString(ps, 5, config.harvestPrefix)
      setOptString(ps, 6, config.harvestRecord)
      setOptString(ps, 7, config.harvestSearch)
      setOptString(ps, 8, config.harvestDownloadUrl)
      setOptString(ps, 9, config.harvestUsername)
      setOptString(ps, 10, config.harvestPassword)
      setOptString(ps, 11, config.harvestApiKey)
      setOptString(ps, 12, config.harvestApiKeyParam)
      setOptString(ps, 13, config.sourceType)
      setOptString(ps, 14, config.recordRoot)
      setOptString(ps, 15, config.uniqueId)
      setOptString(ps, 16, config.recordContainer)
      setOptString(ps, 17, config.oaiSourceId)
      ps.setBoolean(18, config.continueOnError)
      setOptInt(ps, 19, config.errorThreshold)
      setOptString(ps, 20, config.idFilterType)
      setOptString(ps, 21, config.idFilterExpression)
      ps.executeUpdate()
    } finally ps.close()
  }

  override def getHarvestConfig(spec: String): Option[HarvestConfigRecord] = withConnection { conn =>
    val ps = conn.prepareStatement("SELECT * FROM dataset_harvest_config WHERE spec = ?")
    try {
      ps.setString(1, spec)
      val rs = ps.executeQuery()
      try {
        if (rs.next()) Some(readHarvestConfig(rs)) else None
      } finally rs.close()
    } finally ps.close()
  }

  private def readHarvestConfig(rs: ResultSet): HarvestConfigRecord =
    HarvestConfigRecord(
      spec = rs.getString("spec"),
      harvestType = getOptString(rs, "harvest_type"),
      harvestUrl = getOptString(rs, "harvest_url"),
      harvestDataset = getOptString(rs, "harvest_dataset"),
      harvestPrefix = getOptString(rs, "harvest_prefix"),
      harvestRecord = getOptString(rs, "harvest_record"),
      harvestSearch = getOptString(rs, "harvest_search"),
      harvestDownloadUrl = getOptString(rs, "harvest_download_url"),
      harvestUsername = getOptString(rs, "harvest_username"),
      harvestPassword = getOptString(rs, "harvest_password"),
      harvestApiKey = getOptString(rs, "harvest_api_key"),
      harvestApiKeyParam = getOptString(rs, "harvest_api_key_param"),
      sourceType = getOptString(rs, "source_type"),
      recordRoot = getOptString(rs, "record_root"),
      uniqueId = getOptString(rs, "unique_id"),
      recordContainer = getOptString(rs, "record_container"),
      oaiSourceId = getOptString(rs, "oai_source_id"),
      continueOnError = rs.getBoolean("continue_on_error"),
      errorThreshold = getOptInt(rs, "error_threshold"),
      idFilterType = getOptString(rs, "id_filter_type"),
      idFilterExpression = getOptString(rs, "id_filter_expression")
    )

  override def listAllHarvestDatasets(orgId: String): List[String] = withConnection { conn =>
    val sql =
      """SELECT DISTINCT hc.harvest_dataset
        |FROM dataset_harvest_config hc
        |JOIN datasets ds ON hc.spec = ds.spec
        |WHERE ds.org_id = ? AND hc.harvest_dataset IS NOT NULL AND hc.harvest_dataset <> ''
      """.stripMargin
    val ps = conn.prepareStatement(sql)
    try {
      ps.setString(1, orgId)
      val rs = ps.executeQuery()
      try readList(rs)(rs => rs.getString("harvest_dataset"))
      finally rs.close()
    } finally ps.close()
  }

  // ---------------------------------------------------------------------------
  // Harvest Schedule
  // ---------------------------------------------------------------------------

  override def upsertHarvestSchedule(schedule: HarvestScheduleRecord): Unit = withConnection { conn =>
    val sql =
      """INSERT INTO dataset_harvest_schedule (
        |  spec, delay, delay_unit, incremental, previous_time,
        |  last_full_harvest, last_incremental_harvest, updated_at
        |) VALUES (?, ?, ?, ?, ?, ?, ?, now())
        |ON CONFLICT (spec) DO UPDATE SET
        |  delay = EXCLUDED.delay,
        |  delay_unit = EXCLUDED.delay_unit,
        |  incremental = EXCLUDED.incremental,
        |  previous_time = EXCLUDED.previous_time,
        |  last_full_harvest = EXCLUDED.last_full_harvest,
        |  last_incremental_harvest = EXCLUDED.last_incremental_harvest,
        |  updated_at = now()""".stripMargin
    val ps = conn.prepareStatement(sql)
    try {
      ps.setString(1, schedule.spec)
      setOptString(ps, 2, schedule.delay)
      setOptString(ps, 3, schedule.delayUnit)
      ps.setBoolean(4, schedule.incremental)
      setOptTimestamp(ps, 5, schedule.previousTime)
      setOptTimestamp(ps, 6, schedule.lastFullHarvest)
      setOptTimestamp(ps, 7, schedule.lastIncrementalHarvest)
      ps.executeUpdate()
    } finally ps.close()
  }

  override def getHarvestSchedule(spec: String): Option[HarvestScheduleRecord] = withConnection { conn =>
    val ps = conn.prepareStatement("SELECT * FROM dataset_harvest_schedule WHERE spec = ?")
    try {
      ps.setString(1, spec)
      val rs = ps.executeQuery()
      try {
        if (rs.next()) Some(readHarvestSchedule(rs)) else None
      } finally rs.close()
    } finally ps.close()
  }

  private def readHarvestSchedule(rs: ResultSet): HarvestScheduleRecord =
    HarvestScheduleRecord(
      spec = rs.getString("spec"),
      delay = getOptString(rs, "delay"),
      delayUnit = getOptString(rs, "delay_unit"),
      incremental = rs.getBoolean("incremental"),
      previousTime = getOptInstant(rs, "previous_time"),
      lastFullHarvest = getOptInstant(rs, "last_full_harvest"),
      lastIncrementalHarvest = getOptInstant(rs, "last_incremental_harvest")
    )

  // ---------------------------------------------------------------------------
  // Mapping Config
  // ---------------------------------------------------------------------------

  override def upsertMappingConfig(config: MappingConfigRecord): Unit = withConnection { conn =>
    val sql =
      """INSERT INTO dataset_mapping_config (
        |  spec, map_to_prefix, mapping_source, default_mapping_prefix,
        |  default_mapping_name, default_mapping_version,
        |  publish_oaipmh, publish_index, publish_lod, categories_include, updated_at
        |) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
        |ON CONFLICT (spec) DO UPDATE SET
        |  map_to_prefix = EXCLUDED.map_to_prefix,
        |  mapping_source = EXCLUDED.mapping_source,
        |  default_mapping_prefix = EXCLUDED.default_mapping_prefix,
        |  default_mapping_name = EXCLUDED.default_mapping_name,
        |  default_mapping_version = EXCLUDED.default_mapping_version,
        |  publish_oaipmh = EXCLUDED.publish_oaipmh,
        |  publish_index = EXCLUDED.publish_index,
        |  publish_lod = EXCLUDED.publish_lod,
        |  categories_include = EXCLUDED.categories_include,
        |  updated_at = now()""".stripMargin
    val ps = conn.prepareStatement(sql)
    try {
      ps.setString(1, config.spec)
      setOptString(ps, 2, config.mapToPrefix)
      setOptString(ps, 3, config.mappingSource)
      setOptString(ps, 4, config.defaultMappingPrefix)
      setOptString(ps, 5, config.defaultMappingName)
      setOptString(ps, 6, config.defaultMappingVersion)
      ps.setBoolean(7, config.publishOaipmh)
      ps.setBoolean(8, config.publishIndex)
      ps.setBoolean(9, config.publishLod)
      ps.setBoolean(10, config.categoriesInclude)
      ps.executeUpdate()
    } finally ps.close()
  }

  override def getMappingConfig(spec: String): Option[MappingConfigRecord] = withConnection { conn =>
    val ps = conn.prepareStatement("SELECT * FROM dataset_mapping_config WHERE spec = ?")
    try {
      ps.setString(1, spec)
      val rs = ps.executeQuery()
      try {
        if (rs.next()) Some(readMappingConfig(rs)) else None
      } finally rs.close()
    } finally ps.close()
  }

  private def readMappingConfig(rs: ResultSet): MappingConfigRecord =
    MappingConfigRecord(
      spec = rs.getString("spec"),
      mapToPrefix = getOptString(rs, "map_to_prefix"),
      mappingSource = getOptString(rs, "mapping_source"),
      defaultMappingPrefix = getOptString(rs, "default_mapping_prefix"),
      defaultMappingName = getOptString(rs, "default_mapping_name"),
      defaultMappingVersion = getOptString(rs, "default_mapping_version"),
      publishOaipmh = rs.getBoolean("publish_oaipmh"),
      publishIndex = rs.getBoolean("publish_index"),
      publishLod = rs.getBoolean("publish_lod"),
      categoriesInclude = rs.getBoolean("categories_include")
    )

  // ---------------------------------------------------------------------------
  // Indexing
  // ---------------------------------------------------------------------------

  override def upsertIndexing(indexing: IndexingRecord): Unit = withConnection { conn =>
    val sql =
      """INSERT INTO dataset_indexing (
        |  spec, records_indexed, records_expected, orphans_deleted, error_count,
        |  last_status, last_message, last_timestamp, last_revision, updated_at
        |) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, now())
        |ON CONFLICT (spec) DO UPDATE SET
        |  records_indexed = EXCLUDED.records_indexed,
        |  records_expected = EXCLUDED.records_expected,
        |  orphans_deleted = EXCLUDED.orphans_deleted,
        |  error_count = EXCLUDED.error_count,
        |  last_status = EXCLUDED.last_status,
        |  last_message = EXCLUDED.last_message,
        |  last_timestamp = EXCLUDED.last_timestamp,
        |  last_revision = EXCLUDED.last_revision,
        |  updated_at = now()""".stripMargin
    val ps = conn.prepareStatement(sql)
    try {
      ps.setString(1, indexing.spec)
      setOptInt(ps, 2, indexing.recordsIndexed)
      setOptInt(ps, 3, indexing.recordsExpected)
      setOptInt(ps, 4, indexing.orphansDeleted)
      setOptInt(ps, 5, indexing.errorCount)
      setOptString(ps, 6, indexing.lastStatus)
      setOptString(ps, 7, indexing.lastMessage)
      setOptTimestamp(ps, 8, indexing.lastTimestamp)
      setOptInt(ps, 9, indexing.lastRevision)
      ps.executeUpdate()
    } finally ps.close()
  }

  override def getIndexing(spec: String): Option[IndexingRecord] = withConnection { conn =>
    val ps = conn.prepareStatement("SELECT * FROM dataset_indexing WHERE spec = ?")
    try {
      ps.setString(1, spec)
      val rs = ps.executeQuery()
      try {
        if (rs.next()) Some(readIndexing(rs)) else None
      } finally rs.close()
    } finally ps.close()
  }

  private def readIndexing(rs: ResultSet): IndexingRecord =
    IndexingRecord(
      spec = rs.getString("spec"),
      recordsIndexed = getOptInt(rs, "records_indexed"),
      recordsExpected = getOptInt(rs, "records_expected"),
      orphansDeleted = getOptInt(rs, "orphans_deleted"),
      errorCount = getOptInt(rs, "error_count"),
      lastStatus = getOptString(rs, "last_status"),
      lastMessage = getOptString(rs, "last_message"),
      lastTimestamp = getOptInstant(rs, "last_timestamp"),
      lastRevision = getOptInt(rs, "last_revision")
    )

  // ---------------------------------------------------------------------------
  // Workflows
  // ---------------------------------------------------------------------------

  override def createWorkflow(wf: WorkflowRecord): Unit = withConnection { conn =>
    val sql =
      """INSERT INTO workflows (
        |  id, spec, trigger, status, retry_count, next_retry_at,
        |  error_message, started_at, completed_at
        |) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""".stripMargin
    val ps = conn.prepareStatement(sql)
    try {
      ps.setString(1, wf.id)
      ps.setString(2, wf.spec)
      ps.setString(3, wf.trigger)
      ps.setString(4, wf.status)
      ps.setInt(5, wf.retryCount)
      setOptTimestamp(ps, 6, wf.nextRetryAt)
      setOptString(ps, 7, wf.errorMessage)
      ps.setTimestamp(8, Timestamp.from(wf.startedAt))
      setOptTimestamp(ps, 9, wf.completedAt)
      ps.executeUpdate()
    } finally ps.close()
  }

  override def getWorkflow(id: String): Option[WorkflowRecord] = withConnection { conn =>
    val ps = conn.prepareStatement("SELECT * FROM workflows WHERE id = ?")
    try {
      ps.setString(1, id)
      val rs = ps.executeQuery()
      try {
        if (rs.next()) Some(readWorkflow(rs)) else None
      } finally rs.close()
    } finally ps.close()
  }

  override def getActiveWorkflows(spec: String): List[WorkflowRecord] = withConnection { conn =>
    val ps = conn.prepareStatement(
      "SELECT * FROM workflows WHERE spec = ? AND status IN ('running', 'pending') ORDER BY started_at"
    )
    try {
      ps.setString(1, spec)
      val rs = ps.executeQuery()
      try readList(rs)(readWorkflow)
      finally rs.close()
    } finally ps.close()
  }

  override def updateWorkflowStatus(
      id: String,
      status: String,
      errorMessage: Option[String] = None,
      completedAt: Option[Instant] = None
  ): Unit = withConnection { conn =>
    val sql =
      """UPDATE workflows SET
        |  status = ?, error_message = ?, completed_at = ?
        |WHERE id = ?""".stripMargin
    val ps = conn.prepareStatement(sql)
    try {
      ps.setString(1, status)
      setOptString(ps, 2, errorMessage)
      setOptTimestamp(ps, 3, completedAt)
      ps.setString(4, id)
      ps.executeUpdate()
    } finally ps.close()
  }

  override def getRetryWorkflows(): List[WorkflowRecord] = withConnection { conn =>
    val ps = conn.prepareStatement(
      "SELECT * FROM workflows WHERE status = 'retry' AND next_retry_at <= now() ORDER BY next_retry_at"
    )
    try {
      val rs = ps.executeQuery()
      try readList(rs)(readWorkflow)
      finally rs.close()
    } finally ps.close()
  }

  private def readWorkflow(rs: ResultSet): WorkflowRecord =
    WorkflowRecord(
      id = rs.getString("id"),
      spec = rs.getString("spec"),
      trigger = rs.getString("trigger"),
      status = rs.getString("status"),
      retryCount = rs.getInt("retry_count"),
      nextRetryAt = getOptInstant(rs, "next_retry_at"),
      errorMessage = getOptString(rs, "error_message"),
      startedAt = rs.getTimestamp("started_at").toInstant,
      completedAt = getOptInstant(rs, "completed_at")
    )

  // ---------------------------------------------------------------------------
  // Workflow Steps
  // ---------------------------------------------------------------------------

  override def createWorkflowStep(step: WorkflowStepRecord): Int = withConnection { conn =>
    val sql =
      """INSERT INTO workflow_steps (
        |  workflow_id, step_name, status, records_processed,
        |  error_message, metadata, started_at, completed_at
        |) VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?)""".stripMargin
    val ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)
    try {
      ps.setString(1, step.workflowId)
      ps.setString(2, step.stepName)
      ps.setString(3, step.status)
      ps.setInt(4, step.recordsProcessed)
      setOptString(ps, 5, step.errorMessage)
      setOptString(ps, 6, step.metadata)
      ps.setTimestamp(7, Timestamp.from(step.startedAt))
      setOptTimestamp(ps, 8, step.completedAt)
      ps.executeUpdate()
      val keys = ps.getGeneratedKeys
      try {
        keys.next()
        keys.getInt(1)
      } finally keys.close()
    } finally ps.close()
  }

  override def updateWorkflowStep(
      id: Int,
      status: String,
      recordsProcessed: Int = 0,
      errorMessage: Option[String] = None,
      completedAt: Option[Instant] = None,
      metadata: Option[String] = None
  ): Unit = withConnection { conn =>
    val sql =
      """UPDATE workflow_steps SET
        |  status = ?, records_processed = ?, error_message = ?, completed_at = ?, metadata = ?::jsonb
        |WHERE id = ?""".stripMargin
    val ps = conn.prepareStatement(sql)
    try {
      ps.setString(1, status)
      ps.setInt(2, recordsProcessed)
      setOptString(ps, 3, errorMessage)
      setOptTimestamp(ps, 4, completedAt)
      setOptString(ps, 5, metadata)
      ps.setInt(6, id)
      ps.executeUpdate()
    } finally ps.close()
  }

  override def getWorkflowSteps(workflowId: String): List[WorkflowStepRecord] = withConnection { conn =>
    val ps = conn.prepareStatement(
      "SELECT * FROM workflow_steps WHERE workflow_id = ? ORDER BY started_at"
    )
    try {
      ps.setString(1, workflowId)
      val rs = ps.executeQuery()
      try readList(rs)(readWorkflowStep)
      finally rs.close()
    } finally ps.close()
  }

  private def readWorkflowStep(rs: java.sql.ResultSet): WorkflowStepRecord =
    WorkflowStepRecord(
      id = Some(rs.getInt("id")),
      workflowId = rs.getString("workflow_id"),
      stepName = rs.getString("step_name"),
      status = rs.getString("status"),
      recordsProcessed = rs.getInt("records_processed"),
      errorMessage = getOptString(rs, "error_message"),
      metadata = getOptString(rs, "metadata"),
      startedAt = rs.getTimestamp("started_at").toInstant,
      completedAt = getOptInstant(rs, "completed_at")
    )

  // ---------------------------------------------------------------------------
  // Audit
  // ---------------------------------------------------------------------------

  override def getAuditHistory(spec: String, tableName: String, limit: Int = 50): List[AuditRecord] =
    withConnection { conn =>
      val ps = conn.prepareStatement(
        """SELECT * FROM audit_history
          |WHERE spec = ? AND table_name = ?
          |ORDER BY changed_at DESC
          |LIMIT ?""".stripMargin
      )
      try {
        ps.setString(1, spec)
        ps.setString(2, tableName)
        ps.setInt(3, limit)
        val rs = ps.executeQuery()
        try readList(rs)(readAudit)
        finally rs.close()
      } finally ps.close()
    }

  private def readAudit(rs: ResultSet): AuditRecord =
    AuditRecord(
      id = rs.getLong("id"),
      tableName = rs.getString("table_name"),
      spec = rs.getString("spec"),
      oldRow = getOptString(rs, "old_row"),
      newRow = getOptString(rs, "new_row"),
      changedAt = rs.getTimestamp("changed_at").toInstant,
      changedBy = getOptString(rs, "changed_by")
    )

  // ---------------------------------------------------------------------------
  // Batch listing (avoids N+1 queries)
  // ---------------------------------------------------------------------------

  override def listActiveDatasetsWithDetails(orgId: String): List[DatasetWithDetails] = withConnection { conn =>
    // Fetch all active datasets
    val datasets = {
      val ps = conn.prepareStatement("SELECT * FROM datasets WHERE org_id = ? AND deleted_at IS NULL ORDER BY spec")
      try {
        ps.setString(1, orgId)
        val rs = ps.executeQuery()
        try readList(rs)(readDataset) finally rs.close()
      } finally ps.close()
    }
    val specs = datasets.map(_.spec).toSet
    if (specs.isEmpty) Nil
    else {

    // Batch fetch all states for these specs
    val states = {
      val ps = conn.prepareStatement(
        s"SELECT * FROM dataset_state WHERE spec = ANY(?)"
      )
      try {
        ps.setArray(1, conn.createArrayOf("text", specs.toArray))
        val rs = ps.executeQuery()
        try readList(rs)(readState).map(s => s.spec -> s).toMap finally rs.close()
      } finally ps.close()
    }

    // Batch fetch all harvest configs
    val harvestConfigs = {
      val ps = conn.prepareStatement(
        s"SELECT * FROM dataset_harvest_config WHERE spec = ANY(?)"
      )
      try {
        ps.setArray(1, conn.createArrayOf("text", specs.toArray))
        val rs = ps.executeQuery()
        try readList(rs)(readHarvestConfig).map(h => h.spec -> h).toMap finally rs.close()
      } finally ps.close()
    }

    // Batch fetch all mapping configs
    val mappingConfigs = {
      val ps = conn.prepareStatement(
        s"SELECT * FROM dataset_mapping_config WHERE spec = ANY(?)"
      )
      try {
        ps.setArray(1, conn.createArrayOf("text", specs.toArray))
        val rs = ps.executeQuery()
        try readList(rs)(readMappingConfig).map(m => m.spec -> m).toMap finally rs.close()
      } finally ps.close()
    }

    datasets.map { ds =>
      DatasetWithDetails(
        dataset = ds,
        state = states.get(ds.spec),
        harvestConfig = harvestConfigs.get(ds.spec),
        mappingConfig = mappingConfigs.get(ds.spec)
      )
    }
    } // end else
  }

  // ---------------------------------------------------------------------------
  // Scheduling queries
  // ---------------------------------------------------------------------------

  override def listHarvestableDatasets(orgId: String): List[HarvestableDataset] = withConnection { conn =>
    val sql =
      """SELECT
        |  ds.spec,
        |  st.state,
        |  st.state_changed_at,
        |  hs.delay,
        |  hs.delay_unit,
        |  hs.incremental,
        |  hs.previous_time,
        |  hs.last_full_harvest,
        |  hs.last_incremental_harvest,
        |  wf.id AS active_workflow_id,
        |  wf.status AS active_workflow_status,
        |  wf.trigger AS workflow_trigger,
        |  st.error_message,
        |  rw.retry_count,
        |  rw.next_retry_at
        |FROM datasets ds
        |JOIN dataset_state st ON ds.spec = st.spec
        |LEFT JOIN dataset_harvest_schedule hs ON ds.spec = hs.spec
        |LEFT JOIN LATERAL (
        |  SELECT id, status, trigger
        |  FROM workflows w
        |  WHERE w.spec = ds.spec AND w.status IN ('running', 'pending', 'retry')
        |  LIMIT 1
        |) wf ON true
        |LEFT JOIN LATERAL (
        |  SELECT retry_count, next_retry_at
        |  FROM workflows w
        |  WHERE w.spec = ds.spec AND w.status = 'retry'
        |  LIMIT 1
        |) rw ON true
        |WHERE ds.org_id = ?
        |  AND ds.deleted_at IS NULL
        |  AND st.state IN ('stateSaved', 'stateIncrementalSaved')
        |  AND hs.delay IS NOT NULL
        |  AND hs.delay_unit IS NOT NULL
        |ORDER BY ds.spec"""
        .stripMargin
    val ps = conn.prepareStatement(sql)
    try {
      ps.setString(1, orgId)
      val rs = ps.executeQuery()
      try readList(rs)(readHarvestableDataset)
      finally rs.close()
    } finally ps.close()
  }

  override def listRetryableDatasets(orgId: String, retryIntervalMinutes: Int): List[HarvestableDataset] = withConnection { conn =>
    val sql =
      """SELECT
        |  ds.spec,
        |  st.state,
        |  st.state_changed_at,
        |  hs.delay,
        |  hs.delay_unit,
        |  hs.incremental,
        |  hs.previous_time,
        |  hs.last_full_harvest,
        |  hs.last_incremental_harvest,
        |  wf.id AS active_workflow_id,
        |  wf.status AS active_workflow_status,
        |  wf.trigger AS workflow_trigger,
        |  st.error_message,
        |  rw.retry_count,
        |  rw.next_retry_at
        |FROM datasets ds
        |JOIN dataset_state st ON ds.spec = st.spec
        |LEFT JOIN dataset_harvest_schedule hs ON ds.spec = hs.spec
        |LEFT JOIN LATERAL (
        |  SELECT id, status, trigger
        |  FROM workflows w
        |  WHERE w.spec = ds.spec AND w.status IN ('running', 'pending', 'retry')
        |  LIMIT 1
        |) wf ON true
        |LEFT JOIN LATERAL (
        |  SELECT retry_count, next_retry_at
        |  FROM workflows w
        |  WHERE w.spec = ds.spec AND w.status = 'retry'
        |  LIMIT 1
        |) rw ON true
        |WHERE ds.org_id = ?
        |  AND ds.deleted_at IS NULL
        |  AND st.error_message IS NOT NULL
        |  AND st.current_operation = 'HARVEST'
        |  AND st.operation_start <= NOW() - INTERVAL '1 minute' * ?
        |ORDER BY ds.spec"""
        .stripMargin
    val ps = conn.prepareStatement(sql)
    try {
      ps.setString(1, orgId)
      ps.setInt(2, retryIntervalMinutes)
      val rs = ps.executeQuery()
      try readList(rs)(readHarvestableDataset)
      finally rs.close()
    } finally ps.close()
  }

  private def readHarvestableDataset(rs: ResultSet): HarvestableDataset =
    HarvestableDataset(
      spec = rs.getString("spec"),
      state = rs.getString("state"),
      stateChangedAt = rs.getTimestamp("state_changed_at").toInstant,
      delay = getOptString(rs, "delay"),
      delayUnit = getOptString(rs, "delay_unit"),
      incremental = rs.getBoolean("incremental"),
      previousTime = getOptInstant(rs, "previous_time"),
      lastFullHarvest = getOptInstant(rs, "last_full_harvest"),
      lastIncrementalHarvest = getOptInstant(rs, "last_incremental_harvest"),
      activeWorkflowId = getOptString(rs, "active_workflow_id"),
      activeWorkflowStatus = getOptString(rs, "active_workflow_status"),
      workflowTrigger = getOptString(rs, "workflow_trigger"),
      errorMessage = getOptString(rs, "error_message"),
      retryCount = getOptInt(rs, "retry_count").getOrElse(0),
      nextRetryAt = getOptInstant(rs, "next_retry_at")
    )

  // ---------------------------------------------------------------------------
  // Index Stats
  // ---------------------------------------------------------------------------

  override def listDatasetsWithState(orgId: String): List[DatasetWithState] = withConnection { conn =>
    val sql =
      """SELECT
        |  ds.spec,
        |  COALESCE(st.state, 'CREATED') as state,
        |  COALESCE(st.record_count, 0) as record_count,
        |  COALESCE(st.processed_valid, 0) as processed_valid,
        |  COALESCE(st.processed_invalid, 0) as processed_invalid,
        |  COALESCE(st.acquired_count, 0) as acquired_count,
        |  COALESCE(st.deleted_count, 0) as deleted_count,
        |  COALESCE(st.source_count, 0) as source_count,
        |  st.acquisition_method,
        |  COALESCE(st.state_changed_at, ds.updated_at) as state_changed_at,
        |  (ds.deleted_at IS NOT NULL) as deleted
        |FROM datasets ds
        |LEFT JOIN dataset_state st ON ds.spec = st.spec
        |WHERE ds.org_id = ?
        |ORDER BY ds.spec""".stripMargin
    val ps = conn.prepareStatement(sql)
    try {
      ps.setString(1, orgId)
      val rs = ps.executeQuery()
      try readList(rs)(readDatasetWithState)
      finally rs.close()
    } finally ps.close()
  }

  private def readDatasetWithState(rs: ResultSet): DatasetWithState =
    DatasetWithState(
      spec = rs.getString("spec"),
      state = rs.getString("state"),
      recordCount = rs.getInt("record_count"),
      processedValid = rs.getInt("processed_valid"),
      processedInvalid = rs.getInt("processed_invalid"),
      acquiredCount = rs.getInt("acquired_count"),
      deletedCount = rs.getInt("deleted_count"),
      sourceCount = rs.getInt("source_count"),
      acquisitionMethod = getOptString(rs, "acquisition_method"),
      stateChangedAt = rs.getTimestamp("state_changed_at").toInstant,
      deleted = rs.getBoolean("deleted")
    )

  // ---------------------------------------------------------------------------
  // ResultSet iteration helper
  // ---------------------------------------------------------------------------

  private def readList[T](rs: ResultSet)(read: ResultSet => T): List[T] =
    Iterator.continually(rs).takeWhile(_.next()).map(read).toList
}
