//===========================================================================
//    Copyright 2026 Delving B.V.
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

package services

import java.io.File
import java.sql.{Connection, DriverManager}
import java.time.{Instant, ZoneOffset}
import java.time.format.DateTimeFormatter

import org.joda.time.DateTime
import play.api.Logger
import play.api.libs.json.{JsObject, Json}

/**
 * Org-level persistent job queue + execution lease (Phases A3b-1/2).
 *
 * Replaces OrgActor's in-memory Vector[QueuedOperation] AND both
 * Semaphores: queued work survives restarts, and "at most one running job
 * per dataset, at most concurrencyLimit overall" is enforced by the store
 * (partial unique index on leased specs) instead of ~25 hand-placed
 * release calls. The table is readable by a future Go orchestrator.
 *
 * Lease lifecycle: taken at dispatch (tryLease/leaseQueued), freed by
 * OrgActor on DatasetBecameIdle / actor termination, reclaimed by the
 * periodic check when stale with no active work, cleared at startup.
 *
 * Priority: manual jobs (priority 0) before periodic/retry/recovery
 * (priority 1), insertion order within a class.
 */
object JobQueue {
  case class QueuedJob(jobId: Long, spec: String, payload: String, trigger: String)

  private val TS_FORMAT = DateTimeFormatter.ISO_INSTANT.withZone(ZoneOffset.UTC)
  private def nowIso(): String = TS_FORMAT.format(Instant.now())
}

class JobQueue(dbFile: File) {

  import JobQueue._

  private val logger = Logger(getClass)

  Class.forName("org.sqlite.JDBC")
  dbFile.getParentFile.mkdirs()

  private val conn: Connection = {
    val c = DriverManager.getConnection(s"jdbc:sqlite:${dbFile.getAbsolutePath}")
    c.setAutoCommit(true)
    val s = c.createStatement()
    try {
      s.executeUpdate("PRAGMA journal_mode=WAL")
      s.executeUpdate("PRAGMA synchronous=NORMAL")
      s.executeUpdate("PRAGMA busy_timeout=5000")
      s.executeUpdate("""CREATE TABLE IF NOT EXISTS jobs (
        job_id      INTEGER PRIMARY KEY AUTOINCREMENT,
        spec        TEXT NOT NULL,
        payload     TEXT NOT NULL,
        job_trigger TEXT NOT NULL,
        priority    INTEGER NOT NULL,
        status      TEXT NOT NULL DEFAULT 'queued',
        enqueued_at TEXT NOT NULL,
        leased_at   TEXT
      )""")
      try s.executeUpdate("ALTER TABLE jobs ADD COLUMN leased_at TEXT")
      catch { case _: Exception => /* column exists (pre-lease dev db) */ }
      s.executeUpdate("CREATE INDEX IF NOT EXISTS idx_jobs_spec ON jobs(spec)")
      // THE concurrency invariant: at most one running job per dataset,
      // enforced by the store instead of hand-placed semaphore calls.
      s.executeUpdate(
        "CREATE UNIQUE INDEX IF NOT EXISTS idx_jobs_leased_spec ON jobs(spec) WHERE status = 'leased'")
    } finally s.close()
    c
  }

  private def priorityFor(trigger: String): Int = if (trigger == "manual") 0 else 1

  /** Enqueue unless the spec already has a queued job. Returns true if enqueued. */
  def enqueue(spec: String, payload: String, trigger: String): Boolean = synchronized {
    if (isQueued(spec)) false
    else {
      val ps = conn.prepareStatement(
        "INSERT INTO jobs (spec, payload, job_trigger, priority, enqueued_at) VALUES (?, ?, ?, ?, ?)")
      try {
        ps.setString(1, spec)
        ps.setString(2, payload)
        ps.setString(3, trigger)
        ps.setInt(4, priorityFor(trigger))
        ps.setString(5, nowIso())
        ps.executeUpdate()
        true
      } finally ps.close()
    }
  }

  def isQueued(spec: String): Boolean = synchronized {
    val ps = conn.prepareStatement("SELECT 1 FROM jobs WHERE spec = ? AND status = 'queued' LIMIT 1")
    try {
      ps.setString(1, spec)
      val rs = ps.executeQuery()
      try rs.next() finally rs.close()
    } finally ps.close()
  }

