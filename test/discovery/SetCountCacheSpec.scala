package discovery

import java.io.File
import org.apache.commons.io.FileUtils
import org.joda.time.DateTime
import org.scalatest.flatspec._
import org.scalatest.matchers._
import org.scalatest.BeforeAndAfterEach
import discovery.OaiSourceConfig._

class SetCountCacheSpec extends AnyFlatSpec with should.Matchers with BeforeAndAfterEach {

  private val testDir = new File(System.getProperty("java.io.tmpdir"), "narthex-test-" + System.currentTimeMillis())

  override def beforeEach(): Unit = {
    testDir.mkdirs()
  }

  override def afterEach(): Unit = {
    FileUtils.deleteDirectory(testDir)
  }

  "OaiSourceRepo" should "save and load counts cache" in {
    val repo = new OaiSourceRepo(testDir)
    val cache = SetCountCache(
      sourceId = "test-source",
      lastVerified = DateTime.now(),
      counts = Map("set-a" -> 100, "set-b" -> 0, "set-c" -> 42),
      errors = Map("set-d" -> "HTTP 500"),
      summary = CountSummary(totalSets = 4, newWithRecords = 2, empty = 1)
    )

    repo.saveCountsCache(cache)
    val loaded = repo.loadCountsCache("test-source")

    loaded shouldBe defined
    loaded.get.sourceId shouldBe "test-source"
    loaded.get.counts("set-a") shouldBe 100
    loaded.get.counts("set-b") shouldBe 0
    loaded.get.summary.newWithRecords shouldBe 2
    loaded.get.errors("set-d") shouldBe "HTTP 500"
  }

  it should "return None for missing cache" in {
    val repo = new OaiSourceRepo(testDir)
    repo.loadCountsCache("nonexistent") shouldBe None
  }
}
