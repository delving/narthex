package dataset.pipeline

import java.io.File

import scala.concurrent.Future
import scala.concurrent.duration._

import dataset.DatasetActor.Scheduled
import dataset.{DatasetContext, DsInfo, PipelinePlan, ProcessedRepo}
import init.NarthexConfig
import org.apache.commons.io.FileUtils
import org.joda.time.DateTime
import org.mockito.ArgumentMatchers.{any, anyInt, anyLong, anyString, eq => eqTo}
import org.mockito.Mockito.{never, verify, when}
import org.scalatest.BeforeAndAfterEach
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import organization.OrgContext
import services._
import triplestore.GraphProperties.{datasetTags, datasetType, externalIndex}

class SaveStageIndexServiceSpec extends AnyFlatSpec with Matchers with MockitoSugar with BeforeAndAfterEach {

  private val org = "testorg"
  private val spec = "ds"
  private var home: File = _
  private def processedDir = new File(home, s"$org/datasets/$spec/processed")
  private def sourceDir = new File(home, s"$org/datasets/$spec/source")

  private var slept: FiniteDuration = Duration.Zero
  private var clockMs = 0L

  override def beforeEach(): Unit = {
    registry = mock[RecordRegistry]
    slept = Duration.Zero
    clockMs = 0L
    home = java.nio.file.Files.createTempDirectory("narthex-save-index-service-").toFile
    processedDir.mkdirs()
    sourceDir.mkdirs()
    Seq("00000.xml.zst", "00001.xml.zst").foreach(n => FileUtils.writeStringToFile(new File(processedDir, n), "x", "UTF-8"))
  }

  override def afterEach(): Unit = { FileUtils.deleteQuietly(home); () }

  private def status(state: String, error: Option[String] = None, counts: Map[String, Long] = Map.empty,
                     targets: Map[String, IndexTargetState] = Map.empty): Either[IndexServiceError, IndexJobStatus] =
    Right(IndexJobStatus("j1", state, Some(10L), error, counts, targets))

  private class FakeIndexService(statuses: Seq[Either[IndexServiceError, IndexJobStatus]],
                           post: Either[IndexServiceError, String] = Right("j1"),
                           publishResult: Either[IndexServiceError, Long] = Right(1L)) extends IndexServiceApi {
    var events = Vector.empty[String]
    var posted = Vector.empty[(String, String, IndexJobRequest)]
    var polls = 0
    def postJob(o: String, s: String, req: IndexJobRequest) = { posted :+= ((o, s, req)); events :+= "post"; Future.successful(post) }
    def depublish(o: String, s: String, by: String) = Future.successful(Right(1L))
    def publish(o: String, s: String, by: String) = { events :+= "publish"; Future.successful(publishResult) }
    def jobStatus(o: String, s: String, jobId: String) = {
      val st = statuses(math.min(polls, statuses.size - 1)); polls += 1; Future.successful(st)
    }
  }

  private class CountingReporter(interruptAfter: Int = Int.MaxValue) extends FakeProgressReporter {
    var checks = 0
    var values = Vector.empty[Int]
    var maximum = 0
    override def setMaximum(max: Int): Unit = maximum = max
    override def checkInterrupt(): Unit = { checks += 1; if (checks > interruptAfter) throw new ProgressReporter.InterruptedException }
    override def sendValue(value: Option[Int]): Unit = values ++= value
  }

  private var registry: RecordRegistry = _

