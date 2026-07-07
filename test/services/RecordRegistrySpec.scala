package services

import java.io.File

import org.apache.commons.io.FileUtils
import org.scalatest.BeforeAndAfterEach
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import services.RecordRegistry._

class RecordRegistrySpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach {

  private val spec = "testspec"
  private var tempDir: File = _
  private var registry: RecordRegistry = _

  override def beforeEach(): Unit = {
    tempDir = java.nio.file.Files.createTempDirectory("narthex-registry-test-").toFile
    registry = new RecordRegistry(tempDir)
  }

  override def afterEach(): Unit = {
    if (registry != null) registry.close()
    if (tempDir != null) FileUtils.deleteQuietly(tempDir)
  }

  "RecordRegistry" should "create the per-dataset db file on first access" in {
    val run = registry.beginRun(spec, KIND_FULL)
    run should be > 0L
    new File(new File(tempDir, spec), DB_FILENAME).exists() shouldBe true
  }

  it should "report every upsertSeen row as pending on first run" in {
    val run = registry.beginRun(spec, KIND_FULL)
    registry.upsertSeenBatch(spec, Seq("a" -> "h1", "b" -> "h2", "c" -> "h3"), run)
    val pending = registry.pendingIndexBatch(spec, 100).toSet
    pending shouldBe Set("a" -> "h1", "b" -> "h2", "c" -> "h3")
  }

  it should "drop a record from pendingIndex once confirmed at the same hash" in {
    val run = registry.beginRun(spec, KIND_FULL)
    registry.upsertSeenBatch(spec, Seq("a" -> "h1"), run)
    registry.confirmIndexed(spec, Seq("a" -> "h1"), run)
    registry.pendingIndexBatch(spec, 100) shouldBe empty
  }

  it should "re-surface a record in pendingIndex when its hash changes" in {
    val run1 = registry.beginRun(spec, KIND_FULL)
    registry.upsertSeenBatch(spec, Seq("a" -> "h1"), run1)
    registry.confirmIndexed(spec, Seq("a" -> "h1"), run1)
    registry.completeRun(spec, run1)

    val run2 = registry.beginRun(spec, KIND_INCREMENT)
    registry.upsertSeenBatch(spec, Seq("a" -> "h1"), run2)       // unchanged
    registry.pendingIndexBatch(spec, 100) shouldBe empty

    registry.upsertSeenBatch(spec, Seq("a" -> "h2"), run2)       // edited
    registry.pendingIndexBatch(spec, 100).toSet shouldBe Set("a" -> "h2")
  }

  it should "surface explicit tombstones via pendingDropBatch" in {
    val run1 = registry.beginRun(spec, KIND_FULL)
    registry.upsertSeenBatch(spec, Seq("a" -> "h1"), run1)
    registry.confirmIndexed(spec, Seq("a" -> "h1"), run1)

    val run2 = registry.beginRun(spec, KIND_INCREMENT)
    registry.upsertDeleted(spec, "a", run2)
    registry.pendingDropBatch(spec, 100) shouldBe Seq("a")
  }

  it should "keep the tombstone row but clear pendingDrop once a drop is confirmed" in {
    val run = registry.beginRun(spec, KIND_INCREMENT)
    registry.upsertDeleted(spec, "a", run)
    registry.pendingDropBatch(spec, 100) shouldBe Seq("a")
    registry.confirmDropped(spec, Seq("a"))
    registry.pendingDropBatch(spec, 100) shouldBe empty
    registry.count(spec, STATUS_DELETED) shouldBe 1
    registry.count(spec, STATUS_SEEN) shouldBe 0
  }

  it should "not re-emit a drop when a stale tombstone is re-stamped" in {
    val run1 = registry.beginRun(spec, KIND_INCREMENT)
    registry.upsertDeleted(spec, "a", run1)
    registry.confirmDropped(spec, Seq("a"))

    // deleted.ids is a whole-file snapshot: the same id gets re-stamped on
    // every subsequent processing run and must stay a no-op
    val run2 = registry.beginRun(spec, KIND_FULL)
    registry.upsertDeleted(spec, "a", run2)
    registry.pendingDropBatch(spec, 100) shouldBe empty
  }

