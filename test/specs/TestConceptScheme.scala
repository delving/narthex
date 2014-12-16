package specs

import mapping.ConceptScheme
import mapping.ConceptScheme.LabelSearch
import org.scalatest.{FlatSpec, Matchers}
import play.api.libs.json.Json

class TestConceptScheme extends FlatSpec with Matchers {

  "A SKOS file" should "be readable by Jena" in {
    val example = getClass.getResource("/skos-example.xml")
    val conSchemes = ConceptScheme.read(example.openStream())
    conSchemes.map(s => println(s"Scheme: $s"))
    val conScheme = conSchemes.head

    def searchConceptScheme(sought: String) = conScheme.search("nl", sought, 3)

    val searches: List[LabelSearch] = List(
      "Europese wetgeving",
      "bezoeken",
      "wetgevingen"
    ).map(searchConceptScheme)

    searches.foreach(labelSearch => println(Json.prettyPrint(Json.toJson(labelSearch))))

  }
}
