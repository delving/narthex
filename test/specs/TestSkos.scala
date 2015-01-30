package specs

import java.io.File

import mapping.SkosMappingStore.SkosMapping
import mapping.SkosVocabulary.LabelSearch
import mapping.{SkosInfo, SkosMappingStore, Skosification}
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

  "Mapping toggle should work" in {
    cleanStart()

    // have an actor create two Skos vocabs
    val actorStore = new ActorStore(ts)
    val admin = await(actorStore.authenticate("gumby", "secret gumby")).get
    val genreInfo = await(SkosInfo.create(admin, "gtaa_genre", ts))
    val genreFile = new File(getClass.getResource("/skos/Genre.xml").getFile)
    await(ts.dataPutXMLFile(genreInfo.dataUri, genreFile)) must be(None)
    val classyInfo = await(SkosInfo.create(admin, "gtaa_classy", ts))
    val classyFile = new File(getClass.getResource("/skos/Classificatie.xml").getFile)
    await(ts.dataPutXMLFile(classyInfo.dataUri, classyFile)) must be(None)
    countGraphs must be(5)

    // check the stats
    val stats = await(genreInfo.getStatistics)
    val count = stats("conceptCount")
    count must be(117)

    // try a search
    val vocab = genreInfo.vocabulary
    def searchConceptScheme(sought: String) = vocab.search("nl", sought, 3)
    val searches: List[LabelSearch] = List(
      "nieuwsbulletin"
    ).map(searchConceptScheme)

    searches.foreach(labelSearch => println(Json.prettyPrint(Json.toJson(labelSearch))))

    val skosMappings = new SkosMappingStore(genreInfo, classyInfo, ts)

    val genreA = "http://data.beeldengeluid.nl/gtaa/30103"
    val classyA = "http://data.beeldengeluid.nl/gtaa/24896"
    val mappingA = SkosMapping(admin, genreA, classyA)

    // toggle while checking
    await(skosMappings.toggleMapping(mappingA)) must be(None)
    await(skosMappings.getMappings) must be(Seq((genreA, classyA)))
    await(skosMappings.toggleMapping(mappingA)) must be(None)
    await(skosMappings.getMappings) must be(Seq.empty[(String, String)])
    await(skosMappings.toggleMapping(mappingA)) must be(None)
    await(skosMappings.getMappings) must be(Seq((genreA, classyA)))

    val genreB = "http://data.beeldengeluid.nl/gtaa/30420"
    val classyB = "http://data.beeldengeluid.nl/gtaa/24903"
    val mappingB = SkosMapping(admin, genreB, classyB)
    await(skosMappings.toggleMapping(mappingB)) must be(None)
    await(skosMappings.getMappings).sortBy(_._1) must be(Seq((genreA, classyA),(genreB, classyB)))
  }

}