  it should "re-emit a drop when a reappeared record is deleted again" in {
    val run1 = registry.beginRun(spec, KIND_INCREMENT)
    registry.upsertDeleted(spec, "a", run1)
    registry.confirmDropped(spec, Seq("a"))

    val run2 = registry.beginRun(spec, KIND_INCREMENT)
    registry.upsertSeenBatch(spec, Seq("a" -> "h2"), run2)

    val run3 = registry.beginRun(spec, KIND_INCREMENT)
    registry.upsertDeleted(spec, "a", run3)
    registry.pendingDropBatch(spec, 100) shouldBe Seq("a")
  }

  it should "mark unseen records deleted on a full run sweep" in {
    val run1 = registry.beginRun(spec, KIND_FULL)
    registry.upsertSeenBatch(spec, Seq("a" -> "h1", "b" -> "h2", "c" -> "h3"), run1)
    registry.confirmIndexed(spec, Seq("a" -> "h1", "b" -> "h2", "c" -> "h3"), run1)
    registry.completeRun(spec, run1)

    // New full run: source no longer contains "b"
    val run2 = registry.beginRun(spec, KIND_FULL)
    registry.upsertSeenBatch(spec, Seq("a" -> "h1", "c" -> "h3"), run2)
    val swept = registry.markMissingForFullRun(spec, run2)
    swept shouldBe 1

    registry.pendingDropBatch(spec, 100) shouldBe Seq("b")
    registry.pendingIndexBatch(spec, 100) shouldBe empty   // a, c still at same hash
  }

  it should "not mark unseen records deleted on an incremental run" in {
    val run1 = registry.beginRun(spec, KIND_FULL)
    registry.upsertSeenBatch(spec, Seq("a" -> "h1", "b" -> "h2"), run1)
    registry.confirmIndexed(spec, Seq("a" -> "h1", "b" -> "h2"), run1)
    registry.completeRun(spec, run1)

    // Incremental run touches only "a" (edit)
    val run2 = registry.beginRun(spec, KIND_INCREMENT)
    registry.upsertSeenBatch(spec, Seq("a" -> "h1b"), run2)
    // No sweep for incremental
    registry.pendingDropBatch(spec, 100) shouldBe empty
    registry.pendingIndexBatch(spec, 100).toSet shouldBe Set("a" -> "h1b")
  }

  it should "restore a previously-tombstoned record when it reappears seen" in {
    val run1 = registry.beginRun(spec, KIND_INCREMENT)
    registry.upsertDeleted(spec, "a", run1)
    registry.confirmDropped(spec, Seq("a"))

    val run2 = registry.beginRun(spec, KIND_INCREMENT)
    registry.upsertSeenBatch(spec, Seq("a" -> "hNew"), run2)
    registry.pendingIndexBatch(spec, 100).toSet shouldBe Set("a" -> "hNew")
    registry.count(spec, STATUS_SEEN) shouldBe 1
  }

  it should "isolate state per spec" in {
    val rA = registry.beginRun("dsA", KIND_FULL)
    val rB = registry.beginRun("dsB", KIND_FULL)
    registry.upsertSeenBatch("dsA", Seq("x" -> "ha"), rA)
    registry.upsertSeenBatch("dsB", Seq("x" -> "hb"), rB)

    registry.pendingIndexBatch("dsA", 100).toSet shouldBe Set("x" -> "ha")
    registry.pendingIndexBatch("dsB", 100).toSet shouldBe Set("x" -> "hb")

    new File(new File(tempDir, "dsA"), DB_FILENAME).exists() shouldBe true
    new File(new File(tempDir, "dsB"), DB_FILENAME).exists() shouldBe true
  }

  it should "drop the db file when dropDatasetDb is called" in {
    val run = registry.beginRun(spec, KIND_FULL)
    registry.upsertSeenBatch(spec, Seq("a" -> "h1"), run)
    val dbFile = new File(new File(tempDir, spec), DB_FILENAME)
    dbFile.exists() shouldBe true

    registry.dropDatasetDb(spec)
    dbFile.exists() shouldBe false
  }

