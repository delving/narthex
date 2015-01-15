package specs

import java.io.StringReader

import com.hp.hpl.jena.rdf.model.ModelFactory
import org.scalatestplus.play._
import play.api.test.Helpers._
import triplestore.TripleStoreClient

class TestTripleStore extends PlaySpec with OneAppPerSuite {


  "The triple store " in {

    val ts = new TripleStoreClient("http://localhost:3030/narthex")

    val graphURI = "http://narthextest/testgraph"

    val record =
      s"""
      |<rdf:Description xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:icn="http://www.icn.nl/schemas/icn/" xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#" rdf:about="http://acc.lodd2.delving.org/resource/document/frans/msch%2080-279">
      |  <icn:technique>olieverf op karton | oil on cardboard</icn:technique>
      |  <dc:title>Volendammers in the Snow</dc:title>
      |  <dc:creator>Abraham Doorgeest</dc:creator>
      |</rdf:Description>
       """.stripMargin

    val model = ModelFactory.createDefaultModel().read(new StringReader(record), null, "RDF/XML")

    val pr = await(ts.post(graphURI, model))

    println(s"Post: $pr")

    val fetched = await(ts.get(graphURI))

    println(s"Get: $fetched")
  }



  /*
  "The triple store client query" in {

    val ts = new TripleStoreClient("http://localhost:3030/narthex")

    val tech = "http://www.icn.nl/schemas/icn/technique"
    val oldValue = "foto | photo"
    val newValue = "PHOO TOO"

    val q = ts.query(
      s"""
       |
       | select ?s
       | where {
       |    ?s <$tech> "$oldValue"
       | }
       |
       """.stripMargin
    )

    val qr = await(q)

    println(s"First Response:\n${qr.take(5).mkString("\n")}")

    val firstS = qr.head("s")

    println(s"First S: $firstS")

    val uq =
      s"""
       |
       | delete {
       |   <$firstS> <$tech> "$oldValue"
       | }
       | insert {
       |   <$firstS> <$tech> "$newValue"
       | }
       | where {
       |   <$firstS> <$tech> "$oldValue"
       | }
       |
       """.stripMargin

    println(uq)

    val u = ts.update(uq)

    val ur = await(u)

    println(s"Update response: $ur")
  }


   */
}
