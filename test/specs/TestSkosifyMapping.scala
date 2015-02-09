package specs

import java.io.File

import dataset.{DsInfo, ProcessedRepo, SipRepo, SourceRepo}
import mapping.SkosMappingStore.SkosMapping
import mapping.{TermMappingStore, VocabInfo}
import org.ActorStore
import org.ActorStore.NXActor
import org.OrgContext._
import org.apache.commons.io.FileUtils
import org.scalatestplus.play._
import play.api.test.Helpers._
import record.PocketParser.Pocket
import services.FileHandling._
import services.{FileHandling, ProgressReporter}
import triplestore.GraphProperties._
import triplestore.Sparql._
import triplestore.{Sparql, TripleStore}

class TestSkosifyMapping extends PlaySpec with OneAppPerSuite {

  val ts = new TripleStore("http://localhost:3030/narthex-test", true)
  val dcType = "http://purl.org/dc/elements/1.1/type"
  lazy val actorPrefix = s"$NX_URI_PREFIX/actor"

  def cleanStart() = {
    await(ts.update("DROP ALL"))
    countGraphs must be(0)
  }

  def countGraphs = {
    val graphs = await(ts.query(s"SELECT DISTINCT ?g WHERE { GRAPH ?g { ?s ?p ?o } }")).map(m => m("g"))
    println(graphs.mkString("\n"))
    graphs.size
  }

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
    val home = new File(getClass.getResource("/ingest/sip_source").getFile)
    val sipsDir = FileHandling.clearDir(new File("/tmp/test-sip-source-sips"))
    FileUtils.copyDirectory(home, sipsDir)
    val datasetName = "frans_hals"
    val naveDomain = "http://nave"
    val sipRepo = new SipRepo(sipsDir, datasetName, naveDomain)
    val sourceDir = FileHandling.clearDir(new File("/tmp/test-sip-source-4"))
    val sipOpt = sipRepo.latestSipOpt
    sipOpt.isDefined must be(true)
    // create processed repo
    val actorStore = new ActorStore(ts)
    val admin = await(actorStore.authenticate("gumby", "geheim")).get
    val info = await(DsInfo.create(admin, "frans_hals", DsInfo.CharacterMapped, "icn", ts))
    val processedRepo = new ProcessedRepo(FileHandling.clearDir(new File("/tmp/test-processed-repo")), info)
    var sourceFile = processedRepo.createFile
    val sourceOutput = writer(sourceFile)
    // fill processed repo by mapping records
    sipOpt.foreach { sip =>
      sip.spec must be(Some("frans-hals-museum"))
      val source = sip.copySourceToTempFile
      source.isDefined must be(true)
      val sourceRepo = SourceRepo.createClean(sourceDir, SourceRepo.DELVING_SIP_SOURCE)
      sourceRepo.acceptFile(source.get, ProgressReporter())
      var mappedPockets = List.empty[Pocket]
      sip.createSipMapper.map { sipMapper =>
        def pocketCatcher(pocket: Pocket): Unit = {
          var mappedPocket = sipMapper.map(pocket)
          mappedPocket.map(_.writeTo(sourceOutput))
          mappedPockets = mappedPocket.get :: mappedPockets
        }
        sourceRepo.parsePockets(pocketCatcher, ProgressReporter())
      }
      mappedPockets.size must be(5)
    }
    sourceOutput.close()
    // push the mapped results to the triple store
    val graphReader = processedRepo.createGraphReader(None, ProgressReporter())
    while (graphReader.isActive) {
      graphReader.readChunk.map { chunk =>
        val update = chunk.toSparqlUpdate
        await(ts.update(update))
      }
    }
    countGraphs must be(7)
  }

  "Skosification must work" in {
    // mark a field as skosified
    countGraphs must be(7)
    val info = await(DsInfo.check("frans_hals", ts)).get
    await(info.addUriProp(skosField, dcType))
    info.getUriPropValueList(skosField) must be(List(dcType))
    await(info.removeUriProp(skosField, dcType))
    info.getUriPropValueList(skosField) must be(List())
    await(info.addUriProp(skosField, dcType))
    info.getUriPropValueList(skosField) must be(List(dcType))

    val skosifiedFields = await(ts.query(listSkosifiedFieldsQ)).map(Sparql.SkosifiedField(_))

    val skosificationCases = skosifiedFields.flatMap(sf => createCases(sf, await(ts.query(listSkosificationCasesQ(sf, 2)))))

    skosificationCases.map(println)

    skosificationCases.map { sc =>
      val graph = DsInfo.getSkosUri(sc.sf.datasetUri)
      val valueUri: String = sc.mintedUri
      val checkEntry = s"ASK { GRAPH <$graph> { <$valueUri> a <http://www.w3.org/2004/02/skos/core#Concept> } }"
      val syncedTrue: String = s"ASK { GRAPH <$graph> { <$valueUri> <$synced> true } }"
      val syncedFalse: String = s"ASK { GRAPH <$graph> { <$valueUri> <$synced> false } }"

      await(ts.ask(checkEntry)) must be(false)
      await(ts.update(sc.ensureSkosEntryQ))
      // synced should be false
      await(ts.ask(syncedFalse)) must be(true)
      await(ts.update(
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
      await(ts.update(sc.ensureSkosEntryQ))
      // it still should be true
      await(ts.ask(syncedTrue)) must be(true)
      await(ts.ask(checkEntry)) must be(true)
      await(ts.update(sc.literalToUriQ))
    }

    skosifiedFields.map { sf =>
      val remaining = await(ts.query(listSkosificationCasesQ(sf, 100)))
      remaining.size must be(3)
    }

    await(ts.ask(skosificationCasesExist(skosifiedFields.head))) must be(true)

    val finalCases = skosifiedFields.flatMap(sf => createCases(sf, await(ts.query(listSkosificationCasesQ(sf, 100)))))
    finalCases.map { sc =>
      await(ts.update(sc.ensureSkosEntryQ))
      await(ts.update(sc.literalToUriQ))
    }

    await(ts.ask(skosificationCasesExist(skosifiedFields.head))) must be(false)
  }

  "Mapping a skosified entry to a vocab entry" in {
    val actorStore = new ActorStore(ts)
    val admin = await(actorStore.authenticate("gumby", "geheim")).get
    val classyInfo = await(VocabInfo.create(admin, "gtaa_classy", ts))
    val classyFile = new File(getClass.getResource("/skos/Classificatie.xml").getFile)
    await(ts.dataPutXMLFile(classyInfo.dataUri, classyFile))
    val info = await(DsInfo.check("frans_hals", ts)).get
    info.getUriPropValueList(skosField) must be(List(dcType))
    val store = new TermMappingStore(info, ts)
    val uriA = "http://localhost:9000/resolve/dataset/frans_hals/schilderij%3B%20portret%20%7C%20painting%3B%20portrait"
    val uriB = "http://data.beeldengeluid.nl/gtaa/24829"
    val mapping = SkosMapping(admin, uriA, uriB)
    await(store.toggleMapping(mapping, classyInfo)) must be("added")
    await(store.getMappings) must be(Seq(List(uriA, uriB, "gtaa_classy")))
    await(store.toggleMapping(mapping, classyInfo)) must be("removed")
    await(store.getMappings) must be(Seq())
  }

}
