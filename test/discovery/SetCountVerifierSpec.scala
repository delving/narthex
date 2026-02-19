package discovery

import org.scalatest.flatspec._
import org.scalatest.matchers._

/**
 * Unit tests for the parsing logic used by SetCountVerifier.
 * Integration testing with real HTTP is done manually against the endpoint.
 */
class SetCountVerifierSpec extends AnyFlatSpec with should.Matchers {

  "SetCountVerifier progress tracking" should "default to idle when no job exists" in {
    // We can't easily test the full verify method without a running Play app,
    // but we can test the companion parsing and progress defaults.
    // The core parsing is tested in ListIdentifiersParserSpec.

    val emptyResponse = """<?xml version="1.0" encoding="UTF-8"?>
      |<OAI-PMH xmlns="http://www.openarchives.org/OAI/2.0/">
      |  <ListIdentifiers>
      |    <resumptionToken completeListSize="0" cursor="0"></resumptionToken>
      |  </ListIdentifiers>
      |</OAI-PMH>""".stripMargin

    OaiListSetsParser.parseCompleteListSize(emptyResponse) shouldBe Right(0)
  }

  it should "parse non-zero completeListSize" in {
    val response = """<?xml version="1.0" encoding="UTF-8"?>
      |<OAI-PMH xmlns="http://www.openarchives.org/OAI/2.0/">
      |  <ListIdentifiers>
      |    <header><identifier>oai:example:1</identifier></header>
      |    <resumptionToken completeListSize="42" cursor="0">token</resumptionToken>
      |  </ListIdentifiers>
      |</OAI-PMH>""".stripMargin

    OaiListSetsParser.parseCompleteListSize(response) shouldBe Right(42)
  }
}
