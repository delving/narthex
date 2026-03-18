package services

import java.sql.{Connection, PreparedStatement, ResultSet, Timestamp, Types}
import java.time.Instant
import play.api.Logging

/** OAI-PMH source record stored in PostgreSQL.
  *
  * Represents a configured OAI-PMH endpoint used for dataset discovery.
  * The [[mappingRules]] field is stored as a JSONB string — parsing is left
  * to the service layer.
  */
case class OaiSourceRecord(
    id: String,
    orgId: String,
    name: String,
    url: String,
    defaultMetadataPrefix: String = "oai_dc",
    defaultAggregator: Option[String] = None,
    defaultPrefix: String = "edm",
    defaultEdmType: Option[String] = None,
    harvestDelay: Option[Int] = None,
    harvestDelayUnit: Option[String] = None,
    harvestIncremental: Boolean = false,
    mappingRules: String = "[]",
    ignoredSets: List[String] = Nil,
    enabled: Boolean = true,
    lastChecked: Option[Instant] = None,
    createdAt: Instant = Instant.now()
)

/** Cached record count for a single OAI-PMH set within a source. */
case class SetCountRecord(
    sourceId: String,
    setSpec: String,
    recordCount: Option[Int] = None,
    error: Option[String] = None,
    verifiedAt: Instant = Instant.now()
)

/** JDBC-backed repository for OAI-PMH source configurations.
  *
  * Operates on the `oai_sources` and `oai_source_set_counts` tables created
  * by Flyway V4. Uses the same connection/parameter patterns as
  * [[PostgresDatasetRepository]].
  */
class OaiSourceRepository(db: DatabaseService) extends Logging {

  // ---------------------------------------------------------------------------
  // Connection helper
  // ---------------------------------------------------------------------------

