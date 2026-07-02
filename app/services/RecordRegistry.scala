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
  val SCHEMA_VERSION = "1"
  val DB_FILENAME = "records.db"

  val STATUS_SEEN    = "seen"
  val STATUS_DELETED = "deleted"

  val RUN_RUNNING   = "running"
  val RUN_COMPLETED = "completed"
  val RUN_FAILED    = "failed"

  val KIND_FULL      = "full"
  val KIND_INCREMENT = "incremental"
  val KIND_FSI       = "from_scratch_incremental"

  case class RunCounts(seen: Int, changed: Int, deleted: Int)

  private val TS_FORMAT =
    DateTimeFormatter.ISO_INSTANT.withZone(ZoneOffset.UTC)

  def nowIso(): String = TS_FORMAT.format(Instant.now())
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

  def completeRun(specName: String, runId: Long, counts: RunCounts): Unit =
    spec(specName).completeRun(runId, counts)

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
      s.executeUpdate("""CREATE TABLE IF NOT EXISTS records (
        local_id          TEXT PRIMARY KEY,
        content_hash      TEXT NOT NULL,
        status            TEXT NOT NULL,
        last_seen_run_id  INTEGER NOT NULL,
        last_seen_ts      TEXT NOT NULL,
        last_sent_hash    TEXT,
        last_sent_run_id  INTEGER,
        created_at        TEXT NOT NULL,
        updated_at        TEXT NOT NULL
      )""")
      s.executeUpdate("CREATE INDEX IF NOT EXISTS idx_records_status ON records(status)")
      s.executeUpdate("CREATE INDEX IF NOT EXISTS idx_records_last_seen_run_id ON records(last_seen_run_id)")
      s.executeUpdate("""CREATE TABLE IF NOT EXISTS harvest_runs (
        run_id        INTEGER PRIMARY KEY AUTOINCREMENT,
        kind          TEXT NOT NULL,
        started_at    TEXT NOT NULL,
        completed_at  TEXT,
        status        TEXT NOT NULL,
        seen_count    INTEGER,
        changed_count INTEGER,
        deleted_count INTEGER,
        note          TEXT
      )""")
      s.executeUpdate(
        s"INSERT OR IGNORE INTO schema_meta (k, v) VALUES ('schema_version', '$SCHEMA_VERSION')"
      )
      c.commit()
      // Fail loud on version drift instead of running new queries on an old
      // schema; a future v2 adds its migration steps above before this check.
      val rs = s.executeQuery("SELECT v FROM schema_meta WHERE k = 'schema_version'")
      try {
        if (rs.next()) {
          val found = rs.getString(1)
          if (found != SCHEMA_VERSION)
            sys.error(s"records.db schema_version $found, expected $SCHEMA_VERSION: ${datasetDir.getAbsolutePath}")
        }
      } finally rs.close()
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

  def completeRun(runId: Long, counts: RunCounts): Unit = synchronized {
    commitTx {
      val sql = """UPDATE harvest_runs
                      SET completed_at = ?, status = ?, seen_count = ?, changed_count = ?, deleted_count = ?
                    WHERE run_id = ?"""
      val ps = conn.prepareStatement(sql)
      try {
        ps.setString(1, nowIso())
        ps.setString(2, RUN_COMPLETED)
        ps.setInt(3, counts.seen)
        ps.setInt(4, counts.changed)
        ps.setInt(5, counts.deleted)
        ps.setLong(6, runId)
        ps.executeUpdate()
      } finally ps.close()
    }
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
      val sql = """INSERT INTO records
                     (local_id, content_hash, status, last_seen_run_id, last_seen_ts, created_at, updated_at)
                   VALUES (?, ?, ?, ?, ?, ?, ?)
                   ON CONFLICT(local_id) DO UPDATE SET
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
          ps.setString(5, ts)
          ps.setString(6, ts)
          ps.setString(7, ts)
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
                     (local_id, content_hash, status, last_seen_run_id, last_seen_ts, created_at, updated_at)
                   VALUES (?, '', ?, ?, ?, ?, ?)
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
          ps.setString(4, ts)
          ps.setString(5, ts)
          ps.setString(6, ts)
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
