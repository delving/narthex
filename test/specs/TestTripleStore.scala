package specs

import java.io.File

import dataset.ProcessedRepo
import org.apache.jena.riot.{RDFDataMgr, RDFFormat}
import org.scalatestplus.play._

class TestTripleStore extends PlaySpec with OneAppPerSuite {


  "The triple store " in {

    val home = new File(getClass.getResource(s"/processed").getFile)
    val repo = new ProcessedRepo(home)

    val reader = repo.createDatasetReader(2)

    val dataset = reader.nextDataset.get

    RDFDataMgr.write(System.out, dataset, RDFFormat.TRIG_PRETTY)

//    val ts = new TripleStoreClient("http://localhost:3030/narthex")
//
//    val graphURI = "http://narthextest/testgraph"
//
//    val record =
//      s"""
//      |<rdf:Description xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:icn="http://www.icn.nl/schemas/icn/" xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#" rdf:about="http://acc.lodd2.delving.org/resource/document/frans/msch%2080-279">
//      |  <icn:technique>olieverf op karton | oil on cardboard</icn:technique>
//      |  <dc:title>Volendammers in the Snow</dc:title>
//      |  <dc:creator>Abraham Doorgeest</dc:creator>
//      |</rdf:Description>
//       """.stripMargin
//
//    val dataset = DatasetFactory.createMem()
//    val model = dataset.getNamedModel(graphURI).read(new StringReader(record), null, "RDF/XML")
//    RDFDataMgr.write(System.out, dataset, RDFFormat.NQUADS_UTF8)
//
//    val pr = await(ts.post(graphURI, model))
//
//    println(s"Post: $pr")
//
//    val fetched = await(ts.get(graphURI))
//
//    val triples = new StringWriter()
//
//    fetched.write(triples, "N-TRIPLES")
//
//    val quads = triples.toString.split("\n").map(t => s"<$graphURI> $t").mkString("\n")
//
//    println(s"Get:\n$triples")
//
//    println(s"Get:\n$quads")
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
