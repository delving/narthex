package services

import java.io.File

import org.apache.commons.io.FileUtils
import org.scalatest.BeforeAndAfterEach
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import dataset.DatasetActor._

class JobQueueSpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach {

  private var tempDir: File = _
  private var queue: JobQueue = _

  override def beforeEach(): Unit = {
    tempDir = java.nio.file.Files.createTempDirectory("narthex-jobqueue-test-").toFile
    queue = new JobQueue(new File(tempDir, "queue.db"))
  }

  override def afterEach(): Unit = {
    if (queue != null) queue.close()
    if (tempDir != null) FileUtils.deleteQuietly(tempDir)
  }

  "JobQueue" should "dispatch manual jobs before periodic ones regardless of insertion order" in {
    queue.enqueue("ds-a", "{}", "periodic") shouldBe true
    queue.enqueue("ds-b", "{}", "manual") shouldBe true
    queue.enqueue("ds-c", "{}", "periodic") shouldBe true
    queue.enqueue("ds-d", "{}", "manual") shouldBe true

    queue.queued().map(_.spec) shouldBe Seq("ds-b", "ds-d", "ds-a", "ds-c")
  }

  it should "reject duplicate specs" in {
    queue.enqueue("ds-a", "{}", "manual") shouldBe true
    queue.enqueue("ds-a", "{}", "periodic") shouldBe false
    queue.size() shouldBe 1
    queue.isQueued("ds-a") shouldBe true
  }

  it should "remove by id and by spec" in {
    queue.enqueue("ds-a", "{}", "manual")
    queue.enqueue("ds-b", "{}", "manual")
    val jobA = queue.queued().find(_.spec == "ds-a").get
    queue.remove(jobA.jobId)
    queue.isQueued("ds-a") shouldBe false
    queue.removeSpec("ds-b") shouldBe true
    queue.removeSpec("ds-b") shouldBe false
    queue.size() shouldBe 0
  }

  it should "survive close and reopen (the whole point)" in {
    queue.enqueue("ds-a", """{"type":"save"}""", "recovery")
    queue.enqueue("ds-b", """{"type":"process"}""", "manual")
    queue.close()

    queue = new JobQueue(new File(tempDir, "queue.db"))
    queue.size() shouldBe 2
    queue.queued().map(_.spec) shouldBe Seq("ds-b", "ds-a")   // manual first
  }

  "lease" should "enforce one lease per spec and the concurrency limit" in {
    queue.tryLease("ds-a", "{}", "manual", limit = 2) shouldBe true
    queue.tryLease("ds-a", "{}", "manual", limit = 2) shouldBe false   // spec already leased
    queue.tryLease("ds-b", "{}", "periodic", limit = 2) shouldBe true
    queue.tryLease("ds-c", "{}", "manual", limit = 2) shouldBe false   // capacity exhausted
    queue.leasedCount() shouldBe 2
    queue.leasedSpecs().toSet shouldBe Set("ds-a", "ds-b")

    queue.releaseLease("ds-a") shouldBe true
    queue.releaseLease("ds-a") shouldBe false
    queue.tryLease("ds-c", "{}", "manual", limit = 2) shouldBe true
  }

  it should "promote queued jobs within capacity" in {
    queue.enqueue("ds-a", "{}", "manual")
    queue.enqueue("ds-b", "{}", "manual")
    val jobs = queue.queued()
    queue.leaseQueued(jobs(0).jobId, jobs(0).spec, limit = 1) shouldBe true
    queue.leaseQueued(jobs(1).jobId, jobs(1).spec, limit = 1) shouldBe false  // capacity
    queue.size() shouldBe 1              // ds-b still waiting
    queue.isLeased("ds-a") shouldBe true

    queue.releaseLease("ds-a")
    queue.leaseQueued(jobs(1).jobId, jobs(1).spec, limit = 1) shouldBe true
  }

  it should "reclaim stale leases only for inactive specs" in {
    queue.tryLease("ds-a", "{}", "manual", limit = 3)
    queue.tryLease("ds-b", "{}", "manual", limit = 3)
    // nothing is stale yet
    queue.reclaimStaleLeases(1, activeSpecs = Set.empty) shouldBe empty
    // everything older than 0 minutes is stale; ds-b is actively working
    queue.reclaimStaleLeases(0, activeSpecs = Set("ds-b")) shouldBe Seq("ds-a")
    queue.isLeased("ds-a") shouldBe false
    queue.isLeased("ds-b") shouldBe true
  }

  it should "clear all leases on startup but keep queued jobs" in {
    queue.tryLease("ds-a", "{}", "manual", limit = 3)
    queue.enqueue("ds-b", "{}", "manual")
    queue.clearLeases() shouldBe 1
    queue.isLeased("ds-a") shouldBe false
    queue.size() shouldBe 1
  }

  it should "never remove a live lease via removeSpec" in {
    queue.tryLease("ds-a", "{}", "manual", limit = 3)
    queue.removeSpec("ds-a") shouldBe false
    queue.isLeased("ds-a") shouldBe true
  }

  "JobPayload" should "round-trip every queueable message type" in {
    val messages: Seq[AnyRef] = Seq(
      Command("start fast save"),
      StartHarvest(ModifiedAfter(org.joda.time.DateTime.parse("2026-06-01T15:37:22Z"), justDate = true), "periodic"),
      StartHarvest(FromScratch(autoProcess = true), "discovery"),
      StartHarvest(FromScratchIncremental, "periodic"),
      StartHarvest(Sample, "manual"),
      StartProcessing(None),
      StartSaving(None)
    )
    messages.foreach { msg =>
      val encoded = JobPayload.encode(msg)
      encoded.isDefined shouldBe true
      // decode needs an OrgContext only for AdoptSource; null is safe here
      JobPayload.decode(encoded.get, null) shouldBe Some(msg)
    }
  }

  it should "encode AdoptSource by file path" in {
    val encoded = JobPayload.encode(record.SourceProcessor.AdoptSource(new File("/tmp/x.xml"), null))
    encoded.isDefined shouldBe true
    encoded.get should include("/tmp/x.xml")
  }

  it should "return None for garbage payloads" in {
    JobPayload.decode("not json", null) shouldBe None
    JobPayload.decode("""{"type":"martian"}""", null) shouldBe None
  }
}