  it should "persist state across close and reopen" in {
    val run = registry.beginRun(spec, KIND_FULL)
    registry.upsertSeenBatch(spec, Seq("a" -> "h1"), run)
    registry.confirmIndexed(spec, Seq("a" -> "h1"), run)
    registry.completeRun(spec, run)
    registry.close()

    registry = new RecordRegistry(tempDir)
    val run2 = registry.beginRun(spec, KIND_FULL)
    registry.upsertSeenBatch(spec, Seq("a" -> "h1"), run2)    // same hash
    registry.pendingIndexBatch(spec, 100) shouldBe empty
  }

  it should "mark rows synced at their current hash via confirmIndexedByIds" in {
    val run1 = registry.beginRun(spec, KIND_FULL)
    registry.upsertSeenBatch(spec, Seq("a" -> "h1", "b" -> "h2"), run1)
    registry.confirmIndexedByIds(spec, Seq("a", "b"), run1)
    registry.pendingIndexBatch(spec, 100) shouldBe empty

    val run2 = registry.beginRun(spec, KIND_INCREMENT)
    registry.upsertSeenBatch(spec, Seq("a" -> "h1b"), run2)
    registry.pendingIndexBatch(spec, 100).toSet shouldBe Set("a" -> "h1b")
  }

  it should "mark open runs failed via failOpenRuns" in {
    val run = registry.beginRun(spec, KIND_FULL)
    registry.runStatus(spec, run) shouldBe Some(RUN_RUNNING)

    registry.failOpenRuns(spec, "boom") shouldBe 1
    registry.runStatus(spec, run) shouldBe Some(RUN_FAILED)

    // completed runs are untouched
    val run2 = registry.beginRun(spec, KIND_FULL)
    registry.completeRun(spec, run2)
    registry.failOpenRuns(spec, "boom again") shouldBe 0
    registry.runStatus(spec, run2) shouldBe Some(RUN_COMPLETED)
  }

  it should "self-heal stale running runs on beginRun" in {
    val stale = registry.beginRun(spec, KIND_FULL)   // never closed (crash)
    val fresh = registry.beginRun(spec, KIND_FULL)
    registry.runStatus(spec, stale) shouldBe Some(RUN_FAILED)
    registry.runStatus(spec, fresh) shouldBe Some(RUN_RUNNING)
  }

  it should "respect the limit on pendingIndexBatch" in {
    val run = registry.beginRun(spec, KIND_FULL)
    registry.upsertSeenBatch(spec, (1 to 50).map(i => s"id$i" -> s"h$i"), run)
    registry.pendingIndexBatch(spec, 10).size shouldBe 10
    registry.pendingIndexBatch(spec, 100).size shouldBe 50
  }

  // === Phase 2: per-run diffs ===

  it should "mark the first full run as baseline, not as added" in {
    val run1 = registry.beginRun(spec, KIND_FULL)
    registry.upsertSeenBatch(spec, Seq("a" -> "h1", "b" -> "h2", "c" -> "h3"), run1)
    registry.completeRun(spec, run1)

    val runs = registry.listRuns(spec, 30)
    runs.size shouldBe 1
    runs.head.baseline shouldBe true
    runs.head.added shouldBe 3
    runs.head.seen shouldBe 3

    // Baseline excluded from daily diff sums
    val diffs = registry.dailyRunDiffs(spec, 30)
    diffs.size shouldBe 1
    diffs.head.added shouldBe 0
    diffs.head.runs shouldBe 1
  }

