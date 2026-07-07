package dataset

import org.joda.time.DateTime
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import dataset.DatasetStatusDoc._
import dataset.DatasetStatusProjector.ProjectedStatus

class DatasetStatusDocSpec extends AnyFlatSpec with Matchers {

  private val t0 = new DateTime(2026, 1, 1, 0, 0)
  private val t1 = t0.plusDays(1)

  private val blank = ProjectedStatus(None, None, None, None, None, None, None, None, None, None, None)
  private val noFacts = Facts(delimitersSet = None, errorMessage = None, inRetry = false)

  "actions" should "offer only cancel while running or queued" in {
    actions(blank.copy(sourced = Some(t0), processable = Some(t0)), PHASE_RUNNING, noFacts) shouldBe Seq("cancel")
    actions(blank.copy(sourced = Some(t0)), PHASE_QUEUED, noFacts) shouldBe Seq("cancel")
  }

  it should "offer only enable when disabled" in {
    actions(blank.copy(disabled = Some(t0)), PHASE_DISABLED, noFacts) shouldBe Seq("enable")
  }

  it should "not offer process without a mapping" in {
    val idle = actions(blank.copy(sourced = Some(t0)), PHASE_IDLE, noFacts)
    idle should contain allOf ("analyze_source", "generate_sip", "fast_save")
    idle should not contain "process"
  }

  it should "offer process when source + mapping exist" in {
    actions(blank.copy(sourced = Some(t0), processable = Some(t0)), PHASE_IDLE, noFacts) should contain("process")
  }

  it should "gate first_harvest on valid delimiters and no source yet" in {
    val rawOnly = blank.copy(raw = Some(t0), rawAnalyzed = Some(t1))
    // delimiters set BEFORE the latest raw analysis: invalid → delimit
    val stale = actions(rawOnly, PHASE_IDLE, noFacts.copy(delimitersSet = Some("2025-12-31T00:00:00.000Z")))
    stale should contain("delimit")
    stale should not contain "first_harvest"
    // delimiters set after analysis: harvest offered
    val valid = actions(rawOnly, PHASE_IDLE, noFacts.copy(delimitersSet = Some("2026-01-03T00:00:00.000Z")))
    valid should contain("first_harvest")
    valid should not contain "delimit"
    // already sourced: no first_harvest
    actions(rawOnly.copy(sourced = Some(t1)), PHASE_IDLE, noFacts) should not contain "first_harvest"
  }

  it should "gate save on processed analysis" in {
    val processed = blank.copy(sourced = Some(t0), processed = Some(t0))
    actions(processed, PHASE_IDLE, noFacts) should not contain "save"
    actions(processed.copy(analyzed = Some(t0)), PHASE_IDLE, noFacts) should contain("save")
  }

  "lastStep" should "name the newest artifact" in {
    lastStep(blank) shouldBe None
    lastStep(blank.copy(sourced = Some(t0), processed = Some(t1))) shouldBe Some("processed")
    lastStep(blank.copy(processed = Some(t0), mappable = Some(t1))) shouldBe Some("sip")
  }
}
