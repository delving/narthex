package specs

import mapping.SkosVocabulary
import mapping.SkosVocabulary.LabelSearch
import org.scalatest.{FlatSpec, Matchers}

class TestSkos extends FlatSpec with Matchers {

  "A Source" should "be readable" in {
    val example = getClass.getResource("/skos-example.xml")
    val vocabulary = SkosVocabulary(example.openStream())

    //    println(vocabulary)


    def searchConceptScheme(sought: String) = vocabulary.search("dut", sought, 3)

    val searches: List[LabelSearch] = List(
      "Europese wetgeving",
      "bezoeken",
      "wetgevingen"
    ).map(searchConceptScheme)

    searches.foreach(println)
  }

}
