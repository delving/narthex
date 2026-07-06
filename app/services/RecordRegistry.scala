package services

import java.io.File
import java.sql.{Connection, DriverManager}
import java.time.{Instant, ZoneOffset}
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import javax.inject.{Inject, Singleton}

import init.NarthexConfig
import org.apache.commons.io.FileUtils
import play.api.Logger

object RecordRegistry {
  val SCHEMA_VERSION = "3"
  val DB_FILENAME = "records.db"

  val STATUS_SEEN    = "seen"
  val STATUS_DELETED = "deleted"

  val RUN_RUNNING   = "running"
  val RUN_COMPLETED = "completed"
  val RUN_FAILED    = "failed"

  val KIND_FULL      = "full"
  val KIND_INCREMENT = "incremental"

  val NOTE_BASELINE = "baseline"

  /** Per-run diff: what this run actually did, derived from the records table. */
  case class RunDiff(added: Int, changed: Int, deleted: Int, seenTotal: Int)

  /** One row of harvest_runs, for the trends API. */
  case class RunSummary(
    runId: Long,
    kind: String,
    startedAt: String,
    completedAt: Option[String],
    status: String,
    added: Int,
    changed: Int,
    deleted: Int,
    seen: Int,
    sent: Int,
    baseline: Boolean
  )

  /** Completed-run diffs summed per UTC day, for the trends API. */
  case class DailyRunDiff(date: String, added: Int, changed: Int, deleted: Int, runs: Int)

  /** Records not yet confirmed in Hub3 — explains indexed-count lag to the UI. */
  case class PendingCounts(pendingIndex: Int, pendingDrops: Int)

  import play.api.libs.json.{Json, OWrites}
  implicit val runSummaryWrites: OWrites[RunSummary] = Json.writes[RunSummary]
  implicit val dailyRunDiffWrites: OWrites[DailyRunDiff] = Json.writes[DailyRunDiff]
  implicit val pendingCountsWrites: OWrites[PendingCounts] = Json.writes[PendingCounts]

  private val TS_FORMAT =
    DateTimeFormatter.ISO_INSTANT.withZone(ZoneOffset.UTC)

  def nowIso(): String = TS_FORMAT.format(Instant.now())

  def isoDaysAgo(days: Int): String =
    TS_FORMAT.format(Instant.now().minus(java.time.Duration.ofDays(days.toLong)))
}

@Singleton
class RecordRegistry(datasetsDir: File) {

  import RecordRegistry._

  @Inject() def this(narthexConfig: NarthexConfig) =
    this(new File(new File(narthexConfig.narthexDataDir, narthexConfig.orgId), "datasets"))

  private val logger = Logger(getClass)
  private val specs = new ConcurrentHashMap[String, SpecRegistry]()

  Class.forName("org.sqlite.JDBC")

  private def spec(name: String): SpecRegistry =
    specs.computeIfAbsent(name, s => new SpecRegistry(new File(datasetsDir, s)))

  def beginRun(specName: String, kind: String): Long =
    spec(specName).beginRun(kind)

  def completeRun(specName: String, runId: Long, sentCount: Int = 0): Unit =
    spec(specName).completeRun(runId, sentCount)

  /**
   * Map raw OAI tombstone identifiers onto the id space of the seen rows.
   * deleted.ids holds raw header ids (oai:x:123); the registry keys on the
   * dataset's pocket ids, which are sometimes the full cleaned header id and
   * sometimes just the local part (123). For each raw id, prefer whichever
   * candidate variant already exists as a registry row; fall back to the full
   * cleaned id. Without this, drops are emitted with ids Hub3 cannot match.
   */
  def resolveTombstoneIds(specName: String, rawIds: Seq[String]): Seq[String] =
    if (rawIds.isEmpty) Seq.empty
    else spec(specName).resolveTombstoneIds(rawIds)

  // Read paths guard on file existence: spec() would CREATE an empty
  // records.db, so browsing trends must never go through it for datasets
  // that have no registry yet.
  private def dbFileExists(specName: String): Boolean =
    new File(new File(datasetsDir, specName), DB_FILENAME).exists()

  def listRuns(specName: String, sinceDays: Int): Seq[RunSummary] =
    if (dbFileExists(specName)) spec(specName).listRuns(sinceDays) else Seq.empty

