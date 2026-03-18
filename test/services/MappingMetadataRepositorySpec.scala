package services

import java.time.Instant
import java.time.temporal.ChronoUnit

import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres

class MappingMetadataRepositorySpec
    extends AnyWordSpec
    with Matchers
    with BeforeAndAfterAll
    with BeforeAndAfterEach {

  var pg: EmbeddedPostgres = _
  var dbService: DatabaseService = _
  var repo: MappingMetadataRepository = _
  // Need a dataset repo to create prerequisite dataset rows for FK constraints
  var datasetRepo: PostgresDatasetRepository = _

  override def beforeAll(): Unit = {
    pg = EmbeddedPostgres.builder().setPort(15435).start()
    val config = PostgresConfig(
      url = "jdbc:postgresql://localhost:15435/postgres",
      user = "postgres",
      password = "postgres"
    )
    dbService = new DatabaseService(config)
    dbService.initialize()
    repo = new MappingMetadataRepository(dbService)
    datasetRepo = new PostgresDatasetRepository(dbService)
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
      stmt.execute("DELETE FROM dataset_mapping_versions")
      stmt.execute("DELETE FROM dataset_mappings")
      stmt.execute("DELETE FROM default_mapping_versions")
      stmt.execute("DELETE FROM default_mappings")
      // Also clear tables that reference datasets so we can safely clean up
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

  private def createTestDataset(spec: String): Unit = {
    datasetRepo.createDataset(
      DatasetRecord(spec = spec, orgId = "org-1", createdAt = now, updatedAt = now)
    )
  }

  "MappingMetadataRepository" should {

    // =========================================================================
    // Default Mappings
    // =========================================================================

    "create and list default mappings" in {
      repo.upsertDefaultMapping(
        DefaultMappingRecord(
          prefix = "icn",
          name = "default",
          orgId = "org-1",
          displayName = Some("ICN Default"),
          createdAt = now,
          updatedAt = now
        )
      )
      repo.upsertDefaultMapping(
        DefaultMappingRecord(
          prefix = "edm",
          name = "standard",
          orgId = "org-1",
          displayName = Some("EDM Standard"),
          createdAt = now,
          updatedAt = now
        )
      )
      // Different org, should not appear
      repo.upsertDefaultMapping(
        DefaultMappingRecord(
          prefix = "dc",
          name = "basic",
          orgId = "org-2",
          createdAt = now,
          updatedAt = now
        )
      )

      val mappings = repo.listDefaultMappings("org-1")
      mappings should have size 2
      mappings.map(m => (m.prefix, m.name)).toSet shouldBe Set(
        ("edm", "standard"),
        ("icn", "default")
      )
      mappings.find(_.prefix == "icn").get.displayName shouldBe Some("ICN Default")
    }

    "get a specific default mapping" in {
      repo.upsertDefaultMapping(
        DefaultMappingRecord(
          prefix = "icn",
          name = "default",
          orgId = "org-1",
          displayName = Some("ICN Default"),
          createdAt = now,
          updatedAt = now
        )
      )

      val result = repo.getDefaultMapping("icn", "default")
      result shouldBe defined
      result.get.orgId shouldBe "org-1"
      result.get.displayName shouldBe Some("ICN Default")
      result.get.currentVersion shouldBe None

      repo.getDefaultMapping("nonexistent", "nope") shouldBe None
    }

    "update an existing default mapping via upsert" in {
      repo.upsertDefaultMapping(
        DefaultMappingRecord(
          prefix = "icn",
          name = "default",
          orgId = "org-1",
          displayName = Some("Original"),
          createdAt = now,
          updatedAt = now
        )
      )

      repo.upsertDefaultMapping(
        DefaultMappingRecord(
          prefix = "icn",
          name = "default",
          orgId = "org-1",
          displayName = Some("Updated"),
          createdAt = now,
          updatedAt = now.plusSeconds(60)
        )
      )

      val result = repo.getDefaultMapping("icn", "default")
      result shouldBe defined
      result.get.displayName shouldBe Some("Updated")
    }

    "add versions to a default mapping" in {
      repo.upsertDefaultMapping(
        DefaultMappingRecord(
          prefix = "icn",
          name = "default",
          orgId = "org-1",
          createdAt = now,
          updatedAt = now
        )
      )

      val v1Id = repo.addDefaultMappingVersion(
        DefaultMappingVersionRecord(
          prefix = "icn",
          name = "default",
          hash = "abc123",
          filename = Some("mapping-v1.xml"),
          source = Some("upload"),
          notes = Some("Initial version"),
          createdAt = now
        )
      )
      v1Id should be > 0

      val v2Id = repo.addDefaultMappingVersion(
        DefaultMappingVersionRecord(
          prefix = "icn",
          name = "default",
          hash = "def456",
          filename = Some("mapping-v2.xml"),
          source = Some("copy_from_dataset"),
          sourceDataset = Some("test-ds"),
          notes = Some("Copied from dataset"),
          createdAt = now.plusSeconds(10)
        )
      )
      v2Id should be > v1Id

      val versions = repo.listDefaultMappingVersions("icn", "default")
      versions should have size 2
      versions.head.hash shouldBe "abc123"
      versions.head.source shouldBe Some("upload")
      versions(1).hash shouldBe "def456"
      versions(1).sourceDataset shouldBe Some("test-ds")
    }

    "set current version of a default mapping" in {
      repo.upsertDefaultMapping(
        DefaultMappingRecord(
          prefix = "icn",
          name = "default",
          orgId = "org-1",
          createdAt = now,
          updatedAt = now
        )
      )

      val v1Id = repo.addDefaultMappingVersion(
        DefaultMappingVersionRecord(
          prefix = "icn",
          name = "default",
          hash = "abc123",
          filename = Some("v1.xml"),
          createdAt = now
        )
      )

      val v2Id = repo.addDefaultMappingVersion(
        DefaultMappingVersionRecord(
          prefix = "icn",
          name = "default",
          hash = "def456",
          filename = Some("v2.xml"),
          createdAt = now.plusSeconds(10)
        )
      )

      // Set current version to v1 by hash
      repo.setDefaultMappingCurrentVersion("icn", "default", "abc123")
      val afterV1 = repo.getDefaultMapping("icn", "default")
      afterV1.get.currentVersion shouldBe Some(v1Id)

      // Set current version to v2 by hash
      repo.setDefaultMappingCurrentVersion("icn", "default", "def456")
      val afterV2 = repo.getDefaultMapping("icn", "default")
      afterV2.get.currentVersion shouldBe Some(v2Id)
    }

    // =========================================================================
    // Dataset Mappings
    // =========================================================================

    "create and get dataset mapping" in {
      createTestDataset("ds-map-1")

      repo.upsertDatasetMapping(
        DatasetMappingRecord(
          spec = "ds-map-1",
          prefix = Some("icn"),
          updatedAt = now
        )
      )

      val result = repo.getDatasetMapping("ds-map-1")
      result shouldBe defined
      result.get.spec shouldBe "ds-map-1"
      result.get.prefix shouldBe Some("icn")
      result.get.currentVersion shouldBe None

      repo.getDatasetMapping("nonexistent") shouldBe None
    }

    "update existing dataset mapping via upsert" in {
      createTestDataset("ds-map-upd")

      repo.upsertDatasetMapping(
        DatasetMappingRecord(spec = "ds-map-upd", prefix = Some("icn"), updatedAt = now)
      )

      repo.upsertDatasetMapping(
        DatasetMappingRecord(
          spec = "ds-map-upd",
          prefix = Some("edm"),
          updatedAt = now.plusSeconds(60)
        )
      )

      val result = repo.getDatasetMapping("ds-map-upd")
      result shouldBe defined
      result.get.prefix shouldBe Some("edm")
    }

    "add dataset mapping versions" in {
      createTestDataset("ds-ver-1")

      repo.upsertDatasetMapping(
        DatasetMappingRecord(spec = "ds-ver-1", prefix = Some("icn"), updatedAt = now)
      )

      val v1Id = repo.addDatasetMappingVersion(
        DatasetMappingVersionRecord(
          spec = "ds-ver-1",
          hash = "hash-a",
          filename = Some("mapping.xml"),
          source = Some("sip_upload"),
          description = Some("First upload"),
          createdAt = now
        )
      )
      v1Id should be > 0

      val v2Id = repo.addDatasetMappingVersion(
        DatasetMappingVersionRecord(
          spec = "ds-ver-1",
          hash = "hash-b",
          filename = Some("mapping-v2.xml"),
          source = Some("editor"),
          description = Some("Edited in browser"),
          createdAt = now.plusSeconds(10)
        )
      )
      v2Id should be > v1Id

      val v3Id = repo.addDatasetMappingVersion(
        DatasetMappingVersionRecord(
          spec = "ds-ver-1",
          hash = "hash-c",
          filename = Some("mapping-v3.xml"),
          source = Some("default_copy"),
          sourceDefault = Some("icn/default"),
          description = Some("Copied from default"),
          createdAt = now.plusSeconds(20)
        )
      )
      v3Id should be > v2Id

      val versions = repo.listDatasetMappingVersions("ds-ver-1")
      versions should have size 3
      versions.head.hash shouldBe "hash-a"
      versions.head.source shouldBe Some("sip_upload")
      versions(1).hash shouldBe "hash-b"
      versions(1).source shouldBe Some("editor")
      versions(2).hash shouldBe "hash-c"
      versions(2).sourceDefault shouldBe Some("icn/default")
    }

    "set dataset mapping current version" in {
      createTestDataset("ds-curver")

      repo.upsertDatasetMapping(
        DatasetMappingRecord(spec = "ds-curver", prefix = Some("icn"), updatedAt = now)
      )

      val v1Id = repo.addDatasetMappingVersion(
        DatasetMappingVersionRecord(
          spec = "ds-curver",
          hash = "hash-x",
          filename = Some("v1.xml"),
          createdAt = now
        )
      )

      val v2Id = repo.addDatasetMappingVersion(
        DatasetMappingVersionRecord(
          spec = "ds-curver",
          hash = "hash-y",
          filename = Some("v2.xml"),
          createdAt = now.plusSeconds(10)
        )
      )

      repo.setDatasetMappingCurrentVersion("ds-curver", "hash-x")
      val afterV1 = repo.getDatasetMapping("ds-curver")
      afterV1.get.currentVersion shouldBe Some(v1Id)

      repo.setDatasetMappingCurrentVersion("ds-curver", "hash-y")
      val afterV2 = repo.getDatasetMapping("ds-curver")
      afterV2.get.currentVersion shouldBe Some(v2Id)
    }

    "list versions in chronological order" in {
      repo.upsertDefaultMapping(
        DefaultMappingRecord(
          prefix = "edm",
          name = "chrono",
          orgId = "org-1",
          createdAt = now,
          updatedAt = now
        )
      )

      // Insert in non-chronological order but with different created_at
      repo.addDefaultMappingVersion(
        DefaultMappingVersionRecord(
          prefix = "edm",
          name = "chrono",
          hash = "third",
          createdAt = now.plusSeconds(20)
        )
      )
      repo.addDefaultMappingVersion(
        DefaultMappingVersionRecord(
          prefix = "edm",
          name = "chrono",
          hash = "first",
          createdAt = now
        )
      )
      repo.addDefaultMappingVersion(
        DefaultMappingVersionRecord(
          prefix = "edm",
          name = "chrono",
          hash = "second",
          createdAt = now.plusSeconds(10)
        )
      )

      val versions = repo.listDefaultMappingVersions("edm", "chrono")
      versions should have size 3
      versions.map(_.hash) shouldBe List("first", "second", "third")

      // Also verify dataset versions are chronological
      createTestDataset("ds-chrono")
      repo.upsertDatasetMapping(
        DatasetMappingRecord(spec = "ds-chrono", updatedAt = now)
      )

      repo.addDatasetMappingVersion(
        DatasetMappingVersionRecord(spec = "ds-chrono", hash = "z-last", createdAt = now.plusSeconds(30))
      )
      repo.addDatasetMappingVersion(
        DatasetMappingVersionRecord(spec = "ds-chrono", hash = "a-first", createdAt = now)
      )
      repo.addDatasetMappingVersion(
        DatasetMappingVersionRecord(spec = "ds-chrono", hash = "m-middle", createdAt = now.plusSeconds(15))
      )

      val dsVersions = repo.listDatasetMappingVersions("ds-chrono")
      dsVersions should have size 3
      dsVersions.map(_.hash) shouldBe List("a-first", "m-middle", "z-last")
    }
  }
}