  private def ctx(api: Option[IndexServiceApi], external: Boolean = true, disabled: Boolean = false, reporter: ProgressReporter = new CountingReporter): StageContext = {
    val config = mock[NarthexConfig]
    when(config.orgId).thenReturn(org)
    when(config.indexServicePollSeconds).thenReturn(5)
    when(config.indexServiceStuckMinutes).thenReturn(30)
    when(config.registryEnabled).thenReturn(true)
    val dsInfo = mock[DsInfo]
    when(dsInfo.spec).thenReturn(spec)
    when(dsInfo.isDisabled).thenReturn(disabled)
    when(dsInfo.getBooleanProp(externalIndex)).thenReturn(external)
    when(dsInfo.getLiteralProp(datasetType)).thenReturn(Some("edm_record"))
    when(dsInfo.getLiteralProp(datasetTags)).thenReturn(None)
    val orgCtx = mock[OrgContext]
    when(orgCtx.narthexConfig).thenReturn(config)
    when(orgCtx.appConfig).thenReturn(config)
    when(orgCtx.root).thenReturn(home)
    when(orgCtx.recordRegistry).thenReturn(registry)
    when(orgCtx.indexServiceApi).thenReturn(api)
    val dsCtx = mock[DatasetContext]
    when(dsCtx.dsInfo).thenReturn(dsInfo)
    when(dsCtx.processedRepo).thenReturn(new ProcessedRepo(processedDir, dsInfo))
    when(dsCtx.sourceDir).thenReturn(sourceDir)
    StageContext(dsCtx, orgCtx, reporter)
  }

  private def sleep(d: FiniteDuration): Unit = { slept += d; clockMs += d.toMillis }
  private def run(stage: SaveStage, c: StageContext): StageResult = stage.runIndexService(c, sleep, () => clockMs)

  private val full = SaveStage(None, None, Some(PipelinePlan.FullSendWithSweep))

  "SaveStage for an externalIndex dataset" should "post every processed file relative to narthex home and poll to done" in {
    val api = new FakeIndexService(Seq(
      status("indexing", targets = Map("hub3" -> IndexTargetState(5L, "ok", None), "alt" -> IndexTargetState(4L, "failed", Some("boom")))),
      status("done", counts = Map("received" -> 12L, "refused" -> 2L))))
    val reporter = new CountingReporter
    run(full, ctx(Some(api), reporter = reporter)) shouldBe GraphsSaved(10)
    api.posted.map(p => (p._1, p._2)) shouldBe Vector((org, spec))
    val req = api.posted.head._3
    req.mode shouldBe "full"
    req.files shouldBe Seq(s"$org/datasets/$spec/processed/00000.xml.zst", s"$org/datasets/$spec/processed/00001.xml.zst")
    req.deletedIds shouldBe None
    req.docType shouldBe "edm_record"
    req.docTags shouldBe ""
    req.agent should startWith("narthex@")
    req.force shouldBe false
    api.polls shouldBe 2
    slept shouldBe 5.seconds
    reporter.values shouldBe Vector(1) // targets ok out of setMaximum(2)
    reporter.maximum shouldBe 2
  }

  it should "post only the scheduled file plus deleted.ids on an incremental run" in {
    FileUtils.writeStringToFile(new File(sourceDir, "deleted.ids"), "a\n", "UTF-8")
    val file = new File(processedDir, "00001.xml.zst")
    val stage = SaveStage(Some(Scheduled(Some(DateTime.now()), file)), None, Some(PipelinePlan.IncrementalFileSend))
    val api = new FakeIndexService(Seq(status("unchanged", counts = Map("received" -> 3L))))
    run(stage, ctx(Some(api))) shouldBe GraphsSaved(3)
    val req = api.posted.head._3
    req.mode shouldBe "incremental"
    req.files shouldBe Seq(s"$org/datasets/$spec/processed/00001.xml.zst")
    req.deletedIds shouldBe Some(s"$org/datasets/$spec/source/deleted.ids")
  }

  it should "send deleted.ids on a full save when the file exists" in {
    FileUtils.writeStringToFile(new File(sourceDir, "deleted.ids"), "a\n", "UTF-8")
    val api = new FakeIndexService(Seq(status("done", counts = Map("received" -> 1L))))
    run(full, ctx(Some(api))) shouldBe GraphsSaved(1)
    api.posted.head._3.mode shouldBe "full"
    api.posted.head._3.deletedIds shouldBe Some(s"$org/datasets/$spec/source/deleted.ids")
  }

