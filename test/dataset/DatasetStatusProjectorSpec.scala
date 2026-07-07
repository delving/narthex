package dataset

import org.joda.time.DateTime
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import dataset.DatasetStatusProjector.ProjectedStatus
import dataset.DsInfo.DsState

class DatasetStatusProjectorSpec extends AnyFlatSpec with Matchers {

  private val t0 = new DateTime(2026, 1, 1, 0, 0)
  private val t1 = t0.plusDays(1)

  private val blank = ProjectedStatus(None, None, None, None, None, None, None, None, None, None, None)

  "currentState" should "return EMPTY when nothing exists" in {
    blank.currentState shouldBe DsState.EMPTY
  }

  it should "follow the lattice, not timestamps" in {
    // SIP regenerated AFTER processing (newer mtime) must not demote the
    // dataset to MAPPABLE — the date-max bug class.
    val s = blank.copy(sourced = Some(t0), mappable = Some(t1), processed = Some(t0))
    s.currentState shouldBe DsState.PROCESSED
  }

  it should "let DISABLED trump everything" in {
    blank.copy(saved = Some(t1), disabled = Some(t0)).currentState shouldBe DsState.DISABLED
  }

  it should "pick the newer of saved vs incrementalSaved" in {
    blank.copy(saved = Some(t0), incrementalSaved = Some(t1)).currentState shouldBe DsState.INCREMENTAL_SAVED
    blank.copy(saved = Some(t1), incrementalSaved = Some(t0)).currentState shouldBe DsState.SAVED
  }

  it should "report PROCESSABLE only above MAPPABLE" in {
    blank.copy(sourced = Some(t0), mappable = Some(t0)).currentState shouldBe DsState.MAPPABLE
    blank.copy(sourced = Some(t0), mappable = Some(t0), processable = Some(t0)).currentState shouldBe DsState.PROCESSABLE
  }

  "stateFields" should "emit only present states with the UI's field names" in {
    val fields = blank.copy(raw = Some(t0), sourced = Some(t1)).stateFields.toMap
    fields.keySet shouldBe Set("stateRaw", "stateSourced")
    fields("stateSourced") should startWith("2026-01-02T")
  }
}