  // === Lease semantics (Phase A3b-2): replaces the two Semaphores ===

  /**
   * Try to lease execution for a spec directly (dispatch-now path).
   * Fails when the spec already holds a lease or capacity is exhausted.
   */
  def tryLease(spec: String, payload: String, trigger: String, limit: Int): Boolean = synchronized {
    if (isLeased(spec) || leasedCount() >= limit) false
    else {
      val ps = conn.prepareStatement(
        "INSERT INTO jobs (spec, payload, job_trigger, priority, status, enqueued_at, leased_at) VALUES (?, ?, ?, ?, 'leased', ?, ?)")
      try {
        val now = nowIso()
        ps.setString(1, spec)
        ps.setString(2, payload)
        ps.setString(3, trigger)
        ps.setInt(4, priorityFor(trigger))
        ps.setString(5, now)
        ps.setString(6, now)
        ps.executeUpdate()
        true
      } finally ps.close()
    }
  }

  /** Promote a queued job to leased (queue-drain path). */
  def leaseQueued(jobId: Long, spec: String, limit: Int): Boolean = synchronized {
    if (isLeased(spec) || leasedCount() >= limit) false
    else {
      val ps = conn.prepareStatement(
        "UPDATE jobs SET status = 'leased', leased_at = ? WHERE job_id = ? AND status = 'queued'")
      try {
        ps.setString(1, nowIso())
        ps.setLong(2, jobId)
        ps.executeUpdate() > 0
      } finally ps.close()
    }
  }

  /** Release the spec's lease (job finished, however it finished). */
  def releaseLease(spec: String): Boolean = synchronized {
    val ps = conn.prepareStatement("DELETE FROM jobs WHERE spec = ? AND status = 'leased'")
    try {
      ps.setString(1, spec)
      ps.executeUpdate() > 0
    } finally ps.close()
  }

  def isLeased(spec: String): Boolean = synchronized {
    val ps = conn.prepareStatement("SELECT 1 FROM jobs WHERE spec = ? AND status = 'leased' LIMIT 1")
    try {
      ps.setString(1, spec)
      val rs = ps.executeQuery()
      try rs.next() finally rs.close()
    } finally ps.close()
  }

  def leasedCount(): Int = synchronized {
    val s = conn.createStatement()
    try {
      val rs = s.executeQuery("SELECT COUNT(*) FROM jobs WHERE status = 'leased'")
      try { rs.next(); rs.getInt(1) } finally rs.close()
    } finally s.close()
  }

  def leasedSpecs(): Seq[String] = synchronized {
    val s = conn.createStatement()
    try {
      val rs = s.executeQuery("SELECT spec FROM jobs WHERE status = 'leased' ORDER BY leased_at")
      try {
        val buf = scala.collection.mutable.ArrayBuffer.empty[String]
        while (rs.next()) buf += rs.getString(1)
        buf.toSeq
      } finally rs.close()
    } finally s.close()
  }

  /**
   * Reclaim leases older than the timeout whose dataset is NOT actually
   * active — the safety net for lost completion signals (replaces the
   * stuck-state force-release). Returns the reclaimed specs.
   */
  def reclaimStaleLeases(olderThanMinutes: Int, activeSpecs: Set[String]): Seq[String] = synchronized {
    val cutoff = TS_FORMAT.format(Instant.now().minus(java.time.Duration.ofMinutes(olderThanMinutes.toLong)))
    val stale = {
      val ps = conn.prepareStatement(
        "SELECT spec FROM jobs WHERE status = 'leased' AND leased_at < ?")
      try {
        ps.setString(1, cutoff)
        val rs = ps.executeQuery()
        try {
          val buf = scala.collection.mutable.ArrayBuffer.empty[String]
          while (rs.next()) buf += rs.getString(1)
          buf.toSeq
        } finally rs.close()
      } finally ps.close()
    }
    val reclaimable = stale.filterNot(activeSpecs.contains)
    reclaimable.foreach(releaseLease)
    reclaimable
  }

  /** Startup: leases from a previous JVM are meaningless — drop them. */
  def clearLeases(): Int = synchronized {
    val s = conn.createStatement()
    try s.executeUpdate("DELETE FROM jobs WHERE status = 'leased'")
    finally s.close()
  }

