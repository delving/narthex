package specs

import org.scalatest.flatspec._
import org.scalatest.matchers._
import org.scalatestplus.mockito.MockitoSugar
import dataset.SourceRepo
import dataset.SourceRepo.{IdFilter, SourceFacts}
import organization.OrgContext
import record.PocketParser
import record.PocketParser.Pocket
import services.FakeProgressReporter

import scala.io.Source

class TestPocketParser extends AnyFlatSpec with should.Matchers with MockitoSugar {
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



  behavior of "an object id containing an illegal character"

  it should "replace the colon with a dash" in {
    runTestFor(":")
  }

  it should "replace the ( and ) with a dash" in {
    runTestFor("(")
    runTestFor(")")
  }

  it should "replace the space with a dash" in {
    runTestFor(" ")
  }

  it should "replace the plus with a dash" in {
    runTestFor("+")
  }

  it should "replace multiple dash-chars with a single dash" in {
    runTestFor("------")
  }

  def runTestFor(illegalChar: String): Unit = {
    val source = Source.fromString(getRecords(s"foo${illegalChar}bar"))
    val pocketParser = new PocketParser(SourceRepo.readSourceFacts(factsSource), idFilter, mock[OrgContext])

    var outputCreated = false
    def outPut (pocket: Pocket): Unit = {
      outputCreated = true
      assert(pocket.id == "foo-bar")
    }
    pocketParser.parse(source, Set.empty, outPut, reporter)
    assert(outputCreated)
  }

}