  private def withConnection[T](f: Connection => T): T = {
    val conn = db.getConnection()
    try f(conn)
    finally conn.close()
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
  // Sources — CRUD
  // ---------------------------------------------------------------------------

  /** List all OAI sources for the given organisation. */
  def listSources(orgId: String): List[OaiSourceRecord] = withConnection { conn =>
    val ps = conn.prepareStatement(
      "SELECT * FROM oai_sources WHERE org_id = ? ORDER BY name"
    )
    try {
      ps.setString(1, orgId)
      val rs = ps.executeQuery()
      try readList(rs)(readSource)
      finally rs.close()
    } finally ps.close()
  }

  /** Retrieve a single source by ID. */
  def getSource(id: String): Option[OaiSourceRecord] = withConnection { conn =>
    val ps = conn.prepareStatement("SELECT * FROM oai_sources WHERE id = ?")
    try {
      ps.setString(1, id)
      val rs = ps.executeQuery()
      try {
        if (rs.next()) Some(readSource(rs)) else None
      } finally rs.close()
    } finally ps.close()
  }

  /** Insert a new OAI source. */
  def createSource(source: OaiSourceRecord): Unit = withConnection { conn =>
    val sql =
      """INSERT INTO oai_sources (
        |  id, org_id, name, url, default_metadata_prefix,
        |  default_aggregator, default_prefix, default_edm_type,
        |  harvest_delay, harvest_delay_unit, harvest_incremental,
        |  mapping_rules, ignored_sets, enabled, last_checked, created_at
        |) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)""".stripMargin
    val ps = conn.prepareStatement(sql)
    try {
      ps.setString(1, source.id)
      ps.setString(2, source.orgId)
      ps.setString(3, source.name)
      ps.setString(4, source.url)
      ps.setString(5, source.defaultMetadataPrefix)
      setOptString(ps, 6, source.defaultAggregator)
      ps.setString(7, source.defaultPrefix)
      setOptString(ps, 8, source.defaultEdmType)
      setOptInt(ps, 9, source.harvestDelay)
      setOptString(ps, 10, source.harvestDelayUnit)
      ps.setBoolean(11, source.harvestIncremental)
      ps.setString(12, source.mappingRules)
      ps.setArray(13, conn.createArrayOf("text", source.ignoredSets.toArray))
      ps.setBoolean(14, source.enabled)
      setOptTimestamp(ps, 15, source.lastChecked)
      ps.setTimestamp(16, Timestamp.from(source.createdAt))
      ps.executeUpdate()
    } finally ps.close()
  }

  /** Update an existing OAI source (all fields except id and created_at). */
  def updateSource(source: OaiSourceRecord): Unit = withConnection { conn =>
    val sql =
      """UPDATE oai_sources SET
        |  org_id = ?, name = ?, url = ?, default_metadata_prefix = ?,
        |  default_aggregator = ?, default_prefix = ?, default_edm_type = ?,
        |  harvest_delay = ?, harvest_delay_unit = ?, harvest_incremental = ?,
        |  mapping_rules = ?::jsonb, ignored_sets = ?, enabled = ?, last_checked = ?
        |WHERE id = ?""".stripMargin
    val ps = conn.prepareStatement(sql)
    try {
      ps.setString(1, source.orgId)
      ps.setString(2, source.name)
      ps.setString(3, source.url)
      ps.setString(4, source.defaultMetadataPrefix)
      setOptString(ps, 5, source.defaultAggregator)
      ps.setString(6, source.defaultPrefix)
      setOptString(ps, 7, source.defaultEdmType)
      setOptInt(ps, 8, source.harvestDelay)
      setOptString(ps, 9, source.harvestDelayUnit)
      ps.setBoolean(10, source.harvestIncremental)
      ps.setString(11, source.mappingRules)
      ps.setArray(12, conn.createArrayOf("text", source.ignoredSets.toArray))
      ps.setBoolean(13, source.enabled)
      setOptTimestamp(ps, 14, source.lastChecked)
      ps.setString(15, source.id)
      ps.executeUpdate()
    } finally ps.close()
  }

  /** Delete a source (cascades to set counts via FK). */
  def deleteSource(id: String): Unit = withConnection { conn =>
    val ps = conn.prepareStatement("DELETE FROM oai_sources WHERE id = ?")
    try {
      ps.setString(1, id)
      ps.executeUpdate()
    } finally ps.close()
  }

  // ---------------------------------------------------------------------------
  // Ignored sets — array operations
  // ---------------------------------------------------------------------------

  /** Add setSpecs to the ignored_sets array. */
  def addIgnoredSets(sourceId: String, setSpecs: List[String]): Unit = withConnection { conn =>
    val ps = conn.prepareStatement(
      "UPDATE oai_sources SET ignored_sets = array_cat(ignored_sets, ?) WHERE id = ?"
    )
    try {
      ps.setArray(1, conn.createArrayOf("text", setSpecs.toArray))
      ps.setString(2, sourceId)
      ps.executeUpdate()
    } finally ps.close()
  }

  /** Remove setSpecs from the ignored_sets array. */
  def removeIgnoredSets(sourceId: String, setSpecs: List[String]): Unit = withConnection { conn =>
    setSpecs.foreach { spec =>
      val ps = conn.prepareStatement(
        "UPDATE oai_sources SET ignored_sets = array_remove(ignored_sets, ?) WHERE id = ?"
      )
      try {
        ps.setString(1, spec)
        ps.setString(2, sourceId)
        ps.executeUpdate()
      } finally ps.close()
    }
  }

  /** Set last_checked to the current time. */
  def updateLastChecked(sourceId: String): Unit = withConnection { conn =>
    val ps = conn.prepareStatement(
      "UPDATE oai_sources SET last_checked = now() WHERE id = ?"
    )
    try {
      ps.setString(1, sourceId)
      ps.executeUpdate()
    } finally ps.close()
  }

  // ---------------------------------------------------------------------------
  // Set counts cache
  // ---------------------------------------------------------------------------

  /** Retrieve cached set counts for a source. */
  def getSetCounts(sourceId: String): List[SetCountRecord] = withConnection { conn =>
    val ps = conn.prepareStatement(
      "SELECT * FROM oai_source_set_counts WHERE source_id = ? ORDER BY set_spec"
    )
    try {
      ps.setString(1, sourceId)
      val rs = ps.executeQuery()
      try readList(rs)(readSetCount)
      finally rs.close()
    } finally ps.close()
  }

  /** Save (upsert) set counts. Existing rows for the same (source_id, set_spec)
    * are updated; new rows are inserted.
    */
  def saveSetCounts(counts: List[SetCountRecord]): Unit = withConnection { conn =>
    val sql =
      """INSERT INTO oai_source_set_counts (source_id, set_spec, record_count, error, verified_at)
        |VALUES (?, ?, ?, ?, ?)
        |ON CONFLICT (source_id, set_spec) DO UPDATE SET
        |  record_count = EXCLUDED.record_count,
        |  error = EXCLUDED.error,
        |  verified_at = EXCLUDED.verified_at""".stripMargin
    val ps = conn.prepareStatement(sql)
    try {
      counts.foreach { c =>
        ps.setString(1, c.sourceId)
        ps.setString(2, c.setSpec)
        setOptInt(ps, 3, c.recordCount)
        setOptString(ps, 4, c.error)
        ps.setTimestamp(5, Timestamp.from(c.verifiedAt))
        ps.addBatch()
      }
      ps.executeBatch()
    } finally ps.close()
  }

  /** Delete all cached set counts for a source. */
  def clearSetCounts(sourceId: String): Unit = withConnection { conn =>
    val ps = conn.prepareStatement(
      "DELETE FROM oai_source_set_counts WHERE source_id = ?"
    )
    try {
      ps.setString(1, sourceId)
      ps.executeUpdate()
    } finally ps.close()
  }

  // ---------------------------------------------------------------------------
  // ResultSet readers
  // ---------------------------------------------------------------------------

  private def readSource(rs: ResultSet): OaiSourceRecord = {
    val ignoredArray = Option(rs.getArray("ignored_sets"))
      .map(_.getArray.asInstanceOf[Array[String]].toList)
      .getOrElse(Nil)
    OaiSourceRecord(
      id = rs.getString("id"),
      orgId = rs.getString("org_id"),
      name = rs.getString("name"),
      url = rs.getString("url"),
      defaultMetadataPrefix = rs.getString("default_metadata_prefix"),
      defaultAggregator = getOptString(rs, "default_aggregator"),
      defaultPrefix = rs.getString("default_prefix"),
      defaultEdmType = getOptString(rs, "default_edm_type"),
      harvestDelay = getOptInt(rs, "harvest_delay"),
      harvestDelayUnit = getOptString(rs, "harvest_delay_unit"),
      harvestIncremental = rs.getBoolean("harvest_incremental"),
      mappingRules = Option(rs.getString("mapping_rules")).getOrElse("[]"),
      ignoredSets = ignoredArray,
      enabled = rs.getBoolean("enabled"),
      lastChecked = getOptInstant(rs, "last_checked"),
      createdAt = rs.getTimestamp("created_at").toInstant
    )
  }

  private def readSetCount(rs: ResultSet): SetCountRecord =
    SetCountRecord(
      sourceId = rs.getString("source_id"),
      setSpec = rs.getString("set_spec"),
      recordCount = getOptInt(rs, "record_count"),
      error = getOptString(rs, "error"),
      verifiedAt = rs.getTimestamp("verified_at").toInstant
    )

  private def readList[T](rs: ResultSet)(read: ResultSet => T): List[T] =
    Iterator.continually(rs).takeWhile(_.next()).map(read).toList
}

/** Global singleton holder for [[OaiSourceRepository]].
  *
  * Follows the same pattern as [[GlobalDsInfoService]] and [[GlobalDatabaseService]].
  * Will be replaced by proper Guice DI in Phase 3.
  */
object GlobalOaiSourceRepository {
  @volatile private var instance: Option[OaiSourceRepository] = None

  def set(repo: OaiSourceRepository): Unit = {
    instance = Some(repo)
  }

  def get(): Option[OaiSourceRepository] = instance

  def getOrThrow(): OaiSourceRepository = instance.getOrElse(
    throw new IllegalStateException("OaiSourceRepository not configured")
  )

  def clear(): Unit = {
    instance = None
  }
}
