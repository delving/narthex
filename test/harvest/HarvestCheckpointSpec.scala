package harvest

import java.io.{File, FileOutputStream}
import java.util.zip.{ZipEntry, ZipOutputStream}

import org.apache.commons.io.FileUtils
import org.scalatest.BeforeAndAfterEach
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class HarvestCheckpointSpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach {

  private var tempDir: File = _
  private def rootDir = new File(tempDir, "ds")

  override def beforeEach(): Unit = {
    tempDir = java.nio.file.Files.createTempDirectory("narthex-checkpoint-test-").toFile
    rootDir.mkdirs()
    ()
  }

  override def afterEach(): Unit = { FileUtils.deleteQuietly(tempDir); () }

  // same layout as Harvester.addPage writes
  private def harvestZip(pages: Seq[String]): File = {
    val file = new File(tempDir, "harvest.zip")
    val zip = new ZipOutputStream(new FileOutputStream(file))
    pages.zipWithIndex.foreach { case (page, i) =>
      zip.putNextEntry(new ZipEntry(s"harvest_$i.xml"))
      zip.write(page.getBytes("UTF-8"))
      zip.closeEntry()
    }
    zip.close()
    file
  }

  private def state(savedAt: Long = System.currentTimeMillis()) =
    HarvestCheckpoint.State("http://oai/?", "", "oai_dc", "tok/3", 300, 1000, List("gone"), savedAt)

  "HarvestCheckpoint" should "replay the saved pages in order and keep the token" in {
    val pages = Seq("<p>1</p>", "<p>2 é</p>", "<p>3</p>")
    HarvestCheckpoint.save(rootDir, harvestZip(pages), state())

    val Some((loaded, zip)) = HarvestCheckpoint.load(rootDir, "http://oai/?", "", "oai_dc")
    loaded.token shouldBe "tok/3"
    loaded.cursor shouldBe 300
    loaded.deletedIds shouldBe List("gone")

    var replayed = Vector.empty[String]
    HarvestCheckpoint.replay(zip)(p => replayed :+= p) shouldBe 3
    replayed shouldBe pages
  }

  it should "ignore and remove a checkpoint for another endpoint" in {
    HarvestCheckpoint.save(rootDir, harvestZip(Seq("<p/>")), state())
    HarvestCheckpoint.load(rootDir, "http://other/?", "", "oai_dc") shouldBe None
    new File(rootDir, HarvestCheckpoint.DIR).exists() shouldBe false
  }

  it should "ignore a checkpoint whose token has likely expired" in {
    val old = System.currentTimeMillis() - HarvestCheckpoint.MaxAgeMillis - 1
    HarvestCheckpoint.save(rootDir, harvestZip(Seq("<p/>")), state(savedAt = old))
    HarvestCheckpoint.load(rootDir, "http://oai/?", "", "oai_dc") shouldBe None
  }

  it should "find nothing when no checkpoint exists" in {
    HarvestCheckpoint.load(rootDir, "http://oai/?", "", "oai_dc") shouldBe None
  }
}
