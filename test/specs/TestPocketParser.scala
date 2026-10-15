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

  it should "collapse space-dash-space into a single dash" in {
    // Regression: "XN1012 - 1" must not become "XN1012--1" in the pocket-mapper id
    val source = Source.fromString(getRecords("XN1012 - 1"))
    val pocketParser = new PocketParser(SourceRepo.readSourceFacts(factsSource), idFilter, mock[OrgContext])
    var captured: Option[String] = None
    def outPut(pocket: Pocket): Unit = { captured = Some(pocket.id) }
    pocketParser.parse(source, Set.empty, outPut, reporter)
    assert(captured.contains("XN1012-1"))
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

  "Pocket.getText" should "clean the id in the pocket tag" in {
    val text = "<pocket id=\"foo:bar\" mod=\"x\">\n<r>foo:bar</r>\n</pocket>\n"
    Pocket("foo:bar", text, Map.empty).getText should include ("<pocket id=\"foo-bar\" ")
  }

  it should "stay linear on a long single-line record" in {
    // Regression: brocade-cat-ehc spent ~1 hour per run generating 2418
    // pockets; the old ".*?<pocket id=..." pattern backtracked quadratically
    // over every line when the tag had no space after the id (the normal
    // "<pocket id=\"x\">" form written by PocketParser).
    val text = "<pocket id=\"x\">\n<r>" + ("a" * 200000) + "</r>\n</pocket>\n"
    val start = System.nanoTime()
    Pocket("x", text, Map.empty).getText shouldBe text
    ((System.nanoTime() - start) / 1000000) should be < 500L
  }

}
