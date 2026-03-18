package services

import java.time.Instant
import java.time.temporal.ChronoUnit

import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres

class PostgresDatasetRepositorySpec
    extends AnyWordSpec
    with Matchers
    with BeforeAndAfterAll
    with BeforeAndAfterEach {

  var pg: EmbeddedPostgres = _
  var dbService: DatabaseService = _
  var repo: PostgresDatasetRepository = _

  override def beforeAll(): Unit = {
    pg = EmbeddedPostgres.builder().setPort(15433).start()
    val config = PostgresConfig(
      url = "jdbc:postgresql://localhost:15433/postgres",
      user = "postgres",
      password = "postgres"
    )
    dbService = new DatabaseService(config)
    dbService.initialize()
    repo = new PostgresDatasetRepository(dbService)
  }

  override def afterAll(): Unit = {
    if (dbService != null) dbService.close()
    if (pg != null) pg.close()
  }

  override def beforeEach(): Unit = {
    val conn = dbService.getConnection()
    try {
      val stmt = conn.createStatement()
      // Delete in correct FK order
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

  "PostgresDatasetRepository" should {

    "create and retrieve a dataset" in {
      val ds = DatasetRecord(
        spec = "test-spec",
        orgId = "org-1",
        name = Some("Test Dataset"),
        description = Some("A test dataset"),
        owner = Some("admin"),
        datasetType = Some("oai"),
        character = Some("primary"),
        language = Some("en"),
        rights = Some("CC0"),
        tags = List("history", "culture"),
        aggregator = Some("Delving"),
        dataProviderUrl = Some("http://example.com"),
        edmType = Some("TEXT"),
        createdAt = now,
        updatedAt = now
      )
      repo.createDataset(ds)

      val result = repo.getDataset("test-spec")
      result shouldBe defined
      val r = result.get
      r.spec shouldBe "test-spec"
      r.orgId shouldBe "org-1"
      r.name shouldBe Some("Test Dataset")
      r.description shouldBe Some("A test dataset")
      r.owner shouldBe Some("admin")
      r.datasetType shouldBe Some("oai")
      r.character shouldBe Some("primary")
      r.language shouldBe Some("en")
      r.rights shouldBe Some("CC0")
      r.tags shouldBe List("history", "culture")
      r.aggregator shouldBe Some("Delving")
      r.dataProviderUrl shouldBe Some("http://example.com")
      r.edmType shouldBe Some("TEXT")
      r.deletedAt shouldBe None
    }

    "list active datasets" in {
      repo.createDataset(DatasetRecord(spec = "ds-1", orgId = "org-1", createdAt = now, updatedAt = now))
      repo.createDataset(DatasetRecord(spec = "ds-2", orgId = "org-1", createdAt = now, updatedAt = now))
      repo.createDataset(DatasetRecord(spec = "ds-3", orgId = "org-1", createdAt = now, updatedAt = now))

      repo.softDeleteDataset("ds-2")

      val active = repo.listActiveDatasets("org-1")
      active.map(_.spec).toSet shouldBe Set("ds-1", "ds-3")
    }

    "upsert and retrieve dataset state" in {
      repo.createDataset(DatasetRecord(spec = "state-spec", orgId = "org-1", createdAt = now, updatedAt = now))

      val state = DatasetStateRecord(
        spec = "state-spec",
        state = "HARVESTING",
        stateChangedAt = now,
        currentOperation = Some("harvest"),
        operationStart = Some(now),
        operationTrigger = Some("user"),
        recordCount = 100,
        acquiredCount = 50,
        sourceCount = 200,
        processedValid = 40,
        processedInvalid = 2,
        acquisitionMethod = Some("oai-pmh")
      )
      repo.upsertState(state)

      val result = repo.getState("state-spec")
      result shouldBe defined
      val s = result.get
      s.state shouldBe "HARVESTING"
      s.currentOperation shouldBe Some("harvest")
      s.operationTrigger shouldBe Some("user")
      s.recordCount shouldBe 100
      s.acquiredCount shouldBe 50
      s.sourceCount shouldBe 200
      s.processedValid shouldBe 40
      s.processedInvalid shouldBe 2
      s.acquisitionMethod shouldBe Some("oai-pmh")
    }

    "upsert and retrieve harvest config" in {
      repo.createDataset(DatasetRecord(spec = "harvest-spec", orgId = "org-1", createdAt = now, updatedAt = now))

      val config = HarvestConfigRecord(
        spec = "harvest-spec",
        harvestType = Some("oai-pmh"),
        harvestUrl = Some("http://oai.example.com"),
        harvestDataset = Some("collection-1"),
        harvestPrefix = Some("oai_dc"),
        sourceType = Some("xml"),
        recordRoot = Some("/record"),
        uniqueId = Some("/record/id"),
        recordContainer = Some("/records"),
        continueOnError = true,
        errorThreshold = Some(100),
        idFilterType = Some("regex"),
        idFilterExpression = Some("^[A-Z]+")
      )
      repo.upsertHarvestConfig(config)

      val result = repo.getHarvestConfig("harvest-spec")
      result shouldBe defined
      val c = result.get
      c.harvestType shouldBe Some("oai-pmh")
      c.harvestUrl shouldBe Some("http://oai.example.com")
      c.harvestDataset shouldBe Some("collection-1")
      c.harvestPrefix shouldBe Some("oai_dc")
      c.sourceType shouldBe Some("xml")
      c.recordRoot shouldBe Some("/record")
      c.uniqueId shouldBe Some("/record/id")
      c.recordContainer shouldBe Some("/records")
      c.continueOnError shouldBe true
      c.errorThreshold shouldBe Some(100)
      c.idFilterType shouldBe Some("regex")
      c.idFilterExpression shouldBe Some("^[A-Z]+")
    }

    "upsert and retrieve harvest schedule" in {
      repo.createDataset(DatasetRecord(spec = "sched-spec", orgId = "org-1", createdAt = now, updatedAt = now))

      val schedule = HarvestScheduleRecord(
        spec = "sched-spec",
        delay = Some("6"),
        delayUnit = Some("hours"),
        incremental = true,
        previousTime = Some(now),
        lastFullHarvest = Some(now),
        lastIncrementalHarvest = Some(now)
      )
      repo.upsertHarvestSchedule(schedule)

      val result = repo.getHarvestSchedule("sched-spec")
      result shouldBe defined
      val s = result.get
      s.delay shouldBe Some("6")
      s.delayUnit shouldBe Some("hours")
      s.incremental shouldBe true
      s.previousTime shouldBe defined
      s.lastFullHarvest shouldBe defined
      s.lastIncrementalHarvest shouldBe defined
    }

    "upsert and retrieve mapping config" in {
      repo.createDataset(DatasetRecord(spec = "map-spec", orgId = "org-1", createdAt = now, updatedAt = now))

      val config = MappingConfigRecord(
        spec = "map-spec",
        mapToPrefix = Some("edm"),
        mappingSource = Some("groovy"),
        defaultMappingPrefix = Some("icn"),
        defaultMappingName = Some("default"),
        defaultMappingVersion = Some("1.0"),
        publishOaipmh = false,
        publishIndex = true,
        publishLod = false,
        categoriesInclude = true
      )
      repo.upsertMappingConfig(config)

      val result = repo.getMappingConfig("map-spec")
      result shouldBe defined
      val m = result.get
      m.mapToPrefix shouldBe Some("edm")
      m.mappingSource shouldBe Some("groovy")
      m.defaultMappingPrefix shouldBe Some("icn")
      m.defaultMappingName shouldBe Some("default")
      m.defaultMappingVersion shouldBe Some("1.0")
      m.publishOaipmh shouldBe false
      m.publishIndex shouldBe true
      m.publishLod shouldBe false
      m.categoriesInclude shouldBe true
    }

    "upsert and retrieve indexing" in {
      repo.createDataset(DatasetRecord(spec = "idx-spec", orgId = "org-1", createdAt = now, updatedAt = now))

      val indexing = IndexingRecord(
        spec = "idx-spec",
        recordsIndexed = Some(500),
        recordsExpected = Some(1000),
        orphansDeleted = Some(5),
        errorCount = Some(3),
        lastStatus = Some("completed"),
        lastMessage = Some("All done"),
        lastTimestamp = Some(now),
        lastRevision = Some(42)
      )
      repo.upsertIndexing(indexing)

      val result = repo.getIndexing("idx-spec")
      result shouldBe defined
      val i = result.get
      i.recordsIndexed shouldBe Some(500)
      i.recordsExpected shouldBe Some(1000)
      i.orphansDeleted shouldBe Some(5)
      i.errorCount shouldBe Some(3)
      i.lastStatus shouldBe Some("completed")
      i.lastMessage shouldBe Some("All done")
      i.lastRevision shouldBe Some(42)
    }

    "create and query workflows" in {
      repo.createDataset(DatasetRecord(spec = "wf-spec", orgId = "org-1", createdAt = now, updatedAt = now))

      val wf = WorkflowRecord(
        id = "wf-1",
        spec = "wf-spec",
        trigger = "user",
        status = "running",
        startedAt = now
      )
      repo.createWorkflow(wf)

      val result = repo.getWorkflow("wf-1")
      result shouldBe defined
      result.get.spec shouldBe "wf-spec"
      result.get.trigger shouldBe "user"
      result.get.status shouldBe "running"

      val active = repo.getActiveWorkflows("wf-spec")
      active should have size 1
      active.head.id shouldBe "wf-1"
    }

    "update workflow status" in {
      repo.createDataset(DatasetRecord(spec = "wf-upd", orgId = "org-1", createdAt = now, updatedAt = now))
      repo.createWorkflow(WorkflowRecord(id = "wf-2", spec = "wf-upd", trigger = "auto", startedAt = now))

      val completedAt = now.plusSeconds(60)
      repo.updateWorkflowStatus("wf-2", "completed", errorMessage = None, completedAt = Some(completedAt))

      val result = repo.getWorkflow("wf-2")
      result shouldBe defined
      result.get.status shouldBe "completed"
      result.get.completedAt shouldBe defined
    }

    "get retry workflows" in {
      repo.createDataset(DatasetRecord(spec = "retry-spec", orgId = "org-1", createdAt = now, updatedAt = now))

      val pastTime = now.minusSeconds(300)
      repo.createWorkflow(WorkflowRecord(
        id = "wf-retry-1",
        spec = "retry-spec",
        trigger = "auto",
        status = "retry",
        retryCount = 2,
        nextRetryAt = Some(pastTime),
        startedAt = now
      ))

      // Future retry should not be returned
      repo.createWorkflow(WorkflowRecord(
        id = "wf-retry-future",
        spec = "retry-spec",
        trigger = "auto",
        status = "retry",
        retryCount = 1,
        nextRetryAt = Some(now.plusSeconds(3600)),
        startedAt = now
      ))

      val retries = repo.getRetryWorkflows()
      retries.map(_.id) should contain("wf-retry-1")
      retries.map(_.id) should not contain "wf-retry-future"
    }

    "create and update workflow steps" in {
      repo.createDataset(DatasetRecord(spec = "step-spec", orgId = "org-1", createdAt = now, updatedAt = now))
      repo.createWorkflow(WorkflowRecord(id = "wf-step", spec = "step-spec", trigger = "user", startedAt = now))

      val step = WorkflowStepRecord(
        workflowId = "wf-step",
        stepName = "harvest",
        status = "running",
        startedAt = now
      )
      val stepId = repo.createWorkflowStep(step)
      stepId should be > 0

      val completedAt = now.plusSeconds(30)
      repo.updateWorkflowStep(stepId, "completed", recordsProcessed = 150, completedAt = Some(completedAt))

      // Verify by querying directly
      val conn = dbService.getConnection()
      try {
        val ps = conn.prepareStatement("SELECT status, records_processed FROM workflow_steps WHERE id = ?")
        ps.setInt(1, stepId)
        val rs = ps.executeQuery()
        rs.next() shouldBe true
        rs.getString("status") shouldBe "completed"
        rs.getInt("records_processed") shouldBe 150
        rs.close()
        ps.close()
      } finally {
        conn.close()
      }
    }

    "generate audit trail on state changes" in {
      repo.createDataset(DatasetRecord(spec = "audit-spec", orgId = "org-1", createdAt = now, updatedAt = now))

      // Three state upserts should generate audit rows via trigger
      repo.upsertState(DatasetStateRecord(spec = "audit-spec", state = "CREATED", stateChangedAt = now))
      repo.upsertState(DatasetStateRecord(spec = "audit-spec", state = "HARVESTING", stateChangedAt = now))
      repo.upsertState(DatasetStateRecord(spec = "audit-spec", state = "PROCESSING", stateChangedAt = now))

      val history = repo.getAuditHistory("audit-spec", "dataset_state")
      // INSERT + 2 UPDATEs = 3 audit rows
      history should have size 3
      history.foreach { a =>
        a.tableName shouldBe "dataset_state"
        a.spec shouldBe "audit-spec"
      }
    }
  }
}
