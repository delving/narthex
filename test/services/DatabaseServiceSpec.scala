package services

import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterAll
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres

class DatabaseServiceSpec extends AnyWordSpec with Matchers with BeforeAndAfterAll {

  var pg: EmbeddedPostgres = _
  var dbService: DatabaseService = _

  override def beforeAll(): Unit = {
    pg = EmbeddedPostgres.builder().setPort(15432).start()
  }

  override def afterAll(): Unit = {
    if (dbService != null) dbService.close()
    if (pg != null) pg.close()
  }

  "DatabaseService" should {

    "connect to PostgreSQL and run Flyway migrations" in {
      val config = PostgresConfig(
        url = "jdbc:postgresql://localhost:15432/postgres",
        user = "postgres",
        password = "postgres"
      )
      dbService = new DatabaseService(config)
      dbService.initialize()

      // Verify that Flyway ran migrations by checking that core tables exist
      val conn = dbService.getConnection()
      try {
        val meta = conn.getMetaData
        val tables = meta.getTables(null, "public", "datasets", null)
        tables.next() shouldBe true

        // Also check a table from a later migration
        val stateTables = meta.getTables(null, "public", "dataset_state", null)
        stateTables.next() shouldBe true
      } finally {
        conn.close()
      }
    }

    "provide working connections from pool" in {
      val conn = dbService.getConnection()
      try {
        val stmt = conn.createStatement()
        val rs = stmt.executeQuery("SELECT 1 AS result")
        rs.next() shouldBe true
        rs.getInt("result") shouldBe 1
        rs.close()
        stmt.close()
      } finally {
        conn.close()
      }
    }

    "report health status" in {
      dbService.isHealthy shouldBe true
    }

    "provide a DataSource" in {
      val ds = dbService.getDataSource
      ds should not be null
      val conn = ds.getConnection
      try {
        conn.isValid(5) shouldBe true
      } finally {
        conn.close()
      }
    }

    "fail gracefully when not initialized" in {
      val config = PostgresConfig(
        url = "jdbc:postgresql://localhost:15432/postgres",
        user = "postgres",
        password = "postgres"
      )
      val uninitializedService = new DatabaseService(config)
      try {
        an[IllegalStateException] should be thrownBy {
          uninitializedService.getConnection()
        }
        an[IllegalStateException] should be thrownBy {
          uninitializedService.getDataSource
        }
        uninitializedService.isHealthy shouldBe false
      } finally {
        // Don't close — it was never initialized
      }
    }
  }

  "GlobalDatabaseService" should {

    "store and retrieve a DatabaseService instance" in {
      GlobalDatabaseService.set(dbService)
      GlobalDatabaseService.get() shouldBe Some(dbService)
      GlobalDatabaseService.getOrThrow() shouldBe dbService
    }

    "throw when getting unset instance" in {
      GlobalDatabaseService.close()
      an[IllegalStateException] should be thrownBy {
        GlobalDatabaseService.getOrThrow()
      }
      // Restore for other tests
      GlobalDatabaseService.set(dbService)
    }
  }
}
