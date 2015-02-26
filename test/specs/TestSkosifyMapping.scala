package specs

import java.io.File

import dataset.{DsInfo, ProcessedRepo}
import mapping.SkosMappingStore.SkosMapping
import mapping.{TermMappingStore, VocabInfo}
import org.ActorStore
import org.ActorStore.NXActor
import org.OrgContext._
import org.scalatestplus.play._
import play.api.test.Helpers._
import record.PocketParser.Pocket
import services.FileHandling._
import services.StringHandling.slugify
import services.{FileHandling, ProgressReporter}
import triplestore.GraphProperties._
import triplestore.Sparql
import triplestore.Sparql._

class TestSkosifyMapping extends PlaySpec with OneAppPerSuite with PrepareEDM with FakeTripleStore {

  val skosifiedPropertyUri = "http://purl.org/dc/elements/1.1/subject"
  lazy val actorPrefix = s"$NX_URI_PREFIX/actor"

  "The first user should authenticate and become administrator" in {
    cleanStart()

    // start fresh
    val actorStore = new ActorStore(ts)
    val admin = await(actorStore.authenticate("gumby", "secret gumby"))
    admin must be(Some(NXActor("gumby", None)))
    await(actorStore.createActor(admin.get, "pokey", "secret pokey")) must be(Some(NXActor("pokey", Some(s"$actorPrefix/gumby"))))
    //    await(us1.createActor(admin.get, "pokey", "secret pokey")) must be(None)
    actorStore.listActors(admin.get) must be(List("pokey"))
    await(actorStore.authenticate("pokey", "secret pokey")) must be(Some(NXActor("pokey", Some(s"$actorPrefix/gumby"))))

    // change a password
    await(actorStore.setPassword(admin.get, "geheim"))

    // this will have to get its data from the triple store again
    val store2 = new ActorStore(ts)
    await(store2.authenticate("gumby", "secret gumby")) must be(None)
    await(store2.authenticate("gumby", "geheim")) must be(Some(NXActor("gumby", None)))
    await(store2.authenticate("pokey", "secret pokey")) must be(Some(NXActor("pokey", Some(s"$actorPrefix/gumby"))))
    await(store2.authenticate("third-wheel", "can i join")) must be(None)

  }

  "A dataset should be loaded" in {
    // prepare for reading and mapping
    val sipRepo = createSipRepoFromDir(0)
    val latestSip = sipRepo.latestSipOpt.get
    // create processed repo
    val actorStore = new ActorStore(ts)
    val admin = await(actorStore.authenticate("gumby", "geheim")).get
    val info = await(DsInfo.createDsInfo(admin, "ton-smits-huis", DsInfo.CharacterMapped, "edm", ts))
    val processedRepo = new ProcessedRepo(FileHandling.clearDir(new File("/tmp/test-processed-repo")), info)
    var processedFile = processedRepo.createOutput.xmlFile
    val processedWriter = writer(processedFile)
    // fill processed repo by mapping records
    latestSip.spec must be(Some("ton-smits-huis"))
    val source = latestSip.copySourceToTempFile
    source.isDefined must be(true)
    val sourceRepo = createSourceRepo
    sourceRepo.acceptFile(source.get, ProgressReporter())
    var mappedPockets = List.empty[Pocket]
    latestSip.createSipMapper.map { sipMapper =>
      def pocketCatcher(pocket: Pocket): Unit = {
        var mappedPocket = sipMapper.executeMapping(pocket)
        mappedPocket.map(_.writeTo(processedWriter))
        mappedPockets = mappedPocket.get :: mappedPockets
      }
      sourceRepo.parsePockets(pocketCatcher, ProgressReporter())
    }
    mappedPockets.size must be(3)
    processedWriter.close()
    // push the mapped results to the triple store
    val graphReader = processedRepo.createGraphReader(None, ProgressReporter())
    while (graphReader.isActive) {
      graphReader.readChunk.map { chunk =>
        val update = chunk.sparqlUpdateQ
        await(ts.up.sparqlUpdate(update))
      }
    }
    countGraphs must be(5)
  }