  it should "compute real added/changed/deleted per run" in {
    val run1 = registry.beginRun(spec, KIND_FULL)
    registry.upsertSeenBatch(spec, Seq("a" -> "h1", "b" -> "h2", "c" -> "h3"), run1)
    registry.completeRun(spec, run1)

    // Run 2: d added, a changed, b unchanged, c deleted via sweep
    val run2 = registry.beginRun(spec, KIND_FULL)
    registry.upsertSeenBatch(spec, Seq("a" -> "h1x", "b" -> "h2", "d" -> "h4"), run2)
    registry.markMissingForFullRun(spec, run2)
    registry.completeRun(spec, run2)

    val runs = registry.listRuns(spec, 30)
    runs.size shouldBe 2
    val r2 = runs.last
    r2.baseline shouldBe false
    r2.added shouldBe 1     // d
    r2.changed shouldBe 1   // a (hash change); b unchanged
    r2.deleted shouldBe 1   // c (sweep)
    r2.seen shouldBe 3      // a, b, d

    val diffs = registry.dailyRunDiffs(spec, 30)
    diffs.size shouldBe 1   // both runs today
    diffs.head.added shouldBe 1   // baseline run contributes 0
    diffs.head.changed shouldBe 1
    diffs.head.deleted shouldBe 1
    diffs.head.runs shouldBe 2
  }

  it should "count a resurrected record as changed, not added" in {
    val run1 = registry.beginRun(spec, KIND_INCREMENT)
    registry.upsertSeenBatch(spec, Seq("a" -> "h1", "b" -> "h2"), run1)
    registry.completeRun(spec, run1)

    val run2 = registry.beginRun(spec, KIND_INCREMENT)
    registry.upsertDeleted(spec, "a", run2)
    registry.confirmDropped(spec, Seq("a"))
    registry.completeRun(spec, run2)

    val run3 = registry.beginRun(spec, KIND_INCREMENT)
    registry.upsertSeenBatch(spec, Seq("a" -> "h1"), run3)   // reappears, same hash
    registry.completeRun(spec, run3)

    val r3 = registry.listRuns(spec, 30).last
    r3.added shouldBe 0
    r3.changed shouldBe 1
    r3.deleted shouldBe 0
  }

  it should "return the latest completed FULL run for manual-save adoption" in {
    registry.latestCompletedFullRunId(spec) shouldBe None   // no db yet

    val full1 = registry.beginRun(spec, KIND_FULL)
    registry.completeRun(spec, full1)
    val inc = registry.beginRun(spec, KIND_INCREMENT)
    registry.completeRun(spec, inc)
    val full2 = registry.beginRun(spec, KIND_FULL)
    registry.completeRun(spec, full2)
    val open = registry.beginRun(spec, KIND_FULL)           // still running

    // Latest completed FULL, never the incremental or the open run
    registry.latestCompletedFullRunId(spec) shouldBe Some(full2)
    registry.runStatus(spec, open) shouldBe Some(RUN_RUNNING)
  }

  it should "return empty run lists without creating a db for unknown specs" in {
    registry.listRuns("never-seen", 30) shouldBe empty
    registry.dailyRunDiffs("never-seen", 30) shouldBe empty
    new File(new File(tempDir, "never-seen"), DB_FILENAME).exists() shouldBe false
  }

  it should "report tombstones synced only when deleted and sent" in {
    registry.allTombstonesSynced(spec, Seq.empty) shouldBe true
    registry.allTombstonesSynced(spec, Seq("a")) shouldBe false   // no db/rows yet

    val run = registry.beginRun(spec, KIND_INCREMENT)
    registry.upsertDeleted(spec, "a", run)
    registry.allTombstonesSynced(spec, Seq("a")) shouldBe false   // pending drop
    registry.confirmDropped(spec, Seq("a"))
    registry.allTombstonesSynced(spec, Seq("a")) shouldBe true    // dropped + sent
    registry.allTombstonesSynced(spec, Seq("a", "b")) shouldBe false  // b unknown
  }

  it should "re-pend everything after resetSentState (Hub3 wipe escape hatch)" in {
    registry.resetSentState("never-seen") shouldBe 0   // no db, no-op

    val run = registry.beginRun(spec, KIND_FULL)
    registry.upsertSeenBatch(spec, Seq("a" -> "h1", "b" -> "h2"), run)
    registry.confirmIndexedByIds(spec, Seq("a", "b"), run)
    registry.upsertDeleted(spec, "t", run)
    registry.confirmDropped(spec, Seq("t"))
    registry.pendingIndexBatch(spec, 100) shouldBe empty
    registry.pendingDropBatch(spec, 100) shouldBe empty

    registry.resetSentState(spec) shouldBe 3
    registry.pendingIndexBatch(spec, 100).map(_._1).toSet shouldBe Set("a", "b")
    registry.pendingDropBatch(spec, 100) shouldBe Seq("t")
  }

