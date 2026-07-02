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
    registry.completeRun(spec, run1, RunCounts(1, 1, 0))

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
    registry.completeRun(spec, run1, RunCounts(3, 3, 0))

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
    registry.completeRun(spec, run1, RunCounts(2, 2, 0))

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
    registry.completeRun(spec, run, RunCounts(1, 1, 0))
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
    registry.completeRun(spec, run2, RunCounts(0, 0, 0))
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
}
