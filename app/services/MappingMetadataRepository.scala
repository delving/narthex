package services

import java.sql.{Connection, PreparedStatement, ResultSet, Statement, Timestamp, Types}
import java.time.Instant
import play.api.Logging

// ---------------------------------------------------------------------------
// Default mapping records
// ---------------------------------------------------------------------------

case class DefaultMappingRecord(
    prefix: String,
    name: String,
    orgId: String,
    displayName: Option[String] = None,
    currentVersion: Option[Int] = None,
    createdAt: Instant = Instant.now(),
    updatedAt: Instant = Instant.now()
)

case class DefaultMappingVersionRecord(
    id: Option[Int] = None,
    prefix: String,
    name: String,
    hash: String,
    filename: Option[String] = None,
    source: Option[String] = None,
    sourceDataset: Option[String] = None,
    notes: Option[String] = None,
    createdAt: Instant = Instant.now()
)

// ---------------------------------------------------------------------------
// Dataset-specific mapping records
// ---------------------------------------------------------------------------

case class DatasetMappingRecord(
    spec: String,
    prefix: Option[String] = None,
    currentVersion: Option[Int] = None,
    updatedAt: Instant = Instant.now()
)

case class DatasetMappingVersionRecord(
    id: Option[Int] = None,
    spec: String,
    hash: String,
    filename: Option[String] = None,
    source: Option[String] = None,
    sourceDefault: Option[String] = None,
    description: Option[String] = None,
    createdAt: Instant = Instant.now()
)

/** JDBC-backed repository for mapping metadata stored in PostgreSQL.
  *
  * Uses the same patterns as [[PostgresDatasetRepository]]: plain JDBC with
  * HikariCP pool from [[DatabaseService]].
  */
class MappingMetadataRepository(db: DatabaseService) extends Logging {

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

  private def getOptString(rs: ResultSet, col: String): Option[String] =
    Option(rs.getString(col))

  private def getOptInt(rs: ResultSet, col: String): Option[Int] = {
    val v = rs.getInt(col)
    if (rs.wasNull()) None else Some(v)
  }

  private def readList[T](rs: ResultSet)(read: ResultSet => T): List[T] =
    Iterator.continually(rs).takeWhile(_.next()).map(read).toList

  // ===========================================================================
  // Default Mappings
  // ===========================================================================

  def listDefaultMappings(orgId: String): List[DefaultMappingRecord] = withConnection { conn =>
    val ps = conn.prepareStatement(
      "SELECT * FROM default_mappings WHERE org_id = ? ORDER BY prefix, name"
    )
    try {
      ps.setString(1, orgId)
      val rs = ps.executeQuery()
      try readList(rs)(readDefaultMapping)
      finally rs.close()
    } finally ps.close()
  }

  def getDefaultMapping(prefix: String, name: String): Option[DefaultMappingRecord] =
    withConnection { conn =>
      val ps = conn.prepareStatement(
        "SELECT * FROM default_mappings WHERE prefix = ? AND name = ?"
      )
      try {
        ps.setString(1, prefix)
        ps.setString(2, name)
        val rs = ps.executeQuery()
        try {
          if (rs.next()) Some(readDefaultMapping(rs)) else None
        } finally rs.close()
      } finally ps.close()
    }

  def upsertDefaultMapping(mapping: DefaultMappingRecord): Unit = withConnection { conn =>
    val sql =
      """INSERT INTO default_mappings (
        |  prefix, name, org_id, display_name, current_version, created_at, updated_at
        |) VALUES (?, ?, ?, ?, ?, ?, ?)
        |ON CONFLICT (prefix, name) DO UPDATE SET
        |  org_id = EXCLUDED.org_id,
        |  display_name = EXCLUDED.display_name,
        |  current_version = EXCLUDED.current_version,
        |  updated_at = EXCLUDED.updated_at""".stripMargin
    val ps = conn.prepareStatement(sql)
    try {
      ps.setString(1, mapping.prefix)
      ps.setString(2, mapping.name)
      ps.setString(3, mapping.orgId)
      setOptString(ps, 4, mapping.displayName)
      setOptInt(ps, 5, mapping.currentVersion)
      ps.setTimestamp(6, Timestamp.from(mapping.createdAt))
      ps.setTimestamp(7, Timestamp.from(mapping.updatedAt))
      ps.executeUpdate()
    } finally ps.close()
  }

