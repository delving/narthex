package dataset

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class HarvestTypeToStartSpec extends AnyFlatSpec with Matchers {

  "harvestTypeToStart" should "refuse a harvest without an endpoint" in {
    DatasetActor.harvestTypeToStart("mip", "pmh", "", "").left.toOption.get should include("no harvest URL")
    DatasetActor.harvestTypeToStart("mip", "pmh", "  ", "").isLeft shouldBe true
  }

  it should "refuse an unknown harvest type" in {
    DatasetActor.harvestTypeToStart("mip", "", "http://example.org/oai", "").left.toOption.get should include("unknown harvest type")
  }

  it should "start a harvest that has its endpoint" in {
    DatasetActor.harvestTypeToStart("mip", "pmh", "http://example.org/oai", "").isRight shouldBe true
    DatasetActor.harvestTypeToStart("mip", "download", "", "http://example.org/dump.xml").isRight shouldBe true
    DatasetActor.harvestTypeToStart("mip", "download", "http://example.org/oai", "").isLeft shouldBe true
  }
}
