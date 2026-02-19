package discovery

import org.scalatest.flatspec._
import org.scalatest.matchers._

class ListIdentifiersParserSpec extends AnyFlatSpec with should.Matchers {

  "OaiListSetsParser" should "parse completeListSize from resumptionToken" in {
    val xml = """<?xml version="1.0" encoding="UTF-8"?>
      |<OAI-PMH xmlns="http://www.openarchives.org/OAI/2.0/">
      |  <responseDate>2026-02-19T12:00:00Z</responseDate>
      |  <request verb="ListIdentifiers">http://example.com/oai</request>
      |  <ListIdentifiers>
      |    <header><identifier>oai:example:1</identifier></header>
      |    <resumptionToken completeListSize="1523" cursor="0">token123</resumptionToken>
      |  </ListIdentifiers>
      |</OAI-PMH>""".stripMargin

    val result = OaiListSetsParser.parseCompleteListSize(xml)
    result shouldBe Right(1523)
  }

  it should "return 0 for empty set with completeListSize=0" in {
    val xml = """<?xml version="1.0" encoding="UTF-8"?>
      |<OAI-PMH xmlns="http://www.openarchives.org/OAI/2.0/">
      |  <responseDate>2026-02-19T12:00:00Z</responseDate>
      |  <request verb="ListIdentifiers">http://example.com/oai</request>
      |  <ListIdentifiers>
      |    <resumptionToken completeListSize="0" cursor="0"></resumptionToken>
      |  </ListIdentifiers>
      |</OAI-PMH>""".stripMargin

    val result = OaiListSetsParser.parseCompleteListSize(xml)
    result shouldBe Right(0)
  }

  it should "return 0 when no resumptionToken and no headers" in {
    val xml = """<?xml version="1.0" encoding="UTF-8"?>
      |<OAI-PMH xmlns="http://www.openarchives.org/OAI/2.0/">
      |  <responseDate>2026-02-19T12:00:00Z</responseDate>
      |  <request verb="ListIdentifiers">http://example.com/oai</request>
      |  <ListIdentifiers>
      |  </ListIdentifiers>
      |</OAI-PMH>""".stripMargin

    val result = OaiListSetsParser.parseCompleteListSize(xml)
    result shouldBe Right(0)
  }

  it should "count headers when no completeListSize attribute" in {
    val xml = """<?xml version="1.0" encoding="UTF-8"?>
      |<OAI-PMH xmlns="http://www.openarchives.org/OAI/2.0/">
      |  <responseDate>2026-02-19T12:00:00Z</responseDate>
      |  <request verb="ListIdentifiers">http://example.com/oai</request>
      |  <ListIdentifiers>
      |    <header><identifier>oai:example:1</identifier></header>
      |    <header><identifier>oai:example:2</identifier></header>
      |  </ListIdentifiers>
      |</OAI-PMH>""".stripMargin

    val result = OaiListSetsParser.parseCompleteListSize(xml)
    result shouldBe Right(2)
  }

  it should "return 0 for noRecordsMatch error" in {
    val xml = """<?xml version="1.0" encoding="UTF-8"?>
      |<OAI-PMH xmlns="http://www.openarchives.org/OAI/2.0/">
      |  <responseDate>2026-02-19T12:00:00Z</responseDate>
      |  <error code="noRecordsMatch">No records match</error>
      |</OAI-PMH>""".stripMargin

    val result = OaiListSetsParser.parseCompleteListSize(xml)
    result shouldBe Right(0)
  }
}