  it should "record the sent count on completeRun" in {
    val run = registry.beginRun(spec, KIND_FULL)
    registry.upsertSeenBatch(spec, Seq("a" -> "h1", "b" -> "h2"), run)
    registry.completeRun(spec, run, sentCount = 2)
    registry.listRuns(spec, 30).last.sent shouldBe 2
  }

  it should "resolve tombstone ids against the seen-row id space" in {
    val run = registry.beginRun(spec, KIND_FULL)
    // Dataset whose pocket ids are the LOCAL part of the OAI id (stripped)
    registry.upsertSeenBatch(spec, Seq("11135836" -> "h1", "oai-x-42" -> "h2"), run)

    val resolved = registry.resolveTombstoneIds(spec, Seq(
      "oai:bra.uvt.nl:11135836",   // local part matches a seen row -> stripped
      "oai:x:42",                  // full cleaned id matches a seen row -> full
      "oai:bra.uvt.nl:99999999"    // matches nothing -> full cleaned fallback
    ))
    resolved shouldBe Seq("11135836", "oai-x-42", "oai-bra.uvt.nl-99999999")
  }

  // === Phase A1: runs / run_stages / tombstones ===

  it should "track the per-stage audit trail" in {
    val run = registry.beginRun(spec, KIND_FULL, trigger = Some("manual"))
    registry.stageStarted(spec, run, "process", Some("""{"kind":"full"}"""))
    registry.runStages(spec, run) shouldBe Seq("process" -> "running")

    registry.stageCompleted(spec, run, "process", Some("""{"valid":10}"""))
    registry.stageStarted(spec, run, "save")
    registry.stageCompleted(spec, run, "save")
    registry.completeRun(spec, run)

    registry.runStages(spec, run).toMap shouldBe Map("process" -> "completed", "save" -> "completed")
  }

  it should "fail open stages when a run is failed" in {
    val run = registry.beginRun(spec, KIND_FULL)
    registry.stageStarted(spec, run, "process")
    registry.failOpenRuns(spec, "boom") shouldBe 1
    registry.runStages(spec, run) shouldBe Seq("process" -> "failed")

    // beginRun self-heal also fails the stale run's open stages
    val stale = registry.beginRun(spec, KIND_FULL)
    registry.stageStarted(spec, stale, "save")
    val fresh = registry.beginRun(spec, KIND_FULL)
    registry.runStages(spec, stale) shouldBe Seq("save" -> "failed")
    registry.runStatus(spec, fresh) shouldBe Some(RUN_RUNNING)
  }

  it should "replan and discard runs (harvest joins the run)" in {
    val runId = registry.beginRun(spec, KIND_INCREMENT, trigger = Some("periodic"), plan = Some("""{"stages":["harvest"]}"""))
    registry.runPlan(spec, runId) shouldBe Some("""{"stages":["harvest"]}""")

    registry.updateRunPlan(spec, runId, """{"stages":["harvest","reconcile"]}""")
    registry.runPlan(spec, runId) shouldBe Some("""{"stages":["harvest","reconcile"]}""")

    // discard: a no-op quiet tick leaves no trace
    registry.stageStarted(spec, runId, "harvest")
    registry.discardRun(spec, runId)
    registry.runStatus(spec, runId) shouldBe None
    registry.runStages(spec, runId) shouldBe empty

    // discard never touches completed runs — row NOR stages
    val done = registry.beginRun(spec, KIND_FULL)
    registry.stageStarted(spec, done, "process")
    registry.stageCompleted(spec, done, "process")
    registry.completeRun(spec, done)
    registry.discardRun(spec, done)
    registry.runStatus(spec, done) shouldBe Some(RUN_COMPLETED)
    registry.runStages(spec, done) shouldBe Seq("process" -> "completed")
  }