  it should "publish before posting the job, so a depublished dataset comes back" in {
    val api = new FakeIndexService(Seq(status("done", counts = Map("received" -> 1L))))
    run(full, ctx(Some(api))) shouldBe GraphsSaved(1)
    api.events shouldBe Vector("publish", "post")
  }

  it should "never publish a DISABLED dataset" in {
    val api = new FakeIndexService(Seq(status("done", counts = Map("received" -> 1L))))
    run(full, ctx(Some(api), disabled = true)) shouldBe GraphsSaved(1)
    api.events shouldBe Vector("post")
  }

  it should "fail the stage and post no job when publish fails" in {
    val api = new FakeIndexService(Seq.empty, publishResult = Left(IndexServiceRejected(403, "forbidden", "no")))
    run(full, ctx(Some(api))) match {
      case StageFailed(msg) => msg should (include("publish failed") and include("forbidden"))
      case other => fail(s"expected StageFailed, got $other")
    }
    api.events shouldBe Vector("publish")
  }

  it should "publish before a deletes-only job too" in {
    FileUtils.writeStringToFile(new File(sourceDir, "deleted.ids"), "a\n", "UTF-8")
    val api = new FakeIndexService(Seq(status("done", counts = Map("received" -> 0L))))
    SaveStage.indexServiceDeletesOnly(ctx(Some(api)), None, sleep, () => clockMs) shouldBe Some(GraphsSaved(0))
    api.events shouldBe Vector("publish", "post")
  }

  it should "fail with the job's error when the index service fails it" in {
    val api = new FakeIndexService(Seq(status("ingesting"), status("failed", error = Some("mass_delete"))))
    run(full, ctx(Some(api))) match {
      case StageFailed(msg) => msg should (include("j1") and include("mass_delete"))
      case other => fail(s"expected StageFailed, got $other")
    }
  }

  it should "point a mass_delete failure at the forced re-post" in {
    val api = new FakeIndexService(Seq(status("failed", error = Some("mass_delete"))))
    run(full, ctx(Some(api))) match {
      case StageFailed(msg) => msg should (include("mass_delete") and include("10%") and include("\"force\": true") and include("staff"))
      case other => fail(s"expected StageFailed, got $other")
    }
  }

  it should "fail the stage once a target has been failed or dirty for indexService.stuckMinutes while indexing" in {
    val stuck = status("indexing", targets = Map(
      "v2" -> IndexTargetState(3L, "failed", Some("TransportFailed")), "alt" -> IndexTargetState(4L, "ok", None)))
    val api = new FakeIndexService(Seq(stuck))
    run(full, ctx(Some(api))) match {
      case StageFailed(msg) => msg should (include("j1") and include("v2") and include("failed") and include("TransportFailed")
        and include("30 minutes") and include("carries on"))
      case other => fail(s"expected StageFailed, got $other")
    }
    clockMs shouldBe 30.minutes.toMillis // stuck from the first poll, at clock 0
  }

  it should "measure a stuck target from when it went bad and forget it once all targets are ok" in {
    def ix(state: String) = status("indexing", targets = Map("v2" -> IndexTargetState(3L, state, Some("count 2 != 3 live - 0 refused"))))
    val polls20min = (20.minutes.toSeconds / 5).toInt
    // 20 min dirty, ok, 20 min dirty again, done: never 30 in a row
    val api = new FakeIndexService(Seq(status("ingesting")) ++ Seq.fill(polls20min)(ix("dirty")) ++ Seq(ix("ok")) ++
      Seq.fill(polls20min)(ix("dirty")) ++ Seq(status("done", counts = Map("received" -> 3L))))
    run(full, ctx(Some(api))) shouldBe GraphsSaved(3)
  }

  it should "name the token when the index service answers 403" in {
    val api = new FakeIndexService(Seq.empty, post = Left(IndexServiceRejected(403, "forbidden", "wrong org")))
    run(full, ctx(Some(api))) match {
      case StageFailed(msg) => msg should (include("403") and include("forbidden") and include(s"indexService.token for org $org"))
      case other => fail(s"expected StageFailed, got $other")
    }
  }

