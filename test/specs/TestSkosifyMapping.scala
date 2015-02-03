package specs

import java.io.File

import dataset.{DsInfo, ProcessedRepo, SipRepo, SourceRepo}
import mapping.Skosification
import org.ActorStore
import org.ActorStore.NXActor
import org.apache.commons.io.FileUtils
import org.scalatestplus.play._
import play.api.test.Helpers._
import record.PocketParser.Pocket
import services.FileHandling._
import services.{FileHandling, ProgressReporter}
import triplestore.GraphProperties._
import triplestore.TripleStore

class TestSkosifyMapping extends PlaySpec with OneAppPerSuite with Skosification {

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

  "The first user should authenticate and become administrator" in {
    cleanStart()

    // start fresh
    val actorStore = new ActorStore(ts)
    val admin = await(actorStore.authenticate("gumby", "secret gumby"))
    admin must be(Some(NXActor("gumby", None)))
    await(actorStore.createActor(admin.get, "pokey", "secret pokey")) must be(Some(NXActor("pokey", Some("http://localhost:9000/resolve/actor/gumby"))))
    //    await(us1.createActor(admin.get, "pokey", "secret pokey")) must be(None)
    actorStore.listActors(admin.get) must be(List("pokey"))
    await(actorStore.authenticate("pokey", "secret pokey")) must be(Some(NXActor("pokey", Some("http://localhost:9000/resolve/actor/gumby"))))

    // change a password
    await(actorStore.setPassword(admin.get, "geheim"))

    // this will have to get its data from the triple store again
    val store2 = new ActorStore(ts)
    await(store2.authenticate("gumby", "secret gumby")) must be(None)
    await(store2.authenticate("gumby", "geheim")) must be(Some(NXActor("gumby", None)))
    await(store2.authenticate("pokey", "secret pokey")) must be(Some(NXActor("pokey", Some("http://localhost:9000/resolve/actor/gumby"))))
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
    val processedRepo = new ProcessedRepo(FileHandling.clearDir(new File("/tmp/test-processed-repo")), info.dsUri)
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
    await(info.addUriProp(skosField, "http://purl.org/dc/elements/1.1/type"))

    val skosifiedFields = await(ts.query(listSkosifiedFields)).map(SkosifiedField(_))

    val skosificationCases = skosifiedFields.flatMap(sf => createCases(sf, await(ts.query(listSkosificationCases(sf, 2)))))

    skosificationCases.map(println)

    skosificationCases.map { sc =>
      val graph = sc.skosifieldField.datasetSkosUri
      val valueUri: String = sc.mintedUri
      val checkEntry = s"ASK { GRAPH <$graph> { <$valueUri> a <http://www.w3.org/2004/02/skos/core#Concept> } }"
      val syncedTrue: String = s"ASK { GRAPH <$graph> { <$valueUri> <$synced> true } }"
      val syncedFalse: String = s"ASK { GRAPH <$graph> { <$valueUri> <$synced> false } }"

      await(ts.ask(checkEntry)) must be(false)
      await(ts.update(sc.ensureSkosEntry))
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
      await(ts.update(sc.ensureSkosEntry))
      // it still should be true
      await(ts.ask(syncedTrue)) must be(true)
      await(ts.ask(checkEntry)) must be(true)
      await(ts.update(sc.changeLiteralToUri))
    }

    skosifiedFields.map { sf =>
      val remaining = await(ts.query(listSkosificationCases(sf, 100)))
      remaining.size must be(3)
    }

    await(ts.ask(skosificationCasesExist(skosifiedFields.head))) must be(true)

    val finalCases = skosifiedFields.flatMap(sf => createCases(sf, await(ts.query(listSkosificationCases(sf, 100)))))
    finalCases.map { sc =>
      await(ts.update(sc.ensureSkosEntry))
      await(ts.update(sc.changeLiteralToUri))
    }

    await(ts.ask(skosificationCasesExist(skosifiedFields.head))) must be(false)
  }

}