  it should "record tombstones with raw ids via stampTombstones" in {
    val run1 = registry.beginRun(spec, KIND_FULL)
    registry.upsertSeenBatch(spec, Seq("11135836" -> "h1"), run1)

    val resolved = registry.stampTombstones(spec, Seq("oai:bra.uvt.nl:11135836", "oai:x:99"), run1)
    resolved shouldBe Seq("11135836", "oai-x-99")

    registry.listTombstones(spec) shouldBe Seq(
      "11135836" -> Some("oai:bra.uvt.nl:11135836"),
      "oai-x-99" -> Some("oai:x:99")
    )
    // records stamped too: the seen row flipped to deleted
    registry.count(spec, STATUS_DELETED) shouldBe 2

    // re-stamp on a later run updates last_run_id, adds no duplicates
    val run2 = registry.beginRun(spec, KIND_INCREMENT)
    registry.stampTombstones(spec, Seq("oai:x:99"), run2)
    registry.listTombstones(spec).size shouldBe 2
  }

  it should "migrate a v3 records.db in place (harvest_runs renamed to runs)" in {
    val v3Dir = new File(tempDir, "v3spec")
    v3Dir.mkdirs()
    val v3Db = new File(v3Dir, DB_FILENAME)
    val conn = java.sql.DriverManager.getConnection(s"jdbc:sqlite:${v3Db.getAbsolutePath}")
    try {
      val s = conn.createStatement()
      s.executeUpdate("CREATE TABLE schema_meta (k TEXT PRIMARY KEY, v TEXT NOT NULL)")
      s.executeUpdate("""CREATE TABLE records (
        local_id TEXT PRIMARY KEY, content_hash TEXT NOT NULL, status TEXT NOT NULL,
        first_seen_run_id INTEGER, last_changed_run_id INTEGER,
        last_seen_run_id INTEGER NOT NULL, last_seen_ts TEXT NOT NULL,
        last_sent_hash TEXT, last_sent_run_id INTEGER,
        created_at TEXT NOT NULL, updated_at TEXT NOT NULL)""")
      s.executeUpdate("""CREATE TABLE harvest_runs (
        run_id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT NOT NULL,
        started_at TEXT NOT NULL, completed_at TEXT, status TEXT NOT NULL,
        seen_count INTEGER, changed_count INTEGER, deleted_count INTEGER,
        added_count INTEGER, sent_count INTEGER, note TEXT)""")
      s.executeUpdate("INSERT INTO schema_meta (k, v) VALUES ('schema_version', '3')")
      s.executeUpdate("""INSERT INTO harvest_runs (kind, started_at, status, seen_count, sent_count)
        VALUES ('full', '2026-07-01T00:00:00Z', 'completed', 5, 5)""")
      s.close()
    } finally conn.close()

    // Opening through the registry migrates in place: table renamed, new
    // columns/tables added, old run rows still visible
    val runs = registry.listRuns("v3spec", 30)
    runs.size shouldBe 1
    runs.head.kind shouldBe "full"
    runs.head.sent shouldBe 5

    val run2 = registry.beginRun("v3spec", KIND_INCREMENT)
    registry.stageStarted("v3spec", run2, "process")
    registry.runStages("v3spec", run2) shouldBe Seq("process" -> "running")
    registry.listTombstones("v3spec") shouldBe empty
  }

  // === Phase 2: v1 -> v2 migration ===

