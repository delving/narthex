package specs

import java.io.{ByteArrayInputStream, File}
import javax.xml.parsers.DocumentBuilderFactory

import org.apache.commons.io.FileUtils._
import org.joda.time.DateTime
import org.scalatest.{FlatSpec, Matchers}
import org.xml.sax.InputSource
import record.EnrichmentParser
import record.EnrichmentParser._
import record.PocketParser._

class TestTermMatching extends FlatSpec with Matchers {

    val userHome: String = "/tmp/narthex-user"
    System.setProperty("user.home", userHome)
    deleteQuietly(new File(userHome))

//    "A NodeRepo" should "allow terminology mapping" in {
//      val repo = Repo("test@narthex.delving.org")
//      repo.create("password")
//      val datasetRepo = repo.datasetRepo("pretend-file.xml.gz")
//
//      def createNodeRepo(path: String) = {
//        val nodeDir = path.split('/').toList.foldLeft(datasetRepo.dir)((file, tag) => new File(file, Repo.tagToDirectory(tag)))
//        nodeDir.mkdirs()
//        datasetRepo.nodeRepo(path).get
//      }
//
//      Repo.startBaseX()
//
//      datasetRepo.setMapping(TermMapping("a", "http://gumby.com/gumby-is-a-fink", "cusses", "finky"))
//      datasetRepo.setMapping(TermMapping("bb", "http://gumby.com/pokey", "cusses", "horsey"))
//      datasetRepo.setMapping(TermMapping("a", "http://gumby.com/gumby", "cusses", "clayman"))
//
//      datasetRepo.getMappings.toString() should be("List(TermMapping(a,http://gumby.com/gumby,cusses,clayman), TermMapping(bb,http://gumby.com/pokey,cusses,horsey))")
//      datasetRepo.getMapping("a") should be("http://gumby.com/gumby")
//    }

  "A transformer" should "insert an enrichment" in {

    val domain = "http://localhost:9000"
    val filePrefix = "/gerald_delving_eu/RCE_Beeldbank"
    val recordRoot = "/record"

    val mappings = Map(
      s"$domain/resource/thesaurusenrichment$filePrefix$recordRoot/inner/content.subject/Glas%20in%20loodraam" -> TargetConcept("uri", "vocab", "glasinloody", "dude", new DateTime(0)),
      s"$domain/resource/thesaurusenrichment$filePrefix$recordRoot/inner/content.subject" -> TargetConcept("uri", "vocab", "close but no cigar", "dude", new DateTime(0))
    )

    println(s"Mappings: $mappings")

    val storedString =
      s"""
        |<$POCKET id="666" mod="2014-09-14T10:11:38.398+02:00" xmlns:very="http://veryother.org/#">
        |  <record>
        |    <inner>
        |      <content.subject>Glas in loodraam</content.subject>
        |    </inner>
        |    <very:other>Ignore</very:other>
        |    <empty/>
        |  </record>
        |</$POCKET>
      """.stripMargin.trim

    val expectedString =
      """
        |<record xmlns:skos="http://www.w3.org/2004/02/skos/core#" xmlns:rdfs="http://www.w3.org/2000/01/rdf-schema#" xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#" xmlns:very="http://veryother.org/#">
        |  <inner>
        |    <content.subject>
        |      <rdf:Description rdf:about="http://localhost:9000/resource/thesaurusenrichment/gerald_delving_eu/RCE_Beeldbank/record/inner/content.subject/Glas%20in%20loodraam">
        |        <rdf:type rdf:resource="http://schemas.delving.org/ThesaurusEnrichment"/>
        |        <rdfs:label>Glas in loodraam</rdfs:label>
        |        <skos:prefLabel>glasinloody</skos:prefLabel>
        |        <skos:exactMatch rdf:resource="uri"/>
        |        <skos:ConceptScheme>vocab</skos:ConceptScheme>
        |        <skos:note>Mapped in Narthex by dude on 1970-01-01T02:00:00+02:00</skos:note>
        |      </rdf:Description>
        |    </content.subject>
        |  </inner>
        |  <very:other>Ignore</very:other>
        |  <empty/>
        |</record>
      """.stripMargin.trim

    val parser = new EnrichmentParser(domain, filePrefix, mappings)
    val record = parser.parse(storedString)
    val recordText = record(0).text.toString().trim

    println("this is coming out ===")
    println(recordText)
    val factory = DocumentBuilderFactory.newInstance()
    factory.setValidating(false)
    factory.setNamespaceAware(true)
    val builder = factory.newDocumentBuilder()
    val document = builder.parse(new InputSource(new ByteArrayInputStream(recordText.getBytes)))
    println("=== that was it")

    recordText should be(expectedString)
  }
}
