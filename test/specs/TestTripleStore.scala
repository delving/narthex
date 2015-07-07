package specs

import java.io.File

import dataset.DsInfo._
import dataset.{DsInfo, ProcessedRepo}
import org.ActorStore
import org.joda.time.DateTime
import org.scalatestplus.play._
import play.api.libs.json.Json
import play.api.test.Helpers._
import services.ProgressReporter
import triplestore.GraphProperties._

class TestTripleStore extends PlaySpec with OneAppPerSuite with FakeTripleStore {

  "The processed repo should deliver sparql update chunks " in {
    cleanStart()
    val store: ActorStore = new ActorStore()
    val admin = await(store.authenticate("gumby", "secret gumby")).get
    val info = await(DsInfo.createDsInfo(admin, "gumby-set", CharacterMapped, "gfx"))
    val home = new File(getClass.getResource(s"/processed").getFile)
    val repo = new ProcessedRepo(home, info)
    val saveTime = new DateTime()
    val reader = repo.createGraphReader(None, saveTime, ProgressReporter())
    val chunk = reader.readChunkOpt.get
    reader.close()
    val sparql = chunk.sparqlUpdateQ
    await(ts.up.sparqlUpdate(sparql))
    countGraphs must be(14)
  }

  "The dataset info object should be able to interact with the store" in {
    cleanStart()
    val store: ActorStore = new ActorStore()
    val admin = await(store.authenticate("gumby", "secret gumby")).get
    val dsInfo = await(DsInfo.createDsInfo(admin, "gumby-set", CharacterMapped, "gfx"))
    dsInfo.getLiteralProp(datasetMapToPrefix) must be(Some("gfx"))
    dsInfo.setSingularLiteralProps(
      datasetMapToPrefix -> "pfx",
      datasetLanguage -> "nl"
    )
    dsInfo.getModel.size() must be(11)
    dsInfo.getLiteralProp(datasetMapToPrefix) must be(Some("pfx"))
    dsInfo.removeLiteralProp(datasetMapToPrefix)
    dsInfo.getLiteralProp(datasetMapToPrefix) must be(None)
    dsInfo.getModel.size() must be(10)

    dsInfo.setSingularLiteralProps(datasetMapToPrefix -> "pfx2")

    // uri prop
    dsInfo.getLiteralPropList(skosField) must be(List.empty)
    dsInfo.addLiteralPropToList(skosField, "http://purl.org/dc/elements/1.1/type")
    dsInfo.getLiteralPropList(skosField) must be(List("http://purl.org/dc/elements/1.1/type"))
    dsInfo.addLiteralPropToList(skosField, "http://purl.org/dc/elements/1.1/creator")

    def testTwo(di: DsInfo) = {
      val two = di.getLiteralPropList(skosField)
      two.size must be(2)
      two.contains("http://purl.org/dc/elements/1.1/type") must be(true)
      two.contains("http://purl.org/dc/elements/1.1/creator") must be(true)
    }

    // only tests the contained model
    testTwo(dsInfo)

    // a fresh one that has to fetch anew
    val fresh: DsInfo = await(DsInfo.freshDsInfo("gumby-set")).get

    fresh.getLiteralProp(datasetMapToPrefix) must be(Some("pfx2"))
    testTwo(fresh)

    //    println(Json.prettyPrint(dsInfoWrites.writes(fresh)))

    val second = await(DsInfo.createDsInfo(admin, "pokey-set", CharacterMapped, ""))

    val infoList = await(listDsInfo)

    infoList.foreach { info =>
      println(Json.prettyPrint(dsInfoWrites.writes(info)))
    }
  }

}