  it should "migrate a v1 records.db in place" in {
    val v1Dir = new File(tempDir, "v1spec")
    v1Dir.mkdirs()
    val v1Db = new File(v1Dir, DB_FILENAME)
    val conn = java.sql.DriverManager.getConnection(s"jdbc:sqlite:${v1Db.getAbsolutePath}")
    try {
      val s = conn.createStatement()
      s.executeUpdate("CREATE TABLE schema_meta (k TEXT PRIMARY KEY, v TEXT NOT NULL)")
      s.executeUpdate("""CREATE TABLE records (
        local_id TEXT PRIMARY KEY, content_hash TEXT NOT NULL, status TEXT NOT NULL,
        last_seen_run_id INTEGER NOT NULL, last_seen_ts TEXT NOT NULL,
        last_sent_hash TEXT, last_sent_run_id INTEGER,
        created_at TEXT NOT NULL, updated_at TEXT NOT NULL)""")
      s.executeUpdate("""CREATE TABLE harvest_runs (
        run_id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT NOT NULL,
        started_at TEXT NOT NULL, completed_at TEXT, status TEXT NOT NULL,
        seen_count INTEGER, changed_count INTEGER, deleted_count INTEGER, note TEXT)""")
      s.executeUpdate("INSERT INTO schema_meta (k, v) VALUES ('schema_version', '1')")
      // The v1 run that saw old1 — keeps run-id autoincrement realistic
      s.executeUpdate("""INSERT INTO harvest_runs (kind, started_at, status, seen_count, changed_count, deleted_count)
        VALUES ('full', '2026-01-01T00:00:00Z', 'completed', 1, 1, 0)""")
      s.executeUpdate("""INSERT INTO records
        (local_id, content_hash, status, last_seen_run_id, last_seen_ts, created_at, updated_at)
        VALUES ('old1', 'h1', 'seen', 1, 't', 't', 't')""")
      s.close()
    } finally conn.close()

    // Opening through the registry migrates in place
    val run = registry.beginRun("v1spec", KIND_FULL)
    registry.upsertSeenBatch("v1spec", Seq("old1" -> "h1", "new1" -> "h9"), run)
    registry.completeRun("v1spec", run)

    val r = registry.listRuns("v1spec", 30).last
    // Pre-migration record was backfilled first_seen=last_seen(=1), so only
    // new1 counts as added
    r.added shouldBe 1
    r.seen shouldBe 2
  }
  it should "count a run as saved only when it ran a save or reconcile stage" in {
    // Process-only run: completes as kind=full but never touched Hub3
    val processOnly = registry.beginRun(spec, KIND_FULL)
    registry.stageStarted(spec, processOnly, "process")
    registry.stageCompleted(spec, processOnly, "process")
    registry.completeRun(spec, processOnly)
    registry.latestSavedRunCompletion(spec, KIND_FULL) shouldBe None

    // Full run with a completed save stage
    val savedRun = registry.beginRun(spec, KIND_FULL)
    registry.stageStarted(spec, savedRun, "save")
    registry.stageCompleted(spec, savedRun, "save")
    registry.completeRun(spec, savedRun)
    registry.latestSavedRunCompletion(spec, KIND_FULL) shouldBe defined

    // Deletes-only sync: reconcile stage counts too
    val reconcileRun = registry.beginRun(spec, KIND_INCREMENT)
    registry.stageStarted(spec, reconcileRun, "reconcile")
    registry.stageCompleted(spec, reconcileRun, "reconcile")
    registry.completeRun(spec, reconcileRun)
    registry.latestSavedRunCompletion(spec, KIND_INCREMENT) shouldBe defined
  }
  it should "report the latest run outcome with the failing stage's error (C3)" in {
    registry.latestRunOutcome(spec) shouldBe None  // no db yet is fine too

    val ok = registry.beginRun(spec, KIND_FULL)
    registry.stageStarted(spec, ok, "process")
    registry.stageCompleted(spec, ok, "process")
    registry.completeRun(spec, ok)
    registry.latestRunOutcome(spec).map(_.status) shouldBe Some("completed")

    val bad = registry.beginRun(spec, KIND_FULL)
    registry.stageStarted(spec, bad, "process")
    registry.failOpenRuns(spec, "boom: mapper exploded")
    val outcome = registry.latestRunOutcome(spec).get
    outcome.status shouldBe "failed"
    outcome.failedStage shouldBe Some("process")
    outcome.failedError shouldBe Some("boom: mapper exploded")

    // a later successful run supersedes the failure
    val again = registry.beginRun(spec, KIND_FULL)
    registry.stageStarted(spec, again, "process")
    registry.stageCompleted(spec, again, "process")
    registry.completeRun(spec, again)
    registry.latestRunOutcome(spec).map(_.status) shouldBe Some("completed")
  }
}
