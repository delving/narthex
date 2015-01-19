package specs

import java.io.File

import dataset.ProcessedRepo
import org.scalatestplus.play._
import play.api.test.Helpers._
import triplestore.TripleStoreClient

class TestTripleStore extends PlaySpec with OneAppPerSuite {

  "The processed repo should save sparql chunks " in {

    val home = new File(getClass.getResource(s"/processed").getFile)
    val repo = new ProcessedRepo(home)
    val reader = repo.createGraphReader(2)

    val chunk = reader.readChunk.get

    reader.close()

    val ts = new TripleStoreClient("http://localhost:3030/narthex-test")

    await(ts.update(chunk.toSparqlUpdate))

    def countGraphs = await(ts.query(s"SELECT DISTINCT ?g WHERE { GRAPH ?g { ?s ?p ?o } }")).size

    countGraphs must be(2)

//    val graphs: List[Map[String, String]] = await(ts.query(s"SELECT DISTINCT ?g WHERE { GRAPH ?g { ?s ?p ?o } }"))
//    graphs.foreach { map =>
//      println(s"found graph ${map.get("g")}")
//    }

    await(ts.update("DROP ALL"))

    countGraphs must be(0)
  }

}