  "Skosification must work" in {
    // mark a field as skosified
    countGraphs must be(5)
    val info = await(DsInfo.freshDsInfo("ton-smits-huis", ts)).get
    info.addUriProp(skosField, skosifiedPropertyUri)
    info.getUriPropValueList(skosField) must be(List(skosifiedPropertyUri))
    info.removeUriProp(skosField, skosifiedPropertyUri)
    info.getUriPropValueList(skosField) must be(List())
    info.addUriProp(skosField, skosifiedPropertyUri)
    info.getUriPropValueList(skosField) must be(List(skosifiedPropertyUri))

    val skosifiedFields = await(ts.query(listSkosifiedFieldsQ)).map(Sparql.SkosifiedField(_))

    val skosificationCases = skosifiedFields.flatMap(sf => createCases(sf, await(ts.query(listSkosificationCasesQ(sf, 2)))))

//    skosificationCases.map(c => println(s"SKOSIFICATION CASE: $c"))

    skosificationCases.map { sc =>
      val graph = DsInfo.getDsSkosUri(sc.sf.datasetUri)
      val valueUri: String = sc.mintedUri
      val checkEntry = s"ASK { GRAPH <$graph> { <$valueUri> a <http://www.w3.org/2004/02/skos/core#Concept> } }"
      val syncedTrue: String = s"ASK { GRAPH <$graph> { <$valueUri> <$synced> true } }"
      val syncedFalse: String = s"ASK { GRAPH <$graph> { <$valueUri> <$synced> false } }"

      await(ts.ask(checkEntry)) must be(false)
      await(ts.up.sparqlUpdate(sc.ensureSkosEntryQ))
      // synced should be false
      await(ts.ask(syncedFalse)) must be(true)
      await(ts.up.sparqlUpdate(
        s"""
           |WITH <$graph>
           |DELETE { <$valueUri> <$synced> false }
           |INSERT { <$valueUri> <$synced> true }
           |WHERE {  <$valueUri> <$synced> false }
           |""".stripMargin
      ))
      // now synced is forced to true
      await(ts.ask(syncedTrue)) must be(true)
      await(ts.ask(syncedFalse)) must be(false)
      await(ts.up.sparqlUpdate(sc.ensureSkosEntryQ))
      // it still should be true
      await(ts.ask(syncedTrue)) must be(true)
      await(ts.ask(checkEntry)) must be(true)
      await(ts.up.sparqlUpdate(sc.literalToUriQ))
    }

    skosifiedFields.map { sf =>
      val remaining = await(ts.query(listSkosificationCasesQ(sf, 100)))
      remaining.size must be(17)
    }

    await(ts.ask(skosificationCasesExist(skosifiedFields.head))) must be(true)

    val finalCases = skosifiedFields.flatMap(sf => createCases(sf, await(ts.query(listSkosificationCasesQ(sf, 100)))))
    finalCases.map { sc =>
      await(ts.up.sparqlUpdate(sc.ensureSkosEntryQ))
      await(ts.up.sparqlUpdate(sc.literalToUriQ))
    }

    await(ts.ask(skosificationCasesExist(skosifiedFields.head))) must be(false)
  }

  "Mapping a skosified entry to a vocab entry" in {
    val actorStore = new ActorStore(ts)
    val admin = await(actorStore.authenticate("gumby", "geheim")).get
    val classyInfo = await(VocabInfo.createVocabInfo(admin, "gtaa_classy", ts))
    val classyFile = new File(getClass.getResource("/skos/Classificatie.xml").getFile)
    await(ts.up.dataPutXMLFile(classyInfo.dataUri, classyFile))
    val info = await(DsInfo.freshDsInfo("ton-smits-huis", ts)).get
    info.getUriPropValueList(skosField) must be(List(skosifiedPropertyUri))
    val store = new TermMappingStore(info, ts)
    // todo: nothing checks whether this literal or uri string are legitimate
    val literalString = "vogels"
    val uriA = s"http://localhost:9000/resolve/dataset/ton-smits-huis/${slugify(literalString)}"
    val uriB = "http://data.beeldengeluid.nl/gtaa/24829"
    val mapping = SkosMapping(admin, uriA, uriB)
    await(store.toggleMapping(mapping, classyInfo)) must be("added")
    await(store.getMappings(categories = false)) must be(Seq(List(uriA, uriB, "gtaa_classy")))
    await(store.toggleMapping(mapping, classyInfo)) must be("removed")
    await(store.getMappings(categories = false)) must be(Seq())
  }

  "Mapping a skosified entry to a category" in {
    val actorStore = new ActorStore(ts)
    val admin = await(actorStore.authenticate("gumby", "geheim")).get
    val catInfo = await(VocabInfo.createVocabInfo(admin, "categories", ts))
    val catFile = new File(getClass.getResource("/categories/Categories.xml").getFile)
    await(ts.up.dataPutXMLFile(catInfo.dataUri, catFile))
    val dsInfo = await(DsInfo.freshDsInfo("ton-smits-huis", ts)).get
    dsInfo.getUriPropValueList(skosField) must be(List(skosifiedPropertyUri))
    val store = new TermMappingStore(dsInfo, ts)
    val literalString = "vogels"
    val uriA = s"http://localhost:9000/resolve/dataset/ton-smits-huis/${slugify(literalString)}"
    val uriB1 = "http://schemas.delving.eu/narthex/terms/category/wtns"
    val uriB2 = "http://schemas.delving.eu/narthex/terms/category/schi"
    val mapping1 = SkosMapping(admin, uriA, uriB1)
    val mapping2 = SkosMapping(admin, uriA, uriB2)
    await(store.toggleMapping(mapping1, catInfo)) must be("added")
    await(store.getMappings(categories = true)) must be(Seq(List(uriA, uriB1, "categories")))
    await(store.toggleMapping(mapping2, catInfo)) must be("added")
    dsInfo.termCategoryMap(catInfo) must be(Map("http://localhost:9000/resolve/dataset/ton-smits-huis/vogels" -> List("WTNS", "SCHI")))
    await(store.toggleMapping(mapping1, catInfo)) must be("removed")
    await(store.getMappings(categories = true)) must be(Seq(List(uriA, uriB2, "categories")))
    await(store.toggleMapping(mapping2, catInfo)) must be("removed")
    await(store.getMappings(categories = true)) must be(Seq())
  }

  "Delete a dataset" in {
    DsInfo.withDsInfo("ton-smits-huis") { dsInfo =>
      await(dsInfo.dropDataset)
//      println("dropped")
    }
    val list = await(DsInfo.listDsInfo(ts))
//    println(s"After Delete: $list")
    list must be(List())
  }

}
