package controllers

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class MappingFactsSpec extends AnyFlatSpec with Matchers {

  "mappingFacts" should "read every fact of a rec-mapping, empty values included" in {
    val xml =
      <rec-mapping prefix="edm">
        <facts>
          <entry><string>provider</string><string>Erfgoed Brabant</string></entry>
          <entry><string>type</string><string></string></entry>
          <entry><string>language</string><string>NL</string></entry>
        </facts>
      </rec-mapping>
    AppController.mappingFacts(xml) shouldBe Map(
      "provider" -> "Erfgoed Brabant", "type" -> "", "language" -> "NL")
  }

  "dynOptsXml" should "keep the dyn-opts that skos:Concept[x] paths depend on" in {
    val xml =
      <rec-mapping prefix="edm">
        <dyn-opts>
          <dyn-opt path="/edm:RDF/skos:Concept" value="gewijdAan"/>
        </dyn-opts>
      </rec-mapping>
    AppController.dynOptsXml(xml) should include ("""value="gewijdAan"""")
    AppController.dynOptsXml(<rec-mapping/>) shouldBe "<dyn-opts/>"
  }
}
