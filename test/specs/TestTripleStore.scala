package specs

import java.io.File

import dataset.DatasetInfo._
import dataset.{DatasetInfo, ProcessedRepo}
import org.scalatestplus.play._
import play.api.test.Helpers._
import triplestore.TripleStoreClient

class TestTripleStore extends PlaySpec with OneAppPerSuite {

  val TEST_STORE: String = "http://localhost:3030/narthex-test"
  val ts = new TripleStoreClient(TEST_STORE)

  def cleanStart() = {
    await(ts.update("DROP ALL"))
    countGraphs must be(0)
  }

  def countGraphs = await(ts.query(s"SELECT DISTINCT ?g WHERE { GRAPH ?g { ?s ?p ?o } }")).size

  "The processed repo should deliver sparql update chunks " in {
    cleanStart()
    val home = new File(getClass.getResource(s"/processed").getFile)
    val repo = new ProcessedRepo(home)
    val reader = repo.createGraphReader(2)
    val chunk = reader.readChunk.get
    reader.close()
    await(ts.update(chunk.toSparqlUpdate))
    countGraphs must be(2)
    //    val graphs: List[Map[String, String]] = await(ts.query(s"SELECT DISTINCT ?g WHERE { GRAPH ?g { ?s ?p ?o } }"))
    //    graphs.foreach { map =>
    //      println(s"found graph ${map.get("g")}")
    //    }
  }

  "The dataset info object should be able to interact with the store" in {
    cleanStart()
    val info = new DatasetInfo("gumby", ts)
    await(info.getProp(datasetPrefix)) must be(None)
    val model = await(info.setProp(datasetPrefix, "pfx"))
    //    RDFDataMgr.write(System.out, model, RDFFormat.NTRIPLES_UTF8)
    await(info.getProp(datasetPrefix)) must be(Some("pfx"))
    await(info.removeProp(datasetPrefix))
    await(info.getProp(datasetPrefix)) must be(None)
  }

}
