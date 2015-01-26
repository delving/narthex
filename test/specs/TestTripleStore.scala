package specs

import java.io.File

import dataset.DsInfo._
import dataset.{DsInfo, ProcessedRepo}
import org.scalatestplus.play._
import play.api.libs.json.Json
import play.api.test.Helpers._
import triplestore.TripleStore

class TestTripleStore extends PlaySpec with OneAppPerSuite {

  val TEST_STORE: String = "http://localhost:3030/narthex-test"
  val ts = new TripleStore(TEST_STORE)

  def cleanStart() = {
    await(ts.update("DROP ALL"))
    countGraphs must be(0)
  }

  def countGraphs = await(ts.query(s"SELECT DISTINCT ?g WHERE { GRAPH ?g { ?s ?p ?o } }")).size

  "The processed repo should deliver sparql update chunks " in {
    cleanStart()
    val home = new File(getClass.getResource(s"/processed").getFile)
    val repo = new ProcessedRepo(home, "http://dataset.uri")
    val reader = repo.createGraphReader(2)
    val chunk = reader.readChunk.get
    reader.close()
    val sparql = chunk.toSparqlUpdate
    await(ts.update(sparql))
    countGraphs must be(2)
  }

  "The dataset info object should be able to interact with the store" in {
    cleanStart()
    val info = new DsInfo("gumby", ts)
    info.getLiteralProp(datasetMapToPrefix) must be(None)
    val model = await(info.setLiteralProps(
      datasetMapToPrefix -> "pfx",
      datasetLanguage -> "nl"
    ))
    model.size() must be(3)
    info.getLiteralProp(datasetMapToPrefix) must be(Some("pfx"))
    await(info.removeLiteralProp(datasetMapToPrefix))
    info.getLiteralProp(datasetMapToPrefix) must be(None)
    model.size() must be(2)

    await(info.setLiteralProps(datasetMapToPrefix -> "pfx2"))

    // uri prop
    info.getUriProps(skosField) must be(List.empty)
    await(info.setUriProp(skosField, "http://purl.org/dc/elements/1.1/type"))
    info.getUriProps(skosField) must be(List("http://purl.org/dc/elements/1.1/type"))
    await(info.setUriProp(skosField, "http://purl.org/dc/elements/1.1/creator"))

    def testTwo(di: DsInfo) = {
      val two = di.getUriProps(skosField)
      two.size must be(2)
      two.contains("http://purl.org/dc/elements/1.1/type") must be(true)
      two.contains("http://purl.org/dc/elements/1.1/creator") must be(true)
    }

    // only tests the contained model
    testTwo(info)

    // a fresh one that has to fetch anew
    val fresh: DsInfo = new DsInfo("gumby", ts)

    fresh.getLiteralProp(datasetMapToPrefix) must be(Some("pfx2"))
    testTwo(fresh)

    //    println(Json.prettyPrint(dsInfoWrites.writes(fresh)))

    val second = new DsInfo("pokey", ts)

    val infoList = await(listDsInfo(ts))

    infoList.foreach { info =>
      println(Json.prettyPrint(dsInfoWrites.writes(info)))
    }
  }

}