  it should "stop polling on an interrupt" in {
    val api = new FakeIndexService(Seq(status("queued")))
    val reporter = new CountingReporter(interruptAfter = 3) // one check before the post, then one per poll
    run(full, ctx(Some(api), reporter = reporter)) shouldBe StageFailed("Save operation interrupted by user")
    api.polls shouldBe 2
  }

  it should "give up after 10 minutes of the index service being unreachable while polling" in {
    val api = new FakeIndexService(Seq(status("queued"), Left(IndexServiceUnreachable("connection refused"))))
    run(full, ctx(Some(api))) match {
      case StageFailed(msg) => msg should include("unreachable")
      case other => fail(s"expected StageFailed, got $other")
    }
    clockMs shouldBe 5.seconds.toMillis + 10.minutes.toMillis // first unreachable poll comes after one interval
    api.polls shouldBe 1 + 10.minutes.toSeconds / 5 + 1
  }

  it should "keep polling through a short outage" in {
    val api = new FakeIndexService(Seq(Left(IndexServiceUnreachable("x")), Left(IndexServiceUnreachable("x")), status("done", counts = Map("received" -> 1L))))
    run(full, ctx(Some(api))) shouldBe GraphsSaved(1)
  }

  it should "fail when the index service is not configured" in {
    run(full, ctx(None)) match {
      case StageFailed(msg) => msg should include(s"not configured for org $org")
      case other => fail(s"expected StageFailed, got $other")
    }
  }

  it should "fail when there are no processed files" in {
    FileUtils.cleanDirectory(processedDir)
    val api = new FakeIndexService(Seq(status("done")))
    run(full, ctx(Some(api))) shouldBe StageFailed("no processed files")
    api.posted shouldBe empty
  }

  it should "keep the run bookkeeping and never confirm or tombstone in the registry" in {
    val api = new FakeIndexService(Seq(status("done", counts = Map("received" -> 4L))))
    run(SaveStage(None, Some(7L), Some(PipelinePlan.FullSendWithSweep)), ctx(Some(api))) shouldBe GraphsSaved(4)
    verify(registry).stageStarted(eqTo(spec), eqTo(7L), eqTo("save"), any[Option[String]]())
    verify(registry).stageCompleted(eqTo(spec), eqTo(7L), eqTo("save"), any[Option[String]]())
    verify(registry).completeRun(eqTo(spec), eqTo(7L), eqTo(4))
    verify(registry, never()).confirmIndexedByIds(anyString(), any[Seq[String]](), anyLong())
    verify(registry, never()).stampTombstones(anyString(), any[Seq[String]](), anyLong())
    verify(registry, never()).pendingDropBatch(anyString(), anyInt())
  }

  it should "be taken from run() when the flag is set, and not otherwise" in {
    val api = new FakeIndexService(Seq(status("done", counts = Map("received" -> 1L))))
    full.run(ctx(Some(api))) shouldBe GraphsSaved(1)
    val other = new FakeIndexService(Seq(status("done")))
    // A non-external dataset takes the hub3 path, which rejects a non-processed file before any I/O.
    SaveStage(Some(Scheduled(None, new File(home, "x.txt"))), None, None).run(ctx(Some(other), external = false)) shouldBe
      StageFailed("Save received non-processed file x.txt; expected .xml or .xml.zst")
    other.posted shouldBe empty
    other.polls shouldBe 0
  }

  it should "keep polling through an unknown state" in {
    val api = new FakeIndexService(Seq(status("paused"), status("paused"), status("done", counts = Map("received" -> 2L))))
    run(full, ctx(Some(api))) shouldBe GraphsSaved(2)
    api.polls shouldBe 3
  }