  def dailyRunDiffs(specName: String, sinceDays: Int): Seq[DailyRunDiff] =
    if (dbFileExists(specName)) spec(specName).dailyRunDiffs(sinceDays) else Seq.empty

  def pendingCounts(specName: String): PendingCounts =
    if (dbFileExists(specName)) spec(specName).pendingCounts()
    else PendingCounts(0, 0)

  /** Seen-record count, without creating a db for specs that have none. */
  def seenCountIfExists(specName: String): Option[Int] =
    if (dbFileExists(specName)) Some(spec(specName).count(STATUS_SEEN)) else None

  /**
   * True when every id is already a deleted row that has been sent to Hub3.
   * Lets the deletes-only harvest path skip opening a no-op run for the
   * cumulative deleted.ids re-read on every quiet tick.
   */
  def allTombstonesSynced(specName: String, localIds: Seq[String]): Boolean =
    localIds.isEmpty ||
      (dbFileExists(specName) && spec(specName).allTombstonesSynced(localIds))

  /**
   * The run that produced the current processed output. Manual saves carry no
   * run id of their own and adopt this one so confirms/sweep/completeRun still
   * happen. Must be a FULL run: sweeping with an incremental run id would mark
   * everything that run didn't touch as deleted.
   */
  def latestCompletedFullRunId(specName: String): Option[Long] =
    if (dbFileExists(specName)) spec(specName).latestCompletedFullRunId() else None

  def failOpenRuns(specName: String, note: String): Int =
    spec(specName).failOpenRuns(note)

  def runStatus(specName: String, runId: Long): Option[String] =
    spec(specName).runStatus(runId)

  def upsertSeen(specName: String, localId: String, contentHash: String, runId: Long): Unit =
    spec(specName).upsertSeen(Seq(localId -> contentHash), runId)

  def upsertSeenBatch(specName: String, rows: Seq[(String, String)], runId: Long): Unit =
    if (rows.nonEmpty) spec(specName).upsertSeen(rows, runId)

  def upsertDeleted(specName: String, localId: String, runId: Long): Unit =
    spec(specName).upsertDeleted(Seq(localId), runId)

  def upsertDeletedBatch(specName: String, localIds: Seq[String], runId: Long): Unit =
    if (localIds.nonEmpty) spec(specName).upsertDeleted(localIds, runId)

  def markMissingForFullRun(specName: String, runId: Long): Int =
    spec(specName).markMissingForFullRun(runId)

  def pendingIndexBatch(specName: String, limit: Int): Seq[(String, String)] =
    spec(specName).pendingIndexBatch(limit)

  def pendingDropBatch(specName: String, limit: Int): Seq[String] =
    spec(specName).pendingDropBatch(limit)

  def confirmIndexed(specName: String, rows: Seq[(String, String)], runId: Long): Unit =
    if (rows.nonEmpty) spec(specName).confirmIndexed(rows, runId)

  def confirmIndexedByIds(specName: String, localIds: Seq[String], runId: Long): Unit =
    if (localIds.nonEmpty) spec(specName).confirmIndexedByIds(localIds, runId)

  def confirmDropped(specName: String, localIds: Seq[String]): Unit =
    if (localIds.nonEmpty) spec(specName).confirmDropped(localIds)

  def count(specName: String, status: String): Int =
    spec(specName).count(status)

  def dropDatasetDb(specName: String): Unit = {
    val reg = specs.remove(specName)
    if (reg != null) reg.closeAndDelete()
    // Uncached: just remove the files. Constructing a SpecRegistry here would
    // mkdirs + open a connection, resurrecting the dataset dir mid-delete.
    else SpecRegistry.deleteDbFiles(new File(datasetsDir, specName))
  }

  def close(): Unit = {
    val it = specs.values().iterator()
    while (it.hasNext) {
      try it.next().close()
      catch { case e: Exception => logger.warn(s"closing spec registry: ${e.getMessage}") }
    }
    specs.clear()
  }
}

private[services] object SpecRegistry {
  import RecordRegistry.DB_FILENAME

  def deleteDbFiles(datasetDir: File): Unit =
    Seq("", "-wal", "-shm", "-journal")
      .map(suffix => new File(datasetDir, DB_FILENAME + suffix))
      .foreach(FileUtils.deleteQuietly(_))
}

