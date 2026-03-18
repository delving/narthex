package services

import java.time.Instant
import java.time.temporal.ChronoUnit

import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import play.api.libs.json.Json

class OaiSourceRepositorySpec
    extends AnyWordSpec
    with Matchers
    with BeforeAndAfterAll
    with BeforeAndAfterEach {

  var pg: EmbeddedPostgres = _
  var dbService: DatabaseService = _
  var repo: OaiSourceRepository = _

  override def beforeAll(): Unit = {
    pg = EmbeddedPostgres.builder().setPort(15434).start()
    val config = PostgresConfig(
      url = "jdbc:postgresql://localhost:15434/postgres",
      user = "postgres",
      password = "postgres"
    )
    dbService = new DatabaseService(config)
    dbService.initialize()
    repo = new OaiSourceRepository(dbService)
  }

  override def afterAll(): Unit = {
    if (dbService != null) dbService.close()
    if (pg != null) pg.close()
  }

  override def beforeEach(): Unit = {
    val conn = dbService.getConnection()
    try {
      val stmt = conn.createStatement()
      stmt.execute("DELETE FROM oai_source_set_counts")
      stmt.execute("DELETE FROM dataset_harvest_config")
      stmt.execute("DELETE FROM oai_sources")
      stmt.close()
    } finally {
      conn.close()
    }
  }

  private val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)

  "OaiSourceRepository" should {

    "create and retrieve a source" in {
      val source = OaiSourceRecord(
        id = "test-source",
        orgId = "org-1",
        name = "Test OAI Source",
        url = "http://oai.example.com/oai",
        defaultMetadataPrefix = "edm",
        defaultAggregator = Some("Delving"),
        defaultPrefix = "edm",
        defaultEdmType = Some("IMAGE"),
        harvestDelay = Some(7),
        harvestDelayUnit = Some("DAYS"),
        harvestIncremental = true,
        mappingRules = """[{"pattern":".*foto$","prefix":"edm","mappingName":"foto"}]""",
        ignoredSets = List("test-set-1", "test-set-2"),
        enabled = true,
        lastChecked = Some(now),
        createdAt = now
      )
      repo.createSource(source)

      val result = repo.getSource("test-source")
      result shouldBe defined
      val r = result.get
      r.id shouldBe "test-source"
      r.orgId shouldBe "org-1"
      r.name shouldBe "Test OAI Source"
      r.url shouldBe "http://oai.example.com/oai"
      r.defaultMetadataPrefix shouldBe "edm"
      r.defaultAggregator shouldBe Some("Delving")
      r.defaultPrefix shouldBe "edm"
      r.defaultEdmType shouldBe Some("IMAGE")
      r.harvestDelay shouldBe Some(7)
      r.harvestDelayUnit shouldBe Some("DAYS")
      r.harvestIncremental shouldBe true
      Json.parse(r.mappingRules) shouldBe Json.parse("""[{"pattern":".*foto$","prefix":"edm","mappingName":"foto"}]""")
      r.ignoredSets shouldBe List("test-set-1", "test-set-2")
      r.enabled shouldBe true
      r.lastChecked shouldBe defined
      r.createdAt shouldBe now
    }

    "list sources by org" in {
      repo.createSource(OaiSourceRecord(
        id = "src-1", orgId = "org-1", name = "Source 1",
        url = "http://oai1.example.com", createdAt = now
      ))
      repo.createSource(OaiSourceRecord(
        id = "src-2", orgId = "org-1", name = "Source 2",
        url = "http://oai2.example.com", createdAt = now
      ))
      repo.createSource(OaiSourceRecord(
        id = "src-3", orgId = "org-other", name = "Source 3",
        url = "http://oai3.example.com", createdAt = now
      ))

      val orgSources = repo.listSources("org-1")
      orgSources should have size 2
      orgSources.map(_.id).toSet shouldBe Set("src-1", "src-2")

      val otherSources = repo.listSources("org-other")
      otherSources should have size 1
      otherSources.head.id shouldBe "src-3"
    }

    "update a source" in {
      repo.createSource(OaiSourceRecord(
        id = "upd-source", orgId = "org-1", name = "Original Name",
        url = "http://old.example.com", createdAt = now
      ))

      val updated = OaiSourceRecord(
        id = "upd-source", orgId = "org-1", name = "Updated Name",
        url = "http://new.example.com",
        defaultMetadataPrefix = "oai_dc",
        defaultAggregator = Some("New Aggregator"),
        defaultPrefix = "icn",
        defaultEdmType = Some("TEXT"),
        harvestDelay = Some(14),
        harvestDelayUnit = Some("DAYS"),
        harvestIncremental = true,
        mappingRules = """[{"pattern":".*","prefix":"icn","mappingName":"default"}]""",
        ignoredSets = List("ignored-1"),
        enabled = false,
        createdAt = now
      )
      repo.updateSource(updated)

      val result = repo.getSource("upd-source")
      result shouldBe defined
      val r = result.get
      r.name shouldBe "Updated Name"
      r.url shouldBe "http://new.example.com"
      r.defaultAggregator shouldBe Some("New Aggregator")
      r.defaultPrefix shouldBe "icn"
      r.defaultEdmType shouldBe Some("TEXT")
      r.harvestDelay shouldBe Some(14)
      r.harvestDelayUnit shouldBe Some("DAYS")
      r.harvestIncremental shouldBe true
      r.ignoredSets shouldBe List("ignored-1")
      r.enabled shouldBe false
    }

    "delete a source" in {
      repo.createSource(OaiSourceRecord(
        id = "del-source", orgId = "org-1", name = "To Delete",
        url = "http://del.example.com", createdAt = now
      ))

      repo.getSource("del-source") shouldBe defined
      repo.deleteSource("del-source")
      repo.getSource("del-source") shouldBe None
    }

    "add ignored sets" in {
      repo.createSource(OaiSourceRecord(
        id = "ign-source", orgId = "org-1", name = "Ignore Test",
        url = "http://ign.example.com",
        ignoredSets = List("existing-1"),
        createdAt = now
      ))

      repo.addIgnoredSets("ign-source", List("new-1", "new-2"))

      val result = repo.getSource("ign-source")
      result shouldBe defined
      result.get.ignoredSets should contain allOf("existing-1", "new-1", "new-2")
    }

    "remove ignored sets" in {
      repo.createSource(OaiSourceRecord(
        id = "rem-source", orgId = "org-1", name = "Remove Test",
        url = "http://rem.example.com",
        ignoredSets = List("keep-1", "remove-1", "remove-2", "keep-2"),
        createdAt = now
      ))

      repo.removeIgnoredSets("rem-source", List("remove-1", "remove-2"))

      val result = repo.getSource("rem-source")
      result shouldBe defined
      result.get.ignoredSets should contain allOf("keep-1", "keep-2")
      result.get.ignoredSets should not contain "remove-1"
      result.get.ignoredSets should not contain "remove-2"
    }

    "update lastChecked" in {
      repo.createSource(OaiSourceRecord(
        id = "check-source", orgId = "org-1", name = "Check Test",
        url = "http://check.example.com",
        lastChecked = None,
        createdAt = now
      ))

      repo.getSource("check-source").get.lastChecked shouldBe None

      repo.updateLastChecked("check-source")

      val result = repo.getSource("check-source")
      result shouldBe defined
      result.get.lastChecked shouldBe defined
    }

    "save and retrieve set counts" in {
      repo.createSource(OaiSourceRecord(
        id = "cnt-source", orgId = "org-1", name = "Count Test",
        url = "http://cnt.example.com", createdAt = now
      ))

      val counts = List(
        SetCountRecord(
          sourceId = "cnt-source",
          setSpec = "set-a",
          recordCount = Some(100),
          error = None,
          verifiedAt = now
        ),
        SetCountRecord(
          sourceId = "cnt-source",
          setSpec = "set-b",
          recordCount = None,
          error = Some("timeout"),
          verifiedAt = now
        ),
        SetCountRecord(
          sourceId = "cnt-source",
          setSpec = "set-c",
          recordCount = Some(0),
          error = None,
          verifiedAt = now
        )
      )
      repo.saveSetCounts(counts)

      val result = repo.getSetCounts("cnt-source")
      result should have size 3

      val setA = result.find(_.setSpec == "set-a").get
      setA.recordCount shouldBe Some(100)
      setA.error shouldBe None

      val setB = result.find(_.setSpec == "set-b").get
      setB.recordCount shouldBe None
      setB.error shouldBe Some("timeout")

      val setC = result.find(_.setSpec == "set-c").get
      setC.recordCount shouldBe Some(0)
      setC.error shouldBe None
    }

    "clear set counts" in {
      repo.createSource(OaiSourceRecord(
        id = "clr-source", orgId = "org-1", name = "Clear Test",
        url = "http://clr.example.com", createdAt = now
      ))

      repo.saveSetCounts(List(
        SetCountRecord(sourceId = "clr-source", setSpec = "set-x", recordCount = Some(50), verifiedAt = now)
      ))
      repo.getSetCounts("clr-source") should have size 1

      repo.clearSetCounts("clr-source")
      repo.getSetCounts("clr-source") shouldBe empty
    }

    "return empty list for non-existent source" in {
      repo.getSource("non-existent") shouldBe None
      repo.listSources("non-existent-org") shouldBe empty
      repo.getSetCounts("non-existent") shouldBe empty
    }

    "handle source with default values" in {
      repo.createSource(OaiSourceRecord(
        id = "default-source", orgId = "org-1", name = "Defaults",
        url = "http://default.example.com", createdAt = now
      ))

      val result = repo.getSource("default-source")
      result shouldBe defined
      val r = result.get
      r.defaultMetadataPrefix shouldBe "oai_dc"
      r.defaultAggregator shouldBe None
      r.defaultPrefix shouldBe "edm"
      r.defaultEdmType shouldBe None
      r.harvestDelay shouldBe None
      r.harvestDelayUnit shouldBe None
      r.harvestIncremental shouldBe false
      r.mappingRules shouldBe "[]"
      r.ignoredSets shouldBe Nil
      r.enabled shouldBe true
      r.lastChecked shouldBe None
    }
  }
}
