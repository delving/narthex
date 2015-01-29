package specs

import java.io.File

import mapping.SkosVocabulary.LabelSearch
import mapping.{SkosInfo, Skosification}
import org.ActorStore
import org.scalatestplus.play._
import play.api.libs.json.Json
import play.api.test.Helpers._
import triplestore.TripleStore

class TestSkos extends PlaySpec with OneAppPerSuite with Skosification {

  val ts = new TripleStore("http://localhost:3030/narthex-test", true)

  def cleanStart() = {
    await(ts.update("DROP ALL"))
    countGraphs must be(0)
  }

  def countGraphs = {
    val graphs = await(ts.query(s"SELECT DISTINCT ?g WHERE { GRAPH ?g { ?s ?p ?o } }")).map(m => m("g"))
    println(graphs.mkString("\n"))
    graphs.size
  }

  "A sample SKOS vocabulary should be loaded" in {
    cleanStart()
    val actorStore = new ActorStore(ts)
    val admin = await(actorStore.authenticate("gumby", "secret gumby")).get
    val info = await(SkosInfo.create(admin, "gtaa_genre", ts))
    val skosFile = new File(getClass.getResource("/skos/Genre.xml").getFile)
    val posted = await(ts.dataPutXMLFile(info.dataUri, skosFile))
    posted must be(None)
    countGraphs must be(3)
    val stats = await(info.getStatistics)
    val count = stats("conceptCount")
    count must be(117)
    //    println(Json.prettyPrint(Json.toJson(stats)))
    val vocab = info.vocabulary
    def searchConceptScheme(sought: String) = vocab.search("nl", sought, 3)
    val searches: List[LabelSearch] = List(
      "nieuwsbulletin"
    ).map(searchConceptScheme)

    searches.foreach(labelSearch => println(Json.prettyPrint(Json.toJson(labelSearch))))

  }

}

