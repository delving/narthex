package dataset

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import dataset.IndexVerifyAction._

class IndexVerifyActionSpec extends AnyFlatSpec with Matchers {

  "IndexVerifyAction" should "do nothing when the counts match" in {
    IndexVerifyAction(3374, 3374, hasNamedErrors = false) shouldBe Nothing
  }

  it should "do nothing when nothing was expected" in {
    // A dataset with no processed output yet: 0 indexed of 0 is not a gap, and
    // 5 indexed of 0 expected says the verification has no baseline to judge by.
    IndexVerifyAction(0, 0, hasNamedErrors = false) shouldBe Nothing
    IndexVerifyAction(0, 5, hasNamedErrors = false) shouldBe Nothing
  }

  it should "leave named errors to their own path" in {
    // Hub3 named the failing documents, so resetSentStateForIds re-sends
    // exactly those. Reconciling or mailing on top would be duplicate work.
    IndexVerifyAction(3374, 285, hasNamedErrors = true) shouldBe Nothing
  }

  it should "reconcile a gap it can close" in {
    IndexVerifyAction(1000, 999, hasNamedErrors = false) shouldBe Reconcile(1)
    IndexVerifyAction(5000, 4000, hasNamedErrors = false) shouldBe Reconcile(MaxSelfHealGap)
  }

  it should "reconcile a gap in the other direction too" in {
    // More indexed than expected: deletes that never landed. Same repair.
    IndexVerifyAction(900, 1000, hasNamedErrors = false) shouldBe Reconcile(100)
  }

  it should "report a gap too large to close" in {
    IndexVerifyAction(5000, 3999, hasNamedErrors = false) shouldBe Report(MaxSelfHealGap + 1)
  }

  it should "report the gap that went unnoticed (#3626)" in {
    // The real numbers: 285 of 3374 in the index, no named errors. This is the
    // case that fell between the self-heal cap and nothing at all, and was
    // detected five times without reaching anyone.
    IndexVerifyAction(3374, 285, hasNamedErrors = false) shouldBe Report(3089)
  }

  it should "split exactly at the self-heal cap" in {
    // The boundary is the whole point: one side repairs itself, the other side
    // has to be told to someone. An off-by-one here is silence again.
    IndexVerifyAction(10000, 10000 - MaxSelfHealGap, hasNamedErrors = false) shouldBe Reconcile(MaxSelfHealGap)
    IndexVerifyAction(10000, 10000 - MaxSelfHealGap - 1, hasNamedErrors = false) shouldBe Report(MaxSelfHealGap + 1)
  }
}
