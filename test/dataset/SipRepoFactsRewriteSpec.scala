package dataset

import java.io.{ByteArrayInputStream, File}

import dataset.SipFactory.SipGenerationFacts
import eu.delving.metadata.{RecDefTree, RecMapping}
import org.scalatest.flatspec._
import org.scalatest.matchers._

/**
 * Regression tests for Planio #3117 / default-mapping facts propagation.
 *
 * The bug: mapping XML imported from DefaultMappingRepo templates carried the
 * template-source dataset's spec and an empty dataProviderURL into every new
 * dataset's SIP. The fix rewrites the `<facts>` block from the target
 * dataset's SipGenerationFacts before the mapping goes into the SIP zip.
 */
class SipRepoFactsRewriteSpec extends AnyFlatSpec with should.Matchers {

  private def recDefTree(): RecDefTree = {
    val recDefFile = new File(getClass.getResource("/edm/factory/edm/edm_5.2.3_record-definition.xml").getFile)
    Sip.loadRecDefTree(recDefFile)
  }

  private def templateXml(templateSpec: String, emptyProviderUrl: Boolean = true): String =
    s"""<rec-mapping prefix="edm" schemaVersion="5.2.3" locked="true">
       |  <facts>
       |    <entry>
       |      <string>spec</string>
       |      <string>$templateSpec</string>
       |    </entry>
       |    <entry>
       |      <string>orgId</string>
       |      <string>orig-org</string>
       |    </entry>
       |    <entry>
       |      <string>dataProviderURL</string>
       |      <string>${if (emptyProviderUrl) "" else "http://old.example.org"}</string>
       |    </entry>
       |    <entry>
       |      <string>name</string>
       |      <string>Template Name</string>
       |    </entry>
       |  </facts>
       |  <functions/>
       |  <node-mappings/>
       |</rec-mapping>
       |""".stripMargin

  private def targetFacts: SipGenerationFacts = SipGenerationFacts(
    spec = "enb-406-bidprentje",
    prefix = "edm",
    name = "Bidprentjes ENB 406",
    provider = "Erfgoed Brabant",
    dataProvider = "Heemkundekring Op 't Goede Spoor",
    dataProviderURL = "https://www.goedespoorwaspik.nl/",
    language = "NL",
    rights = "http://rightsstatements.org/vocab/InC/1.0/",
    edmType = "IMAGE",
    dataType = "",
    orgId = "brabantcloud"
  )

  private def parseFacts(bytes: Array[Byte]): Map[String, String] = {
    val tree = recDefTree()
    val in = new ByteArrayInputStream(bytes)
    try {
      val mapping = RecMapping.read(in, tree)
      import scala.jdk.CollectionConverters._
      mapping.getFacts.asScala.toMap
    } finally in.close()
  }

  "Sip.rewriteFactsInMappingXml" should "replace the template spec with the target spec" in {
    val bytesOpt = Sip.rewriteFactsInMappingXml(
      templateXml("enb-test-bidprentje"),
      recDefTree(),
      Sip.factsToMap(targetFacts)
    )
    bytesOpt shouldBe defined
    val facts = parseFacts(bytesOpt.get)
    facts.get("spec") shouldBe Some("enb-406-bidprentje")
  }

  it should "populate dataProviderURL when the template has an empty value" in {
    val bytesOpt = Sip.rewriteFactsInMappingXml(
      templateXml("enb-test-bidprentje", emptyProviderUrl = true),
      recDefTree(),
      Sip.factsToMap(targetFacts)
    )
    bytesOpt shouldBe defined
    val facts = parseFacts(bytesOpt.get)
    facts.get("dataProviderURL") shouldBe Some("https://www.goedespoorwaspik.nl/")
  }

  it should "overwrite a stale dataProviderURL" in {
    val bytesOpt = Sip.rewriteFactsInMappingXml(
      templateXml("enb-test-bidprentje", emptyProviderUrl = false),
      recDefTree(),
      Sip.factsToMap(targetFacts)
    )
    bytesOpt shouldBe defined
    val facts = parseFacts(bytesOpt.get)
    facts.get("dataProviderURL") shouldBe Some("https://www.goedespoorwaspik.nl/")
  }

  it should "overwrite orgId as well" in {
    val bytesOpt = Sip.rewriteFactsInMappingXml(
      templateXml("enb-test-bidprentje"),
      recDefTree(),
      Sip.factsToMap(targetFacts)
    )
    val facts = parseFacts(bytesOpt.get)
    facts.get("orgId") shouldBe Some("brabantcloud")
  }

  it should "return None when the customMappingXml can't be parsed" in {
    val result = Sip.rewriteFactsInMappingXml(
      "<not-rec-mapping/>",
      recDefTree(),
      Sip.factsToMap(targetFacts)
    )
    result shouldBe None
  }
}
