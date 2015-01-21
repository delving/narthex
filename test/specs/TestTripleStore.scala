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
    val sparql = chunk.toSparqlUpdate
    await(ts.update(sparql))
    countGraphs must be(2)
  }

  "The dataset info object should be able to interact with the store" in {
    cleanStart()
    val info = new DatasetInfo("gumby", ts)
    await(info.getProp(datasetMapTo)) must be(None)
    val model = await(info.setProps(
      datasetMapTo -> "pfx",
      datasetLanguage -> "nl"
    ))
    model.size() must be(3)
//    import org.apache.jena.riot.{RDFDataMgr, RDFFormat}
//    RDFDataMgr.write(System.out, model, RDFFormat.NTRIPLES_UTF8)
    await(info.getProp(datasetMapTo)) must be(Some("pfx"))
    await(info.removeProp(datasetMapTo))
    await(info.getProp(datasetMapTo)) must be(None)
    model.size() must be(2)
  }

}