  "SaveStage.indexServiceDeletesOnly" should "post deleted.ids as an incremental job with no files, once per change" in {
    val deleted = new File(sourceDir, "deleted.ids")
    FileUtils.writeStringToFile(deleted, "a\nb\n", "UTF-8")
    val api = new FakeIndexService(Seq(status("done")))
    val c = ctx(Some(api))
    when(registry.beginRun(anyString(), anyString(), any[Option[String]](), any[Option[String]]())).thenReturn(3L)
    SaveStage.indexServiceDeletesOnly(c, Some(9L), sleep, () => clockMs) shouldBe Some(GraphsSaved(0))
    api.posted.size shouldBe 1
    val req = api.posted.head._3
    req.mode shouldBe "incremental"
    req.files shouldBe empty
    req.deletedIds shouldBe Some(s"$org/datasets/$spec/source/deleted.ids")
    verify(registry).updateRunPlan(eqTo(spec), eqTo(9L), anyString())
    verify(registry).stageStarted(eqTo(spec), eqTo(9L), eqTo("reconcile"), any[Option[String]]())
    verify(registry).completeRun(eqTo(spec), eqTo(9L), anyInt())

    // A quiet tick with the same file posts nothing and discards its run.
    SaveStage.indexServiceDeletesOnly(c, Some(10L), sleep, () => clockMs) shouldBe None
    api.posted.size shouldBe 1
    verify(registry).discardRun(spec, 10L)

    // A changed file posts again.
    FileUtils.writeStringToFile(deleted, "a\nb\nc\n", "UTF-8")
    SaveStage.indexServiceDeletesOnly(c, Some(11L), sleep, () => clockMs) shouldBe Some(GraphsSaved(0))
    api.posted.size shouldBe 2
  }

  it should "post again after a failed job, and fail the run" in {
    FileUtils.writeStringToFile(new File(sourceDir, "deleted.ids"), "a\n", "UTF-8")
    val api = new FakeIndexService(Seq(status("failed", error = Some("boom")), status("done")))
    val c = ctx(Some(api))
    SaveStage.indexServiceDeletesOnly(c, Some(1L), sleep, () => clockMs) match {
      case Some(StageFailed(msg)) => msg should include("boom")
      case other => fail(s"expected StageFailed, got $other")
    }
    verify(registry).failRun(eqTo(spec), eqTo(1L), anyString())
    verify(registry, never()).failOpenRuns(anyString(), anyString())
    SaveStage.indexServiceDeletesOnly(c, Some(2L), sleep, () => clockMs) shouldBe Some(GraphsSaved(0))
    api.posted.size shouldBe 2
  }

  it should "fail its own run when the index service is not configured" in {
    FileUtils.writeStringToFile(new File(sourceDir, "deleted.ids"), "a\n", "UTF-8")
    SaveStage.indexServiceDeletesOnly(ctx(None), Some(4L), sleep, () => clockMs) match {
      case Some(StageFailed(msg)) => msg should include("not configured")
      case other => fail(s"expected StageFailed, got $other")
    }
    verify(registry).failRun(eqTo(spec), eqTo(4L), anyString())
    verify(registry, never()).failOpenRuns(anyString(), anyString())
    verify(registry, never()).discardRun(anyString(), anyLong())
  }

  it should "post nothing without a deleted.ids" in {
    val api = new FakeIndexService(Seq(status("done")))
    SaveStage.indexServiceDeletesOnly(ctx(Some(api)), None, sleep, () => clockMs) shouldBe None
    api.posted shouldBe empty
  }

  "RecordRegistry.stampRegistry" should "be off for an externalIndex dataset" in {
    def check(enabled: Boolean, external: Boolean): Boolean = {
      val config = mock[NarthexConfig]
      when(config.registryEnabled).thenReturn(enabled)
      val dsInfo = mock[DsInfo]
      when(dsInfo.getBooleanProp(externalIndex)).thenReturn(external)
      RecordRegistry.stampRegistry(dsInfo, config)
    }
    check(enabled = true, external = false) shouldBe true
    check(enabled = true, external = true) shouldBe false
    check(enabled = false, external = false) shouldBe false
    check(enabled = false, external = true) shouldBe false
  }
}
