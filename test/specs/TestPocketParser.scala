package specs

import dataset.SourceRepo
import dataset.SourceRepo.{IdFilter, SourceFacts}
import org.scalatest.{FlatSpec, Matchers}
import record.PocketParser
import record.PocketParser.Pocket
import services.FakeProgressReporter

import scala.io.Source

class TestPocketParser extends FlatSpec with Matchers {
  val idFilter = IdFilter("verbatim", None)
  val reporter = new FakeProgressReporter
  val validId = "foo-bar"

  def factsSource = Source.fromString(
    """sourceType=delving-pocket-source
      |recordRoot=/pockets/pocket
      |uniqueId=/pockets/pocket/@id
      |recordContainer=/pockets/pocket
    """.stripMargin)

  def getRecords(toBeCleanedId: String): String = {
    s"""<pockets>
          |  <pocket id="$toBeCleanedId" >
          |    <record id="87">
          |      <achternaam>Schraven</achternaam>
          |      <voornaam1>Hans</voornaam1>
          |      <geb>1956</geb>
          |      <gest>1999</gest>
          |      <titel>Face Eighty Seven</titel>
          |      <jaarvanvervaardiging>2009</jaarvanvervaardiging>
          |      <techniek>potlood, kleurpotlood</techniek>
          |      <inventarisnummer>87</inventarisnummer>
          |      <in>87</in>
          |      <pictureurl>http://www.ietsheelanders.nl/wp-content/uploads/2010/09/faceeightyseven.jpg</pictureurl>
          |    </record>
          |  </pocket>
          |</pockets>
          |
    """.stripMargin
  }



  behavior of "an object id containing a : character"

  it should "replace the colon with a dash " in {
    val source = Source.fromString(getRecords("foo:bar"))
    val pocketParser = new PocketParser(SourceRepo.readSourceFacts(factsSource), idFilter)

    var outputCreated = false
    def outPut (pocket: Pocket): Unit = {
      outputCreated = true
      assert(pocket.id == "foo-bar")
    }
    pocketParser.parse(source, Set.empty, outPut, reporter)
    assert(outputCreated)
  }

  it should "replace the space with a dash " in {
    val source = Source.fromString(getRecords("foo bar"))
    val pocketParser = new PocketParser(SourceRepo.readSourceFacts(factsSource), idFilter)

    var outputCreated = false
    def outPut (pocket: Pocket): Unit = {
      outputCreated = true
      assert(pocket.id == "foo-bar")
    }
    pocketParser.parse(source, Set.empty, outPut, reporter)
    assert(outputCreated)
  }

  it should "replace the plus with a dash " in {
    val source = Source.fromString(getRecords("foo+bar"))
    val pocketParser = new PocketParser(SourceRepo.readSourceFacts(factsSource), idFilter)

    var outputCreated = false
    def outPut (pocket: Pocket): Unit = {
      outputCreated = true
      assert(pocket.id == "foo-bar")
    }
    pocketParser.parse(source, Set.empty, outPut, reporter)
    assert(outputCreated)
  }

  it should "replace multiple dash-chars with a single dash " in {
    val source = Source.fromString(getRecords("foo--------bar"))
    val pocketParser = new PocketParser(SourceRepo.readSourceFacts(factsSource), idFilter)

    var outputCreated = false
    def outPut (pocket: Pocket): Unit = {
      outputCreated = true
      assert(pocket.id == "foo-bar")
    }
    pocketParser.parse(source, Set.empty, outPut, reporter)
    assert(outputCreated)
  }
}