//  val userHome: String = "/tmp/narthex-user"
//  System.setProperty("user.home", userHome)
//  deleteQuietly(new File(userHome))
//  DateTimeZone.setDefault(DateTimeZone.forID("Europe/Amsterdam"))
//
//  //    "A NodeRepo" should "allow terminology mapping" in {
//  //      val repo = Repo("test@narthex.delving.org")
//  //      repo.create("password")
//  //      val datasetContext = repo.datasetContext("pretend-file.xml.gz")
//  //
//  //      def createNodeRepo(path: String) = {
//  //        val nodeDir = path.split('/').toList.foldLeft(datasetContext.dir)((file, tag) => new File(file, Repo.tagToDirectory(tag)))
//  //        nodeDir.mkdirs()
//  //        datasetContext.nodeRepo(path).get
//  //      }
//  //
//  //      Repo.startBaseX()
//  //
//  //      datasetContext.setMapping(TermMapping("a", "http://gumby.com/gumby-is-a-fink", "cusses", "finky"))
//  //      datasetContext.setMapping(TermMapping("bb", "http://gumby.com/pokey", "cusses", "horsey"))
//  //      datasetContext.setMapping(TermMapping("a", "http://gumby.com/gumby", "cusses", "clayman"))
//  //
//  //      datasetContext.getMappings.toString() should be("List(TermMapping(a,http://gumby.com/gumby,cusses,clayman), TermMapping(bb,http://gumby.com/pokey,cusses,horsey))")
//  //      datasetContext.getMapping("a") should be("http://gumby.com/gumby")
//  //    }
//
//  "A transformer" should "insert an enrichment" in {
//
//    val domain = "http://localhost:9000"
//    val filePrefix = "/gerald_delving_eu/RCE_Beeldbank"
//    val recordRoot = "/record"
//
//    val mappings = Map(
//      s"$domain/resource/thesaurusenrichment$filePrefix$recordRoot/inner/content.subject/Glas%20in%20loodraam" -> TargetConcept("uri", "vocab", "attrib", "glasinloody", "dude", new DateTime(0)),
//      s"$domain/resource/thesaurusenrichment$filePrefix$recordRoot/inner/content.subject" -> TargetConcept("uri", "vocab", "attrib", "close but no cigar", "dude", new DateTime(0))
//    )
//
//    println(s"Mappings: $mappings")
//
//    val storedString =
//      s"""
//        |<$POCKET id="666" mod="2014-09-14T10:11:38.398+02:00" xmlns:very="http://veryother.org/#">
//        |  <record>
//        |    <inner>
//        |      <content.subject>Glas in loodraam</content.subject>
//        |    </inner>
//        |    <very:other>Ignore</very:other>
//        |    <empty/>
//        |  </record>
//        |</$POCKET>
//      """.stripMargin.trim
//
//    val expectedString =
//      """
//        |<record xmlns:cc="http://creativecommons.org/ns#" xmlns:skos="http://www.w3.org/2004/02/skos/core#" xmlns:rdfs="http://www.w3.org/2000/01/rdf-schema#" xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#" xmlns:very="http://veryother.org/#">
//        |  <inner>
//        |    <content.subject>
//        |      <rdf:Description rdf:about="http://localhost:9000/resource/thesaurusenrichment/gerald_delving_eu/RCE_Beeldbank/record/inner/content.subject/Glas%20in%20loodraam">
//        |        <rdf:type rdf:resource="http://schemas.delving.org/ThesaurusEnrichment"/>
//        |        <rdfs:label>Glas in loodraam</rdfs:label>
//        |        <skos:prefLabel>glasinloody</skos:prefLabel>
//        |        <skos:exactMatch rdf:resource="uri"/>
//        |        <skos:ConceptScheme>vocab</skos:ConceptScheme>
//        |        <cc:attributionURL rdf:resource="uri"/>
//        |        <cc:attributionName>attrib</cc:attributionName>
//        |        <skos:note>Mapped in Narthex by dude on 1970-01-01T01:00:00+01:00</skos:note>
//        |      </rdf:Description>
//        |    </content.subject>
//        |  </inner>
//        |  <very:other>Ignore</very:other>
//        |  <empty/>
//        |</record>
//      """.stripMargin.trim
//
//    val parser = new EnrichmentParser(domain, filePrefix, mappings)
//    val record = parser.parse(storedString)
//    val recordText = record(0).text.toString().trim
//
//    println("this is coming out ===")
//    println(recordText)
//    val factory = DocumentBuilderFactory.newInstance()
//    factory.setValidating(false)
//    factory.setNamespaceAware(true)
//    val builder = factory.newDocumentBuilder()
//    val document = builder.parse(new InputSource(new ByteArrayInputStream(recordText.getBytes)))
//    println("=== that was it")
//
//    recordText should be(expectedString)
//  }