  def listDefaultMappingVersions(
      prefix: String,
      name: String
  ): List[DefaultMappingVersionRecord] = withConnection { conn =>
    val ps = conn.prepareStatement(
      "SELECT * FROM default_mapping_versions WHERE prefix = ? AND name = ? ORDER BY created_at, id"
    )
    try {
      ps.setString(1, prefix)
      ps.setString(2, name)
      val rs = ps.executeQuery()
      try readList(rs)(readDefaultMappingVersion)
      finally rs.close()
    } finally ps.close()
  }

  def addDefaultMappingVersion(version: DefaultMappingVersionRecord): Int = withConnection {
    conn =>
      val sql =
        """INSERT INTO default_mapping_versions (
          |  prefix, name, hash, filename, source, source_dataset, notes, created_at
          |) VALUES (?, ?, ?, ?, ?, ?, ?, ?)""".stripMargin
      val ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)
      try {
        ps.setString(1, version.prefix)
        ps.setString(2, version.name)
        ps.setString(3, version.hash)
        setOptString(ps, 4, version.filename)
        setOptString(ps, 5, version.source)
        setOptString(ps, 6, version.sourceDataset)
        setOptString(ps, 7, version.notes)
        ps.setTimestamp(8, Timestamp.from(version.createdAt))
        ps.executeUpdate()
        val keys = ps.getGeneratedKeys
        try {
          keys.next()
          keys.getInt(1)
        } finally keys.close()
      } finally ps.close()
  }

  /** Sets the current version of a default mapping by looking up the version id
    * that matches the given hash.
    */
  def setDefaultMappingCurrentVersion(prefix: String, name: String, hash: String): Unit =
    withConnection { conn =>
      val sql =
        """UPDATE default_mappings SET
          |  current_version = (
          |    SELECT id FROM default_mapping_versions
          |    WHERE prefix = ? AND name = ? AND hash = ?
          |  ),
          |  updated_at = now()
          |WHERE prefix = ? AND name = ?""".stripMargin
      val ps = conn.prepareStatement(sql)
      try {
        ps.setString(1, prefix)
        ps.setString(2, name)
        ps.setString(3, hash)
        ps.setString(4, prefix)
        ps.setString(5, name)
        ps.executeUpdate()
      } finally ps.close()
    }

  private def readDefaultMapping(rs: ResultSet): DefaultMappingRecord =
    DefaultMappingRecord(
      prefix = rs.getString("prefix"),
      name = rs.getString("name"),
      orgId = rs.getString("org_id"),
      displayName = getOptString(rs, "display_name"),
      currentVersion = getOptInt(rs, "current_version"),
      createdAt = rs.getTimestamp("created_at").toInstant,
      updatedAt = rs.getTimestamp("updated_at").toInstant
    )

  private def readDefaultMappingVersion(rs: ResultSet): DefaultMappingVersionRecord =
    DefaultMappingVersionRecord(
      id = Some(rs.getInt("id")),
      prefix = rs.getString("prefix"),
      name = rs.getString("name"),
      hash = rs.getString("hash"),
      filename = getOptString(rs, "filename"),
      source = getOptString(rs, "source"),
      sourceDataset = getOptString(rs, "source_dataset"),
      notes = getOptString(rs, "notes"),
      createdAt = rs.getTimestamp("created_at").toInstant
    )

  // ===========================================================================
  // Dataset Mappings
  // ===========================================================================

  def getDatasetMapping(spec: String): Option[DatasetMappingRecord] = withConnection { conn =>
    val ps = conn.prepareStatement("SELECT * FROM dataset_mappings WHERE spec = ?")
    try {
      ps.setString(1, spec)
      val rs = ps.executeQuery()
      try {
        if (rs.next()) Some(readDatasetMapping(rs)) else None
      } finally rs.close()
    } finally ps.close()
  }

  def upsertDatasetMapping(mapping: DatasetMappingRecord): Unit = withConnection { conn =>
    val sql =
      """INSERT INTO dataset_mappings (
        |  spec, prefix, current_version, updated_at
        |) VALUES (?, ?, ?, ?)
        |ON CONFLICT (spec) DO UPDATE SET
        |  prefix = EXCLUDED.prefix,
        |  current_version = EXCLUDED.current_version,
        |  updated_at = EXCLUDED.updated_at""".stripMargin
    val ps = conn.prepareStatement(sql)
    try {
      ps.setString(1, mapping.spec)
      setOptString(ps, 2, mapping.prefix)
      setOptInt(ps, 3, mapping.currentVersion)
      ps.setTimestamp(4, Timestamp.from(mapping.updatedAt))
      ps.executeUpdate()
    } finally ps.close()
  }

  def listDatasetMappingVersions(spec: String): List[DatasetMappingVersionRecord] =
    withConnection { conn =>
      val ps = conn.prepareStatement(
        "SELECT * FROM dataset_mapping_versions WHERE spec = ? ORDER BY created_at, id"
      )
      try {
        ps.setString(1, spec)
        val rs = ps.executeQuery()
        try readList(rs)(readDatasetMappingVersion)
        finally rs.close()
      } finally ps.close()
    }

  def addDatasetMappingVersion(version: DatasetMappingVersionRecord): Int = withConnection {
    conn =>
      val sql =
        """INSERT INTO dataset_mapping_versions (
          |  spec, hash, filename, source, source_default, description, created_at
          |) VALUES (?, ?, ?, ?, ?, ?, ?)""".stripMargin
      val ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)
      try {
        ps.setString(1, version.spec)
        ps.setString(2, version.hash)
        setOptString(ps, 3, version.filename)
        setOptString(ps, 4, version.source)
        setOptString(ps, 5, version.sourceDefault)
        setOptString(ps, 6, version.description)
        ps.setTimestamp(7, Timestamp.from(version.createdAt))
        ps.executeUpdate()
        val keys = ps.getGeneratedKeys
        try {
          keys.next()
          keys.getInt(1)
        } finally keys.close()
      } finally ps.close()
  }

  /** Sets the current version of a dataset mapping by looking up the version id
    * that matches the given hash.
    */
  def setDatasetMappingCurrentVersion(spec: String, hash: String): Unit = withConnection {
    conn =>
      val sql =
        """UPDATE dataset_mappings SET
          |  current_version = (
          |    SELECT id FROM dataset_mapping_versions
          |    WHERE spec = ? AND hash = ?
          |  ),
          |  updated_at = now()
          |WHERE spec = ?""".stripMargin
      val ps = conn.prepareStatement(sql)
      try {
        ps.setString(1, spec)
        ps.setString(2, hash)
        ps.setString(3, spec)
        ps.executeUpdate()
      } finally ps.close()
  }

  private def readDatasetMapping(rs: ResultSet): DatasetMappingRecord =
    DatasetMappingRecord(
      spec = rs.getString("spec"),
      prefix = getOptString(rs, "prefix"),
      currentVersion = getOptInt(rs, "current_version"),
      updatedAt = rs.getTimestamp("updated_at").toInstant
    )

  private def readDatasetMappingVersion(rs: ResultSet): DatasetMappingVersionRecord =
    DatasetMappingVersionRecord(
      id = Some(rs.getInt("id")),
      spec = rs.getString("spec"),
      hash = rs.getString("hash"),
      filename = getOptString(rs, "filename"),
      source = getOptString(rs, "source"),
      sourceDefault = getOptString(rs, "source_default"),
      description = getOptString(rs, "description"),
      createdAt = rs.getTimestamp("created_at").toInstant
    )
}

/** Global singleton holder for [[MappingMetadataRepository]].
  *
  * Follows the same pattern as [[GlobalDatabaseService]] and [[GlobalDsInfoService]].
  * Will be replaced by proper Guice DI in Phase 3.
  */
object GlobalMappingMetadataRepository {
  @volatile private var instance: Option[MappingMetadataRepository] = None

  def set(repo: MappingMetadataRepository): Unit = {
    instance = Some(repo)
  }

  def get(): Option[MappingMetadataRepository] = instance

  def getOrThrow(): MappingMetadataRepository = instance.getOrElse(
    throw new IllegalStateException("MappingMetadataRepository not configured")
  )

  def clear(): Unit = {
    instance = None
  }
}
