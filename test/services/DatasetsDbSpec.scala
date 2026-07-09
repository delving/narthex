package services

import java.io.File

import org.apache.commons.io.FileUtils
import org.scalatest.BeforeAndAfterEach
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class DatasetsDbSpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach {

  private var dir: File = _
  private var db: DatasetsDb = _

  override def beforeEach(): Unit = {
    dir = java.nio.file.Files.createTempDirectory("narthex-datasets-db-").toFile
    db = new DatasetsDb(dir)
  }
  override def afterEach(): Unit = {
    if (db != null) db.close()
    FileUtils.deleteQuietly(dir)
  }

  "DatasetsDb" should "store and update singular props per dataset" in {
    db.isEmpty shouldBe true
    db.setProps("ds1", "datasetName" -> "One", "harvestType" -> "pmh")
    db.setProps("ds1", "datasetName" -> "One Renamed")
    db.getProp("ds1", "datasetName") shouldBe Some("One Renamed")
    db.getProp("ds1", "harvestType") shouldBe Some("pmh")
    db.getProp("ds1", "nope") shouldBe None
    db.exists("ds1") shouldBe true
    db.exists("ds2") shouldBe false
    db.isEmpty shouldBe false
  }

  it should "pivot all props for the list read" in {
    db.setProps("a", "datasetName" -> "A")
    db.setProps("b", "datasetName" -> "B", "datasetErrorMessage" -> "boom")
    db.createDataset("empty-one")
    val all = db.allProps()
    all.keySet shouldBe Set("a", "b", "empty-one")
    all("b")("datasetErrorMessage") shouldBe "boom"
    all("empty-one") shouldBe Map.empty
  }

  it should "handle list-valued props and full deletion" in {
    db.addListValue("ds1", "skosField", "http://x/1")
    db.addListValue("ds1", "skosField", "http://x/2")
    db.addListValue("ds1", "skosField", "http://x/1") // dedup
    db.listValues("ds1", "skosField") shouldBe List("http://x/1", "http://x/2")
    db.removeListValue("ds1", "skosField", "http://x/1")
    db.listValues("ds1", "skosField") shouldBe List("http://x/2")

    db.setProps("ds1", "datasetName" -> "One")
    db.deleteDataset("ds1")
    db.exists("ds1") shouldBe false
    db.getProp("ds1", "datasetName") shouldBe None
    db.listValues("ds1", "skosField") shouldBe Nil
  }

  it should "remove a singular prop" in {
    db.setProps("ds1", "datasetErrorMessage" -> "bad")
    db.removeProp("ds1", "datasetErrorMessage")
    db.getProp("ds1", "datasetErrorMessage") shouldBe None
  }
}
