package specs

import org.UserStore
import org.UserStore.NXUser
import org.scalatestplus.play._
import play.api.test.Helpers._
import triplestore.TripleStoreClient

class TestUsersMapping extends PlaySpec with OneAppPerSuite {

  val TEST_STORE: String = "http://localhost:3030/narthex-test"
  val ts = new TripleStoreClient(TEST_STORE)

  def cleanStart() = {
    await(ts.update("DROP ALL"))
    countGraphs must be(0)
  }

  def countGraphs = await(ts.query(s"SELECT DISTINCT ?g WHERE { GRAPH ?g { ?s ?p ?o } }")).size

  "The first user should authenticate and become administrator" in {
//    cleanStart()
    val userStore = new UserStore(ts)
    val firstUser = await(userStore.authenticate("gumby", "pokey"))
    firstUser must be(Some(NXUser("gumby", administrator = true)))
  }

}


//  "A SKOS file" should "be readable by Jena" in {
//    val example = getClass.getResource("/skos-example.xml")
//    val conSchemes = ConceptScheme.read(example.openStream(), "example")
//    conSchemes.map(s => println(s"Scheme: $s"))
//    val conScheme = conSchemes.head
//
//    def searchConceptScheme(sought: String) = conScheme.search("nl", sought, 3)
//
//    val searches: List[LabelSearch] = List(
//      "Europese wetgeving",
//      "bezoeken",
//      "wetgevingen"
//    ).map(searchConceptScheme)
//
//    searches.foreach(labelSearch => println(Json.prettyPrint(Json.toJson(labelSearch))))
//
//  }

//
//  val userHome: String = "/tmp/narthex-user"
//  System.setProperty("user.home", userHome)
//  deleteQuietly(new File(userHome))
//  DateTimeZone.setDefault(DateTimeZone.forID("Europe/Amsterdam"))
//
//  //    "A NodeRepo" should "allow terminology mapping" in {
//  //      val repo = Repo("test@narthex.delving.org")
//  //      repo.create("password")
//  //      val datasetRepo = repo.datasetRepo("pretend-file.xml.gz")
//  //
//  //      def createNodeRepo(path: String) = {
//  //        val nodeDir = path.split('/').toList.foldLeft(datasetRepo.dir)((file, tag) => new File(file, Repo.tagToDirectory(tag)))
//  //        nodeDir.mkdirs()
//  //        datasetRepo.nodeRepo(path).get
//  //      }
//  //
//  //      Repo.startBaseX()
//  //
//  //      datasetRepo.setMapping(TermMapping("a", "http://gumby.com/gumby-is-a-fink", "cusses", "finky"))
//  //      datasetRepo.setMapping(TermMapping("bb", "http://gumby.com/pokey", "cusses", "horsey"))
//  //      datasetRepo.setMapping(TermMapping("a", "http://gumby.com/gumby", "cusses", "clayman"))
//  //
//  //      datasetRepo.getMappings.toString() should be("List(TermMapping(a,http://gumby.com/gumby,cusses,clayman), TermMapping(bb,http://gumby.com/pokey,cusses,horsey))")
//  //      datasetRepo.getMapping("a") should be("http://gumby.com/gumby")
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