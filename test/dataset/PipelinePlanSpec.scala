package dataset

import java.io.File

import org.joda.time.DateTime
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import dataset.PipelinePlan._
import services.RecordRegistry

class PipelinePlanSpec extends AnyFlatSpec with Matchers {

  private val delta = new File("/data/datasets/x/source/00003.zip")
  private val mod = DateTime.parse("2026-06-01T15:37:22Z")

  // === afterHarvest: the chain that bug-061 silently degraded ===

  "afterHarvest" should "plan a delta chain for a true incremental harvest" in {
    val plan = afterHarvest(hasMapper = true, Some(mod), delta)
    plan.stages shouldBe List(STAGE_GENERATE_SIP, STAGE_PROCESS, STAGE_SAVE, STAGE_RECONCILE)
    plan.kind shouldBe RecordRegistry.KIND_INCREMENT
    plan.incremental shouldBe true

    // bug-061 regression: the delta context must survive to the processing
    // stage — the old flag path matched it and then sent StartProcessing(None)
    val scheduled = plan.scheduledForProcessing
    scheduled.isDefined shouldBe true
    scheduled.get.file.getAbsolutePath shouldBe delta.getAbsolutePath
    scheduled.get.modifiedAfter shouldBe Some(mod)
  }

  it should "plan a full chain for FromScratchIncremental (modifiedAfter empty)" in {
    val plan = afterHarvest(hasMapper = true, None, delta)
    plan.stages shouldBe List(STAGE_GENERATE_SIP, STAGE_PROCESS, STAGE_SAVE, STAGE_RECONCILE)
    plan.kind shouldBe RecordRegistry.KIND_FULL
    plan.incremental shouldBe false
    // Full semantics: processing must NOT be scoped to the delta file
    plan.scheduledForProcessing shouldBe None
  }

  it should "end after SIP generation when there is no mapper" in {
    afterHarvest(hasMapper = false, Some(mod), delta).stages shouldBe List(STAGE_GENERATE_SIP)
    afterHarvest(hasMapper = false, None, delta).stages shouldBe List(STAGE_GENERATE_SIP)
  }

  // === command plans mirror the old dispatch exactly ===

  "command plans" should "match the legacy flag semantics" in {
    fastSaveFromSourced.stages shouldBe List(STAGE_GENERATE_SIP, STAGE_PROCESS, STAGE_SAVE, STAGE_RECONCILE)
    fastSaveFromProcessable.stages shouldBe List(STAGE_PROCESS, STAGE_SAVE, STAGE_RECONCILE)
    fastProcess.stages shouldBe List(STAGE_GENERATE_SIP, STAGE_PROCESS)          // stops at PROCESSED
    processOnly.stages shouldBe List(STAGE_PROCESS)
    afterAdoption.stages shouldBe List(STAGE_GENERATE_SIP)
    afterFirstHarvestAutoProcess(hasMapper = true).stages shouldBe List(STAGE_GENERATE_SIP, STAGE_PROCESS)
    afterFirstHarvestAutoProcess(hasMapper = false).stages shouldBe List(STAGE_GENERATE_SIP)

    // Save only where planned — fastProcess must never save
    fastProcess.includes(STAGE_SAVE) shouldBe false
    afterFirstHarvestAutoProcess(hasMapper = true).includes(STAGE_SAVE) shouldBe false
    fastSaveFromSourced.includes(STAGE_SAVE) shouldBe true
  }

  "nextAfter" should "walk the chain in order and end cleanly" in {
    val plan = fastSaveFromSourced
    plan.nextAfter(STAGE_GENERATE_SIP) shouldBe Some(STAGE_PROCESS)
    plan.nextAfter(STAGE_PROCESS) shouldBe Some(STAGE_SAVE)
    plan.nextAfter(STAGE_RECONCILE) shouldBe None
    plan.nextAfter("unknown") shouldBe None
  }

  // === harvest-initiated runs: plan-then-replan (Phase A3a) ===

