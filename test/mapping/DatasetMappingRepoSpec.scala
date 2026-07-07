package mapping

import java.io.File

import org.apache.commons.io.FileUtils
import org.scalatest.BeforeAndAfterEach
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class DatasetMappingRepoSpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach {

  private var datasetDir: File = _
  private var repo: DatasetMappingRepo = _

  override def beforeEach(): Unit = {
    datasetDir = java.nio.file.Files.createTempDirectory("narthex-mapping-repo-test-").toFile
    repo = new DatasetMappingRepo(datasetDir)
  }

  override def afterEach(): Unit = {
    if (datasetDir != null) FileUtils.deleteQuietly(datasetDir)
  }

  "DatasetMappingRepo" should "hold mappings for exactly one prefix — a save for a new prefix archives the old one" in {
    repo.saveFromSipUpload("<mapping>crmter content</mapping>", "crmter")
    repo.getInfo.map(_.prefix) shouldBe Some("crmter")
    repo.getXml("current").isDefined shouldBe true

    // The dataset switches rec-def: saving an ace mapping must not mix with
    // (or silently relabel) the crmter versions — the flaky-behaviour bug.
    repo.saveFromSipUpload("<mapping>ace content</mapping>", "ace")

    val info = repo.getInfo.get
    info.prefix shouldBe "ace"
    info.versions.size shouldBe 1
    repo.getXml("current") shouldBe Some("<mapping>ace content</mapping>")

    // Old prefix archived, not deleted
    val mappingsDir = new File(datasetDir, "mappings")
    val archives = mappingsDir.listFiles().filter(f => f.isDirectory && f.getName.startsWith("archive-crmter-"))
    archives.length shouldBe 1
    val archived = archives.head.listFiles().map(_.getName).toSet
    archived should contain("metadata.json")
    archived.count(_.endsWith(".xml")) shouldBe 1
  }

  it should "record default-mapping provenance via saveFromDefault" in {
    repo.saveFromDefault("<mapping>default edm</mapping>", "edm", "abc12345", Some("Materialized default edm/base@abc12345"))
    val info = repo.getInfo.get
    info.prefix shouldBe "edm"
    info.versions.head.source shouldBe "default_copy"
    info.versions.head.sourceDefault shouldBe Some("edm:abc12345")
    repo.getXml("current") shouldBe Some("<mapping>default edm</mapping>")
  }

  it should "not archive when saving another version of the same prefix" in {
    repo.saveFromSipUpload("<mapping>v1</mapping>", "edm")
    repo.saveFromSipUpload("<mapping>v2</mapping>", "edm")
    val info = repo.getInfo.get
    info.prefix shouldBe "edm"
    info.versions.size shouldBe 2
    new File(datasetDir, "mappings").listFiles().count(_.getName.startsWith("archive-")) shouldBe 0
  }
}