private[services] class SpecRegistry(val datasetDir: File) {

  import RecordRegistry._

  private val logger = Logger(getClass)

  datasetDir.mkdirs()
  private val dbFile = new File(datasetDir, DB_FILENAME)

  private val conn: Connection = {
    val c = DriverManager.getConnection(s"jdbc:sqlite:${dbFile.getAbsolutePath}")
    c.setAutoCommit(true)
    val s = c.createStatement()
    try {
      s.executeUpdate("PRAGMA journal_mode=WAL")
      s.executeUpdate("PRAGMA synchronous=NORMAL")
      s.executeUpdate("PRAGMA foreign_keys=ON")
      s.executeUpdate("PRAGMA busy_timeout=5000")
    } finally s.close()
    c.setAutoCommit(false)
    migrate(c)
    c
  }

  private def migrate(c: Connection): Unit = {
    val s = c.createStatement()
    try {
      s.executeUpdate("""CREATE TABLE IF NOT EXISTS schema_meta (
        k TEXT PRIMARY KEY,
        v TEXT NOT NULL
      )""")
      // Fresh dbs get the v2 shape directly; existing tables are untouched
      // here and upgraded by the version branch below.
      s.executeUpdate("""CREATE TABLE IF NOT EXISTS records (
        local_id            TEXT PRIMARY KEY,
        content_hash        TEXT NOT NULL,
        status              TEXT NOT NULL,
        first_seen_run_id   INTEGER,
        last_changed_run_id INTEGER,
        last_seen_run_id    INTEGER NOT NULL,
        last_seen_ts        TEXT NOT NULL,
        last_sent_hash      TEXT,
        last_sent_run_id    INTEGER,
        created_at          TEXT NOT NULL,
        updated_at          TEXT NOT NULL
      )""")
      s.executeUpdate("""CREATE TABLE IF NOT EXISTS harvest_runs (
        run_id        INTEGER PRIMARY KEY AUTOINCREMENT,
        kind          TEXT NOT NULL,
        started_at    TEXT NOT NULL,
        completed_at  TEXT,
        status        TEXT NOT NULL,
        seen_count    INTEGER,
        changed_count INTEGER,
        deleted_count INTEGER,
        added_count   INTEGER,
        sent_count    INTEGER,
        note          TEXT
      )""")

      val storedVersion: Option[String] = {
        val rs = s.executeQuery("SELECT v FROM schema_meta WHERE k = 'schema_version'")
        try { if (rs.next()) Option(rs.getString(1)) else None } finally rs.close()
      }

      // Sequential upgrades: each case falls through to the next version's
      // ALTERs so any historical db lands on the current schema.
      def upgradeV1toV2(): Unit = {
        logger.info(s"Migrating $dbFile from schema v1 to v2")
        s.executeUpdate("ALTER TABLE records ADD COLUMN first_seen_run_id INTEGER")
        s.executeUpdate("ALTER TABLE records ADD COLUMN last_changed_run_id INTEGER")
        s.executeUpdate("ALTER TABLE harvest_runs ADD COLUMN added_count INTEGER")
        // Best-effort backfill: v1 never recorded first-seen, so the oldest
        // known association is last_seen. Prevents pre-migration records
        // from ever counting as "added" retroactively.
        s.executeUpdate("UPDATE records SET first_seen_run_id = last_seen_run_id WHERE first_seen_run_id IS NULL")
      }
      def upgradeV2toV3(): Unit = {
        logger.info(s"Migrating $dbFile from schema v2 to v3")
        s.executeUpdate("ALTER TABLE harvest_runs ADD COLUMN sent_count INTEGER")
      }

      storedVersion match {
        case None =>
          s.executeUpdate(s"INSERT INTO schema_meta (k, v) VALUES ('schema_version', '$SCHEMA_VERSION')")
        case Some("1") =>
          upgradeV1toV2()
          upgradeV2toV3()
          s.executeUpdate(s"UPDATE schema_meta SET v = '$SCHEMA_VERSION' WHERE k = 'schema_version'")
        case Some("2") =>
          upgradeV2toV3()
          s.executeUpdate(s"UPDATE schema_meta SET v = '$SCHEMA_VERSION' WHERE k = 'schema_version'")
        case Some(SCHEMA_VERSION) => // current
        case Some(other) =>
          sys.error(s"records.db schema_version $other, expected $SCHEMA_VERSION: ${datasetDir.getAbsolutePath}")
      }

      s.executeUpdate("CREATE INDEX IF NOT EXISTS idx_records_status ON records(status)")
      s.executeUpdate("CREATE INDEX IF NOT EXISTS idx_records_last_seen_run_id ON records(last_seen_run_id)")
      s.executeUpdate("CREATE INDEX IF NOT EXISTS idx_records_first_seen_run_id ON records(first_seen_run_id)")
      s.executeUpdate("CREATE INDEX IF NOT EXISTS idx_records_last_changed_run_id ON records(last_changed_run_id)")
      c.commit()
    } finally s.close()
  }

  // All writes share one autocommit-off connection; without rollback a
  // failed batch would silently ride along with the next successful commit.
  private def commitTx[A](body: => A): A =
    try {
      val result = body
      conn.commit()
      result
    } catch {
      case e: Throwable =>
        try conn.rollback()
        catch { case re: Exception => logger.warn(s"rollback failed for $dbFile: ${re.getMessage}") }
        throw e
    }

  def beginRun(kind: String): Long = synchronized {
    commitTx {
      // Self-heal: a run still 'running' at this point was orphaned by a
      // crash or an unclosed failure path — only one run per dataset can be
      // active. Mark it failed so harvest_runs history stays trustworthy.
      val healed = markOpenRunsFailed("stale: superseded by new run")
      if (healed > 0) logger.warn(s"beginRun: marked $healed stale running run(s) failed in $dbFile")

      val sql = "INSERT INTO harvest_runs (kind, started_at, status) VALUES (?, ?, ?)"
      val ps = conn.prepareStatement(sql, java.sql.Statement.RETURN_GENERATED_KEYS)
      try {
        ps.setString(1, kind)
        ps.setString(2, nowIso())
        ps.setString(3, RUN_RUNNING)
        ps.executeUpdate()
        val rs = ps.getGeneratedKeys
        try {
          if (!rs.next()) sys.error("beginRun: no generated run_id")
          rs.getLong(1)
        } finally rs.close()
      } finally ps.close()
    }
  }

  def completeRun(runId: Long, sentCount: Int): Unit = synchronized {
    commitTx {
      val diff = computeRunDiff(runId)
      // First completed run that introduced every record it saw = the
      // baseline import of a pre-existing dataset, not "+N records added".
      val isBaseline =
        diff.added > 0 && diff.added == diff.seenTotal && !hasOtherCompletedRun(runId)
      val sql = """UPDATE harvest_runs
                      SET completed_at = ?, status = ?, seen_count = ?, changed_count = ?, deleted_count = ?, added_count = ?, sent_count = ?,
                          note = COALESCE(?, note)
                    WHERE run_id = ?"""
      val ps = conn.prepareStatement(sql)
      try {
        ps.setString(1, nowIso())
        ps.setString(2, RUN_COMPLETED)
        ps.setInt(3, diff.seenTotal)
        ps.setInt(4, diff.changed)
        ps.setInt(5, diff.deleted)
        ps.setInt(6, diff.added)
        ps.setInt(7, sentCount)
        if (isBaseline) ps.setString(8, NOTE_BASELINE) else ps.setNull(8, java.sql.Types.VARCHAR)
        ps.setLong(9, runId)
        ps.executeUpdate()
      } finally ps.close()
    }
  }

  /**
   * Resolve raw OAI tombstone ids against the id space of existing rows.
   * Candidates per raw id: the full cleaned id and the cleaned last-segment
   * (after the final ':'). Prefer a candidate that already exists as a row.
   */
  def resolveTombstoneIds(rawIds: Seq[String]): Seq[String] = synchronized {
    import record.PocketParser.cleanUpId
    val candidates: Seq[(String, String)] = rawIds.map { raw =>
      val full = cleanUpId(raw)
      val local = cleanUpId(raw.substring(raw.lastIndexOf(':') + 1))
      (full, local)
    }
    val allCandidates = candidates.flatMap { case (f, l) => Seq(f, l) }.distinct
    val existing: Set[String] =
      if (allCandidates.isEmpty) Set.empty
      else {
        val placeholders = allCandidates.map(_ => "?").mkString(",")
        val ps = conn.prepareStatement(s"SELECT local_id FROM records WHERE local_id IN ($placeholders)")
        try {
          allCandidates.zipWithIndex.foreach { case (id, i) => ps.setString(i + 1, id) }
          val rs = ps.executeQuery()
          try {
            val buf = scala.collection.mutable.Set.empty[String]
            while (rs.next()) buf += rs.getString(1)
            buf.toSet
          } finally rs.close()
        } finally ps.close()
      }
    candidates.map { case (full, local) =>
      if (existing.contains(full)) full
      else if (existing.contains(local)) local
      else full
    }
  }

  // What this run actually did, derived from the records table.
  // Caller must hold the lock.
  private def computeRunDiff(runId: Long): RunDiff = {
    def one(sql: String, bind: java.sql.PreparedStatement => Unit): Int = {
      val ps = conn.prepareStatement(sql)
      try {
        bind(ps)
        val rs = ps.executeQuery()
        try { rs.next(); rs.getInt(1) } finally rs.close()
      } finally ps.close()
    }
    val added = one(
      "SELECT COUNT(*) FROM records WHERE first_seen_run_id = ? AND status = ?",
      ps => { ps.setLong(1, runId); ps.setString(2, STATUS_SEEN) })
    val changed = one(
      "SELECT COUNT(*) FROM records WHERE last_changed_run_id = ? AND first_seen_run_id <> ? AND status = ?",
      ps => { ps.setLong(1, runId); ps.setLong(2, runId); ps.setString(3, STATUS_SEEN) })
    val deleted = one(
      "SELECT COUNT(*) FROM records WHERE status = ? AND last_seen_run_id = ?",
      ps => { ps.setString(1, STATUS_DELETED); ps.setLong(2, runId) })
    val seenTotal = one(
      "SELECT COUNT(*) FROM records WHERE status = ?",
      ps => ps.setString(1, STATUS_SEEN))
    RunDiff(added, changed, deleted, seenTotal)
  }

  private def hasOtherCompletedRun(runId: Long): Boolean = {
    val ps = conn.prepareStatement(
      "SELECT COUNT(*) FROM harvest_runs WHERE status = ? AND run_id <> ?")
    try {
      ps.setString(1, RUN_COMPLETED)
      ps.setLong(2, runId)
      val rs = ps.executeQuery()
      try { rs.next(); rs.getInt(1) > 0 } finally rs.close()
    } finally ps.close()
  }

  def listRuns(sinceDays: Int): Seq[RunSummary] = synchronized {
    val ps = conn.prepareStatement(
      """SELECT run_id, kind, started_at, completed_at, status,
                COALESCE(added_count, 0), COALESCE(changed_count, 0),
                COALESCE(deleted_count, 0), COALESCE(seen_count, 0),
                COALESCE(sent_count, 0), note
           FROM harvest_runs
          WHERE started_at >= ?
          ORDER BY run_id""")
    try {
      ps.setString(1, isoDaysAgo(sinceDays))
      val rs = ps.executeQuery()
      try {
        val buf = scala.collection.mutable.ArrayBuffer.empty[RunSummary]
        while (rs.next()) {
          buf += RunSummary(
            runId = rs.getLong(1),
            kind = rs.getString(2),
            startedAt = rs.getString(3),
            completedAt = Option(rs.getString(4)),
            status = rs.getString(5),
            added = rs.getInt(6),
            changed = rs.getInt(7),
            deleted = rs.getInt(8),
            seen = rs.getInt(9),
            sent = rs.getInt(10),
            baseline = rs.getString(11) == NOTE_BASELINE
          )
        }
        buf.toSeq
      } finally rs.close()
    } finally ps.close()
  }

  def dailyRunDiffs(sinceDays: Int): Seq[DailyRunDiff] = synchronized {
    // Baseline runs are excluded from the added sum — enabling the registry
    // on an existing dataset must not chart as "+90,000 added that day".
    val ps = conn.prepareStatement(
      """SELECT substr(started_at, 1, 10) AS day,
                SUM(CASE WHEN COALESCE(note, '') = ? THEN 0 ELSE COALESCE(added_count, 0) END),
                SUM(COALESCE(changed_count, 0)),
                SUM(COALESCE(deleted_count, 0)),
                COUNT(*)
           FROM harvest_runs
          WHERE status = ? AND started_at >= ?
          GROUP BY day
          ORDER BY day""")
    try {
      ps.setString(1, NOTE_BASELINE)
      ps.setString(2, RUN_COMPLETED)
      ps.setString(3, isoDaysAgo(sinceDays))
      val rs = ps.executeQuery()
      try {
        val buf = scala.collection.mutable.ArrayBuffer.empty[DailyRunDiff]
        while (rs.next()) {
          buf += DailyRunDiff(rs.getString(1), rs.getInt(2), rs.getInt(3), rs.getInt(4), rs.getInt(5))
        }
        buf.toSeq
      } finally rs.close()
    } finally ps.close()
  }

  // Fail every run still 'running' for this dataset. Failure paths (actor
  // WorkFailure) don't know the run id, and the FSM allows only one active
  // job per dataset, so "all open runs" is exactly "the run that just died".
  def failOpenRuns(note: String): Int = synchronized {
    commitTx {
      markOpenRunsFailed(note)
    }
  }

  // Caller must hold the lock and be inside commitTx.
  private def markOpenRunsFailed(note: String): Int = {
    val sql = """UPDATE harvest_runs
                    SET completed_at = ?, status = ?, note = ?
                  WHERE status = ?"""
    val ps = conn.prepareStatement(sql)
    try {
      ps.setString(1, nowIso())
      ps.setString(2, RUN_FAILED)
      ps.setString(3, note)
      ps.setString(4, RUN_RUNNING)
      ps.executeUpdate()
    } finally ps.close()
  }

  def latestCompletedFullRunId(): Option[Long] = synchronized {
    val ps = conn.prepareStatement(
      """SELECT run_id FROM harvest_runs
          WHERE status = ? AND kind = ?
          ORDER BY run_id DESC LIMIT 1""")
    try {
      ps.setString(1, RUN_COMPLETED)
      ps.setString(2, KIND_FULL)
      val rs = ps.executeQuery()
      try {
        if (rs.next()) Some(rs.getLong(1)) else None
      } finally rs.close()
    } finally ps.close()
  }

  def runStatus(runId: Long): Option[String] = synchronized {
    val ps = conn.prepareStatement("SELECT status FROM harvest_runs WHERE run_id = ?")
    try {
      ps.setLong(1, runId)
      val rs = ps.executeQuery()
      try {
        if (rs.next()) Option(rs.getString(1)) else None
      } finally rs.close()
    } finally ps.close()
  }

  def upsertSeen(rows: Seq[(String, String)], runId: Long): Unit = synchronized {
    commitTx {
      // last_changed_run_id moves when the content hash changes OR when a
      // tombstoned record reappears (resurrection counts as a change for the
      // per-run diff, since it is re-sent to Hub3). first_seen_run_id is set
      // once at insert and never updated.
      val sql = """INSERT INTO records
                     (local_id, content_hash, status, first_seen_run_id, last_changed_run_id,
                      last_seen_run_id, last_seen_ts, created_at, updated_at)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                   ON CONFLICT(local_id) DO UPDATE SET
                     last_changed_run_id = CASE
                       WHEN records.content_hash <> excluded.content_hash
                         OR records.status = 'deleted'
                       THEN excluded.last_seen_run_id
                       ELSE records.last_changed_run_id END,
                     content_hash     = excluded.content_hash,
                     status           = excluded.status,
                     last_seen_run_id = excluded.last_seen_run_id,
                     last_seen_ts     = excluded.last_seen_ts,
                     updated_at       = excluded.updated_at"""
      val ps = conn.prepareStatement(sql)
      try {
        val ts = nowIso()
        rows.foreach { case (localId, hash) =>
          ps.setString(1, localId)
          ps.setString(2, hash)
          ps.setString(3, STATUS_SEEN)
          ps.setLong(4, runId)
          ps.setLong(5, runId)
          ps.setLong(6, runId)
          ps.setString(7, ts)
          ps.setString(8, ts)
          ps.setString(9, ts)
          ps.addBatch()
        }
        ps.executeBatch()
      } finally ps.close()
    }
  }

  def upsertDeleted(localIds: Seq[String], runId: Long): Unit = synchronized {
    commitTx {
      // Re-stamping an already-deleted row is a no-op (the WHERE guard):
      // deleted.ids is a whole-file snapshot re-read every processing run, so
      // without the guard the same tombstones would bump last_seen_run_id
      // every run and be re-emitted as drop_records forever.
      val sql = """INSERT INTO records
                     (local_id, content_hash, status, first_seen_run_id, last_seen_run_id, last_seen_ts, created_at, updated_at)
                   VALUES (?, '', ?, ?, ?, ?, ?, ?)
                   ON CONFLICT(local_id) DO UPDATE SET
                     status           = excluded.status,
                     last_seen_run_id = excluded.last_seen_run_id,
                     last_seen_ts     = excluded.last_seen_ts,
                     updated_at       = excluded.updated_at
                   WHERE records.status <> excluded.status"""
      val ps = conn.prepareStatement(sql)
      try {
        val ts = nowIso()
        localIds.foreach { localId =>
          ps.setString(1, localId)
          ps.setString(2, STATUS_DELETED)
          ps.setLong(3, runId)
          ps.setLong(4, runId)
          ps.setString(5, ts)
          ps.setString(6, ts)
          ps.setString(7, ts)
          ps.addBatch()
        }
        ps.executeBatch()
      } finally ps.close()
    }
  }

  def markMissingForFullRun(runId: Long): Int = synchronized {
    commitTx {
      val sql = """UPDATE records
                      SET status = ?, last_seen_run_id = ?, last_seen_ts = ?, updated_at = ?
                    WHERE status = ? AND last_seen_run_id < ?"""
      val ps = conn.prepareStatement(sql)
      try {
        val ts = nowIso()
        ps.setString(1, STATUS_DELETED)
        ps.setLong(2, runId)
        ps.setString(3, ts)
        ps.setString(4, ts)
        ps.setString(5, STATUS_SEEN)
        ps.setLong(6, runId)
        ps.executeUpdate()
      } finally ps.close()
    }
  }

  def pendingIndexBatch(limit: Int): Seq[(String, String)] = synchronized {
    val sql = """SELECT local_id, content_hash FROM records
                  WHERE status = ?
                    AND (last_sent_hash IS NULL OR last_sent_hash <> content_hash)
                  LIMIT ?"""
    val ps = conn.prepareStatement(sql)
    try {
      ps.setString(1, STATUS_SEEN)
      ps.setInt(2, limit)
      val rs = ps.executeQuery()
      try {
        val buf = scala.collection.mutable.ArrayBuffer.empty[(String, String)]
        while (rs.next()) buf += ((rs.getString(1), rs.getString(2)))
        buf.toSeq
      } finally rs.close()
    } finally ps.close()
  }

  def pendingDropBatch(limit: Int): Seq[String] = synchronized {
    val sql = """SELECT local_id FROM records
                  WHERE status = ?
                    AND (last_sent_run_id IS NULL OR last_sent_run_id < last_seen_run_id)
                  LIMIT ?"""
    val ps = conn.prepareStatement(sql)
    try {
      ps.setString(1, STATUS_DELETED)
      ps.setInt(2, limit)
      val rs = ps.executeQuery()
      try {
        val buf = scala.collection.mutable.ArrayBuffer.empty[String]
        while (rs.next()) buf += rs.getString(1)
        buf.toSeq
      } finally rs.close()
    } finally ps.close()
  }

  def confirmIndexed(rows: Seq[(String, String)], runId: Long): Unit = synchronized {
    commitTx {
      val sql = """UPDATE records
                      SET last_sent_hash = ?, last_sent_run_id = ?, updated_at = ?
                    WHERE local_id = ?"""
      val ps = conn.prepareStatement(sql)
      try {
        val ts = nowIso()
        rows.foreach { case (localId, hash) =>
          ps.setString(1, hash)
          ps.setLong(2, runId)
          ps.setString(3, ts)
          ps.setString(4, localId)
          ps.addBatch()
        }
        ps.executeBatch()
      } finally ps.close()
    }
  }

  def confirmIndexedByIds(localIds: Seq[String], runId: Long): Unit = synchronized {
    commitTx {
      // Mark each id as synced at its current content_hash — the caller just
      // sent that exact version to Hub3, so last_sent_hash := content_hash.
      val sql = """UPDATE records
                      SET last_sent_hash = content_hash, last_sent_run_id = ?, updated_at = ?
                    WHERE local_id = ?"""
      val ps = conn.prepareStatement(sql)
      try {
        val ts = nowIso()
        localIds.foreach { id =>
          ps.setLong(1, runId)
          ps.setString(2, ts)
          ps.setString(3, id)
          ps.addBatch()
        }
        val updated = ps.executeBatch().filter(_ > 0).sum
        // 0-row updates mean the confirm ids (derived from graph URIs) do not
        // match the registry keys (pocket ids) — id-space drift, shout early.
        if (updated < localIds.size)
          logger.warn(s"confirmIndexedByIds matched $updated of ${localIds.size} ids in $dbFile — id mismatch between graph URIs and registry keys?")
      } finally ps.close()
    }
  }

  def confirmDropped(localIds: Seq[String]): Unit = synchronized {
    commitTx {
      // Keep the tombstone row and mark it sent instead of deleting it:
      // a deleted row would be re-inserted from the stale deleted.ids file on
      // the next run and re-dropped forever. The kept row records "absent in
      // Hub3"; if the record reappears, upsertSeen flips it back and the
      // status change makes the next upsertDeleted pending again.
      val sql = """UPDATE records
                      SET last_sent_run_id = last_seen_run_id, updated_at = ?
                    WHERE local_id = ? AND status = ?"""
      val ps = conn.prepareStatement(sql)
      try {
        val ts = nowIso()
        localIds.foreach { id =>
          ps.setString(1, ts)
          ps.setString(2, id)
          ps.setString(3, STATUS_DELETED)
          ps.addBatch()
        }
        ps.executeBatch()
      } finally ps.close()
    }
  }

  def allTombstonesSynced(localIds: Seq[String]): Boolean = synchronized {
    if (localIds.isEmpty) return true
    val placeholders = localIds.map(_ => "?").mkString(",")
    val ps = conn.prepareStatement(
      s"""SELECT COUNT(*) FROM records
           WHERE local_id IN ($placeholders)
             AND status = '$STATUS_DELETED'
             AND last_sent_run_id IS NOT NULL
             AND last_sent_run_id >= last_seen_run_id""")
    try {
      localIds.zipWithIndex.foreach { case (id, i) => ps.setString(i + 1, id) }
      val rs = ps.executeQuery()
      try { rs.next(); rs.getInt(1) == localIds.size } finally rs.close()
    } finally ps.close()
  }

  def pendingCounts(): PendingCounts = synchronized {
    def one(sql: String): Int = {
      val ps = conn.prepareStatement(sql)
      try {
        val rs = ps.executeQuery()
        try { rs.next(); rs.getInt(1) } finally rs.close()
      } finally ps.close()
    }
    PendingCounts(
      pendingIndex = one(
        s"""SELECT COUNT(*) FROM records
             WHERE status = '$STATUS_SEEN'
               AND (last_sent_hash IS NULL OR last_sent_hash <> content_hash)"""),
      pendingDrops = one(
        s"""SELECT COUNT(*) FROM records
             WHERE status = '$STATUS_DELETED'
               AND (last_sent_run_id IS NULL OR last_sent_run_id < last_seen_run_id)""")
    )
  }

  def count(status: String): Int = synchronized {
    val ps = conn.prepareStatement("SELECT COUNT(*) FROM records WHERE status = ?")
    try {
      ps.setString(1, status)
      val rs = ps.executeQuery()
      try {
        rs.next()
        rs.getInt(1)
      } finally rs.close()
    } finally ps.close()
  }

  def close(): Unit = synchronized {
    try if (!conn.isClosed) conn.close()
    catch { case e: Exception => logger.warn(s"close $dbFile: ${e.getMessage}") }
  }

  def closeAndDelete(): Unit = {
    close()
    SpecRegistry.deleteDbFiles(datasetDir)
  }
}
