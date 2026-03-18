package services

import java.time.Instant
import java.time.temporal.ChronoUnit

import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import play.api.libs.json._

class DsInfoServiceSpec
    extends AnyWordSpec
    with Matchers
    with BeforeAndAfterAll
    with BeforeAndAfterEach {

  var pg: EmbeddedPostgres = _
  var dbService: DatabaseService = _
  var repo: PostgresDatasetRepository = _
  var service: DsInfoService = _

  override def beforeAll(): Unit = {
    pg = EmbeddedPostgres.builder().setPort(15434).start()
    val config = PostgresConfig(
      url = "jdbc:postgresql://localhost:15434/postgres",
      user = "postgres",
      password = "postgres"
    )
    dbService = new DatabaseService(config)
    dbService.initialize()
    repo = new PostgresDatasetRepository(dbService)
    service = new DsInfoService(repo)
  }

  override def afterAll(): Unit = {
    if (dbService != null) dbService.close()
    if (pg != null) pg.close()
  }

  override def beforeEach(): Unit = {
    val conn = dbService.getConnection()
    try {
      val stmt = conn.createStatement()
      stmt.execute("DELETE FROM audit_history")
      stmt.execute("DELETE FROM workflow_steps")
      stmt.execute("DELETE FROM workflows")
      stmt.execute("DELETE FROM dataset_indexing")
      stmt.execute("DELETE FROM dataset_state")
      stmt.execute("DELETE FROM dataset_mapping_config")
      stmt.execute("DELETE FROM dataset_harvest_schedule")
      stmt.execute("DELETE FROM dataset_harvest_config")
      stmt.execute("DELETE FROM datasets")
      stmt.close()
    } finally {
      conn.close()
    }
  }

  private val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)

  private def insertFullDataset(spec: String, orgId: String): Unit = {
    repo.createDataset(DatasetRecord(
      spec = spec,
      orgId = orgId,
      name = Some("Test Dataset"),
      description = Some("A test dataset"),
      owner = Some("admin"),
      datasetType = Some("oai"),
      character = Some("primary"),
      language = Some("en"),
      rights = Some("CC0"),
      aggregator = Some("Delving"),
      dataProviderUrl = Some("http://example.com"),
      edmType = Some("TEXT"),
      createdAt = now,
      updatedAt = now
    ))

    repo.upsertState(DatasetStateRecord(
      spec = spec,
      state = "PROCESSING",
      stateChangedAt = now,
      errorMessage = Some("test error"),
      errorTime = Some(now),
      currentOperation = Some("harvest"),
      operationStart = Some(now),
      operationTrigger = Some("user"),
      recordCount = 100,
      acquiredCount = 50,
      deletedCount = 5,
      sourceCount = 200,
      processedValid = 40,
      processedInvalid = 2,
      processedIncrementalValid = 10,
      processedIncrementalInvalid = 1,
      acquisitionMethod = Some("oai-pmh")
    ))

    repo.upsertHarvestConfig(HarvestConfigRecord(
      spec = spec,
      harvestType = Some("oai-pmh"),
      harvestUrl = Some("http://oai.example.com"),
      harvestDataset = Some("collection-1"),
      harvestPrefix = Some("oai_dc"),
      harvestRecord = Some("record"),
      harvestSearch = Some("search-query"),
      harvestDownloadUrl = Some("http://download.example.com"),
      recordRoot = Some("/record"),
      uniqueId = Some("/record/id"),
      continueOnError = true,
      errorThreshold = Some(100),
      idFilterType = Some("regex"),
      idFilterExpression = Some("^[A-Z]+")
    ))

    repo.upsertHarvestSchedule(HarvestScheduleRecord(
      spec = spec,
      delay = Some("6"),
      delayUnit = Some("hours"),
      incremental = true,
      previousTime = Some(now),
      lastFullHarvest = Some(now),
      lastIncrementalHarvest = Some(now)
    ))

    repo.upsertMappingConfig(MappingConfigRecord(
      spec = spec,
      mapToPrefix = Some("edm"),
      mappingSource = Some("groovy"),
      defaultMappingPrefix = Some("icn"),
      defaultMappingName = Some("default"),
      defaultMappingVersion = Some("1.0"),
      publishOaipmh = false,
      publishIndex = true,
      publishLod = false,
      categoriesInclude = true
    ))

    repo.upsertIndexing(IndexingRecord(
      spec = spec,
      recordsIndexed = Some(500),
      recordsExpected = Some(1000),
      orphansDeleted = Some(5),
      errorCount = Some(3),
      lastStatus = Some("completed"),
      lastMessage = Some("All done"),
      lastTimestamp = Some(now),
      lastRevision = Some(42)
    ))
  }

  "DsInfoService" should {

    "return None for non-existent dataset" in {
      service.getDatasetInfoJson("nonexistent") shouldBe None
    }

    "return dataset info JSON with core fields" in {
      insertFullDataset("test-spec", "org-1")

      val result = service.getDatasetInfoJson("test-spec")
      result shouldBe defined

      val json = result.get.as[JsObject]

      // Core identity fields (like toSimpleJson)
      (json \ "datasetSpec").as[String] shouldBe "test-spec"
      (json \ "spec").as[String] shouldBe "test-spec"
      (json \ "orgId").as[String] shouldBe "org-1"
    }

    "return dataset metadata fields matching DsInfo.webSocketFields names" in {
      insertFullDataset("test-spec", "org-1")
      val json = service.getDatasetInfoJson("test-spec").get.as[JsObject]

      // Dataset metadata fields
      (json \ "datasetName").as[String] shouldBe "Test Dataset"
      (json \ "datasetDescription").as[String] shouldBe "A test dataset"
      (json \ "datasetOwner").as[String] shouldBe "admin"
      (json \ "datasetLanguage").as[String] shouldBe "en"
      (json \ "datasetRights").as[String] shouldBe "CC0"
      (json \ "datasetType").as[String] shouldBe "oai"
      (json \ "datasetAggregator").as[String] shouldBe "Delving"
      (json \ "datasetDataProviderURL").as[String] shouldBe "http://example.com"
      (json \ "edmType").as[String] shouldBe "TEXT"
    }

    "return state fields as correct types" in {
      insertFullDataset("test-spec", "org-1")
      val json = service.getDatasetInfoJson("test-spec").get.as[JsObject]

      // Record counts (IntField in DsInfo)
      (json \ "datasetRecordCount").as[Int] shouldBe 100
      (json \ "sourceRecordCount").as[Int] shouldBe 200
      (json \ "acquiredRecordCount").as[Int] shouldBe 50
      (json \ "deletedRecordCount").as[Int] shouldBe 5
      (json \ "processedValid").as[Int] shouldBe 40
      (json \ "processedInvalid").as[Int] shouldBe 2
      (json \ "processedIncrementalValid").as[Int] shouldBe 10
      (json \ "processedIncrementalInvalid").as[Int] shouldBe 1

      // State string fields
      (json \ "acquisitionMethod").as[String] shouldBe "oai-pmh"
      (json \ "datasetErrorMessage").as[String] shouldBe "test error"
      (json \ "datasetCurrentOperation").as[String] shouldBe "harvest"
      (json \ "datasetOperationTrigger").as[String] shouldBe "user"
      (json \ "currentState").as[String] shouldBe "PROCESSING"

      // Timestamp fields should be ISO strings
      (json \ "datasetErrorTime").asOpt[String] shouldBe defined
      (json \ "datasetOperationStartTime").asOpt[String] shouldBe defined
    }

    "return harvest config fields" in {
      insertFullDataset("test-spec", "org-1")
      val json = service.getDatasetInfoJson("test-spec").get.as[JsObject]

      (json \ "harvestType").as[String] shouldBe "oai-pmh"
      (json \ "harvestURL").as[String] shouldBe "http://oai.example.com"
      (json \ "harvestDataset").as[String] shouldBe "collection-1"
      (json \ "harvestPrefix").as[String] shouldBe "oai_dc"
      (json \ "harvestRecord").as[String] shouldBe "record"
      (json \ "harvestSearch").as[String] shouldBe "search-query"
      (json \ "harvestDownloadURL").as[String] shouldBe "http://download.example.com"
      (json \ "recordRoot").as[String] shouldBe "/record"
      (json \ "uniqueId").as[String] shouldBe "/record/id"
    }

    "return harvest schedule fields" in {
      insertFullDataset("test-spec", "org-1")
      val json = service.getDatasetInfoJson("test-spec").get.as[JsObject]

      (json \ "harvestDelay").as[String] shouldBe "6"
      (json \ "harvestDelayUnit").as[String] shouldBe "hours"
      (json \ "harvestIncrementalMode").as[Boolean] shouldBe true
      (json \ "harvestPreviousTime").asOpt[String] shouldBe defined
      (json \ "lastFullHarvestTime").asOpt[String] shouldBe defined
      (json \ "lastIncrementalHarvestTime").asOpt[String] shouldBe defined
    }

    "return mapping config fields" in {
      insertFullDataset("test-spec", "org-1")
      val json = service.getDatasetInfoJson("test-spec").get.as[JsObject]

      (json \ "publishOAIPMH").as[Boolean] shouldBe false
      (json \ "publishIndex").as[Boolean] shouldBe true
      (json \ "publishLOD").as[Boolean] shouldBe false
      (json \ "categoriesInclude").as[Boolean] shouldBe true
      (json \ "datasetMapToPrefix").as[String] shouldBe "edm"
      (json \ "mappingSource").as[String] shouldBe "groovy"
      (json \ "defaultMappingPrefix").as[String] shouldBe "icn"
      (json \ "defaultMappingName").as[String] shouldBe "default"
    }

    "return indexing fields" in {
      insertFullDataset("test-spec", "org-1")
      val json = service.getDatasetInfoJson("test-spec").get.as[JsObject]

      (json \ "indexingRecordsIndexed").as[Int] shouldBe 500
      (json \ "indexingRecordsExpected").as[Int] shouldBe 1000
      (json \ "indexingOrphansDeleted").as[Int] shouldBe 5
      (json \ "indexingErrorCount").as[Int] shouldBe 3
      (json \ "indexingLastStatus").as[String] shouldBe "completed"
      (json \ "indexingLastMessage").as[String] shouldBe "All done"
      (json \ "indexingLastTimestamp").asOpt[String] shouldBe defined
      (json \ "indexingLastRevision").as[Int] shouldBe 42
    }

    "omit None/null fields from JSON" in {
      // Create dataset with minimal data (no state, harvest, etc.)
      repo.createDataset(DatasetRecord(
        spec = "minimal-spec",
        orgId = "org-1",
        createdAt = now,
        updatedAt = now
      ))

      val result = service.getDatasetInfoJson("minimal-spec")
      result shouldBe defined

      val json = result.get.as[JsObject]

      // Core fields should always be present
      (json \ "spec").as[String] shouldBe "minimal-spec"
      (json \ "orgId").as[String] shouldBe "org-1"

      // Optional fields should be absent, not null
      (json \ "datasetName").asOpt[String] shouldBe None
      (json \ "datasetErrorMessage").asOpt[String] shouldBe None
      (json \ "harvestType").asOpt[String] shouldBe None
      (json \ "indexingRecordsIndexed").asOpt[Int] shouldBe None
    }

    "list datasets as JSON" in {
      insertFullDataset("ds-1", "org-1")
      insertFullDataset("ds-2", "org-1")
      repo.createDataset(DatasetRecord(spec = "ds-3", orgId = "org-2", createdAt = now, updatedAt = now))

      val list = service.listDatasetsJson("org-1")
      list should have size 2

      // Verify light JSON has essential fields
      val first = list.head.as[JsObject]
      (first \ "spec").asOpt[String] shouldBe defined
      (first \ "currentState").asOpt[String] shouldBe defined
      (first \ "harvestType").asOpt[String] shouldBe defined
    }

    "list datasets full JSON with all field groups" in {
      insertFullDataset("ds-1", "org-1")
      insertFullDataset("ds-2", "org-1")

      val list = service.listDatasetsFullJson("org-1")
      list should have size 2

      val json = list.head.as[JsObject]

      // Should have fields from all groups
      (json \ "spec").asOpt[String] shouldBe defined
      (json \ "datasetName").asOpt[String] shouldBe defined
      (json \ "datasetRecordCount").asOpt[Int] shouldBe defined
      (json \ "harvestType").asOpt[String] shouldBe defined
      (json \ "harvestDelay").asOpt[String] shouldBe defined
      (json \ "publishOAIPMH").asOpt[Boolean] shouldBe defined
      (json \ "indexingRecordsIndexed").asOpt[Int] shouldBe defined
    }

    "not include soft-deleted datasets in listings" in {
      insertFullDataset("active-ds", "org-1")
      insertFullDataset("deleted-ds", "org-1")
      repo.softDeleteDataset("deleted-ds")

      val list = service.listDatasetsJson("org-1")
      list should have size 1
      (list.head.as[JsObject] \ "spec").as[String] shouldBe "active-ds"
    }

    "handle dataset with state but no other sub-records" in {
      repo.createDataset(DatasetRecord(
        spec = "partial-spec",
        orgId = "org-1",
        name = Some("Partial"),
        createdAt = now,
        updatedAt = now
      ))
      repo.upsertState(DatasetStateRecord(
        spec = "partial-spec",
        state = "IDLE",
        stateChangedAt = now,
        recordCount = 42
      ))

      val result = service.getDatasetInfoJson("partial-spec")
      result shouldBe defined

      val json = result.get.as[JsObject]
      (json \ "datasetName").as[String] shouldBe "Partial"
      (json \ "currentState").as[String] shouldBe "IDLE"
      (json \ "datasetRecordCount").as[Int] shouldBe 42
      // No harvest config, so harvest fields absent
      (json \ "harvestType").asOpt[String] shouldBe None
      // No indexing, so indexing fields absent
      (json \ "indexingRecordsIndexed").asOpt[Int] shouldBe None
    }

    "include computed fields for backward compatibility" in {
      insertFullDataset("test-spec", "org-1")
      val json = service.getDatasetInfoJson("test-spec").get.as[JsObject]

      // Backward-compat duplicate fields (like toSimpleJson's computedFields)
      (json \ "datasetMappingSource").asOpt[String] shouldBe defined
      (json \ "datasetDefaultMappingPrefix").asOpt[String] shouldBe defined
      (json \ "datasetDefaultMappingName").asOpt[String] shouldBe defined
      // Also the duplicate errorMessage field
      (json \ "errorMessage").asOpt[String] shouldBe defined
    }
  }
}