  /** All queued jobs in dispatch order (manual first, then insertion order). */
  def queued(): Seq[QueuedJob] = synchronized {
    val ps = conn.prepareStatement(
      "SELECT job_id, spec, payload, job_trigger FROM jobs WHERE status = 'queued' ORDER BY priority, job_id")
    try {
      val rs = ps.executeQuery()
      try {
        val buf = scala.collection.mutable.ArrayBuffer.empty[QueuedJob]
        while (rs.next()) buf += QueuedJob(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4))
        buf.toSeq
      } finally rs.close()
    } finally ps.close()
  }

  def remove(jobId: Long): Unit = synchronized {
    val ps = conn.prepareStatement("DELETE FROM jobs WHERE job_id = ?")
    try { ps.setLong(1, jobId); ps.executeUpdate() } finally ps.close()
  }

  /** Remove any QUEUED job for the spec (never touches a live lease). */
  def removeSpec(spec: String): Boolean = synchronized {
    val ps = conn.prepareStatement("DELETE FROM jobs WHERE spec = ? AND status = 'queued'")
    try {
      ps.setString(1, spec)
      ps.executeUpdate() > 0
    } finally ps.close()
  }

  def size(): Int = synchronized {
    val s = conn.createStatement()
    try {
      val rs = s.executeQuery("SELECT COUNT(*) FROM jobs WHERE status = 'queued'")
      try { rs.next(); rs.getInt(1) } finally rs.close()
    } finally s.close()
  }

  def close(): Unit = synchronized {
    try if (!conn.isClosed) conn.close()
    catch { case e: Exception => logger.warn(s"close $dbFile: ${e.getMessage}") }
  }
}

/**
 * Typed JSON codec for the queueable DatasetActor messages. The queue must
 * survive restarts, so messages are data, not object references. Decode
 * failures return None — the caller drops the row with a warning rather
 * than crashing the queue.
 */
object JobPayload {
  import dataset.DatasetActor._

  def encode(message: AnyRef): Option[String] = {
    val obj: Option[JsObject] = message match {
      case Command(name) =>
        Some(Json.obj("type" -> "command", "name" -> name))
      case StartHarvest(strategy, trigger) =>
        val strategyObj = strategy match {
          case ModifiedAfter(mod, justDate) =>
            Json.obj("strategy" -> "modifiedAfter", "modifiedAfter" -> mod.toString, "justDate" -> justDate)
          case FromScratch(autoProcess) =>
            Json.obj("strategy" -> "fromScratch", "autoProcess" -> autoProcess)
          case FromScratchIncremental =>
            Json.obj("strategy" -> "fromScratchIncremental")
          case Sample =>
            Json.obj("strategy" -> "sample")
        }
        Some(Json.obj("type" -> "harvest", "trigger" -> trigger) ++ strategyObj)
      case StartProcessing(None) =>
        Some(Json.obj("type" -> "process"))
      case StartSaving(None) =>
        Some(Json.obj("type" -> "save"))
      case adopt: record.SourceProcessor.AdoptSource =>
        Some(Json.obj("type" -> "adopt", "file" -> adopt.file.getAbsolutePath))
      case _ => None
    }
    obj.map(Json.stringify)
  }

  def decode(json: String, orgContext: organization.OrgContext): Option[AnyRef] =
    scala.util.Try {
      val js = Json.parse(json)
      (js \ "type").as[String] match {
        case "command" => Command((js \ "name").as[String])
        case "harvest" =>
          val trigger = (js \ "trigger").asOpt[String].getOrElse("manual")
          val strategy = (js \ "strategy").as[String] match {
            case "modifiedAfter" =>
              ModifiedAfter(DateTime.parse((js \ "modifiedAfter").as[String]), (js \ "justDate").as[Boolean])
            case "fromScratch" =>
              FromScratch((js \ "autoProcess").asOpt[Boolean].getOrElse(false))
            case "fromScratchIncremental" => FromScratchIncremental
            case "sample" => Sample
          }
          StartHarvest(strategy, trigger)
        case "process" => StartProcessing(None)
        case "save" => StartSaving(None)
        case "adopt" =>
          record.SourceProcessor.AdoptSource(new File((js \ "file").as[String]), orgContext)
      }
    }.toOption
}