  "forHarvest" should "start with only the harvest stage" in {
    forHarvest(incremental = true).stages shouldBe List(STAGE_HARVEST)
    forHarvest(incremental = true).kind shouldBe RecordRegistry.KIND_INCREMENT
    forHarvest(incremental = false).kind shouldBe RecordRegistry.KIND_FULL
  }

  "harvestThen" should "prefix the decided continuation with the harvest stage" in {
    val replanned = harvestThen(afterHarvest(hasMapper = true, Some(mod), delta))
    replanned.stages shouldBe List(STAGE_HARVEST, STAGE_GENERATE_SIP, STAGE_PROCESS, STAGE_SAVE, STAGE_RECONCILE)
    replanned.incremental shouldBe true
    replanned.scheduledForProcessing.map(_.file.getAbsolutePath) shouldBe Some(delta.getAbsolutePath)
    // nextAfter still walks correctly with the prefix
    replanned.nextAfter(STAGE_GENERATE_SIP) shouldBe Some(STAGE_PROCESS)
  }

  "harvestDeletesOnly" should "plan tombstone sync without the pipeline" in {
    harvestDeletesOnly.stages shouldBe List(STAGE_HARVEST, STAGE_RECONCILE)
    harvestDeletesOnly.includes(STAGE_PROCESS) shouldBe false
    harvestDeletesOnly.includes(STAGE_SAVE) shouldBe false
  }

  // === persistence round-trip: the plan must survive the run row ===

  "Plan JSON" should "round-trip including the delta context" in {
    val plan = afterHarvest(hasMapper = true, Some(mod), delta)
    val back = Plan.fromJson(plan.toJson)
    back shouldBe Some(plan)
    back.get.scheduledForProcessing.get.modifiedAfter shouldBe Some(mod)
  }

  it should "round-trip a full plan with empty optionals" in {
    Plan.fromJson(fastSaveFromProcessable.toJson) shouldBe Some(fastSaveFromProcessable)
  }

  it should "return None on garbage" in {
    Plan.fromJson("not json") shouldBe None
    Plan.fromJson("""{"stages": 5}""") shouldBe None
  }

  // === SaveMode: sweep XOR filtering as a type ===

  "saveModeFor" should "make sweep and delta filtering mutually exclusive" in {
    // incremental saves never sweep
    saveModeFor(incremental = true, registryEnabled = true, keepRevisionSweep = true) shouldBe IncrementalFileSend
    saveModeFor(incremental = true, registryEnabled = false, keepRevisionSweep = true) shouldBe IncrementalFileSend
    // full save with the sweep on (default, or registry off): full send
    saveModeFor(incremental = false, registryEnabled = true, keepRevisionSweep = true) shouldBe FullSendWithSweep
    saveModeFor(incremental = false, registryEnabled = false, keepRevisionSweep = false) shouldBe FullSendWithSweep
    // registry as sole orphan authority: delta filtering, no sweep
    saveModeFor(incremental = false, registryEnabled = true, keepRevisionSweep = false) shouldBe DeltaSendRegistryOwned
  }

  it should "round-trip by name" in {
    Seq(FullSendWithSweep, DeltaSendRegistryOwned, IncrementalFileSend).foreach { mode =>
      SaveMode.fromName(mode.name) shouldBe Some(mode)
    }
    SaveMode.fromName("nope") shouldBe None
  }
  it should "plan standalone analysis and make-sip as single-stage runs (C2)" in {
    analyzeOnly.stages shouldBe List(STAGE_ANALYZE)
    analyzeOnly.includes(STAGE_SAVE) shouldBe false
    generateSipOnly.stages shouldBe List(STAGE_GENERATE_SIP)
    // round-trip like every other plan
    Plan.fromJson(analyzeOnly.toJson).map(_.stages) shouldBe Some(List(STAGE_ANALYZE))
  }
  it should "plan manual save as a first-class [save, reconcile] run" in {
    saveOnly.stages shouldBe List(STAGE_SAVE, STAGE_RECONCILE)
    saveOnly.includes(STAGE_PROCESS) shouldBe false
    Plan.fromJson(saveOnly.toJson).map(_.stages) shouldBe Some(List(STAGE_SAVE, STAGE_RECONCILE))
  }
}
