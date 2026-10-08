package dataset

import java.io.File

import org.apache.commons.io.FileUtils
import org.scalatest.BeforeAndAfterEach
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class UploadArchiveSpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach {

  private var tempDir: File = _
  private var rootDir: File = _
  private var orgSipsDir: File = _

  override def beforeEach(): Unit = {
    tempDir = java.nio.file.Files.createTempDirectory("narthex-upload-archive-test-").toFile
    rootDir = new File(tempDir, "datasets/ds")
    orgSipsDir = new File(tempDir, "sips")
    orgSipsDir.mkdirs()
  }

  override def afterEach(): Unit = {
    if (tempDir != null) {
      FileUtils.deleteQuietly(tempDir)
      ()
    }
  }

  private def write(dir: File, name: String, content: String): File = {
    val file = new File(dir, name)
    FileUtils.writeStringToFile(file, content, "UTF-8")
    file
  }

  private def read(path: String): String =
    FileUtils.readFileToString(new File(rootDir, path), "UTF-8")

  private def sipFiles: Seq[File] = orgSipsDir.listFiles.toSeq

  private def writeGeneration(tag: String): Unit = {
    write(new File(rootDir, "raw"), s"$tag.xml", tag)
    write(new File(rootDir, "source"), "00000.zip", tag)
    write(new File(rootDir, "sourceTree"), "index.json", tag)
    write(new File(rootDir, "processed"), "00000.xml.zst", tag)
    write(orgSipsDir, s"ds__$tag.sip.zip", tag)
    ()
  }

  "archive" should "move the raw file and everything derived from it out of the way" in {
    writeGeneration("old")

    UploadArchive.archive(rootDir, sipFiles)

    Seq("raw", "source", "sourceTree", "processed").foreach { name =>
      new File(rootDir, name).exists() shouldBe false
    }
    sipFiles shouldBe empty
    read(s"${UploadArchive.DIR}/source/00000.zip") shouldBe "old"
  }

  it should "keep only one previous version" in {
    writeGeneration("first")
    UploadArchive.archive(rootDir, sipFiles)
    writeGeneration("second")
    UploadArchive.archive(rootDir, sipFiles)

    read(s"${UploadArchive.DIR}/source/00000.zip") shouldBe "second"
    new File(rootDir, s"${UploadArchive.DIR}/raw/first.xml").exists() shouldBe false
  }

  "restore" should "put the previous version back and discard the current one" in {
    writeGeneration("old")
    UploadArchive.archive(rootDir, sipFiles)
    writeGeneration("new")
    write(new File(rootDir, "tree"), "index.json", "new")

    UploadArchive.restore(rootDir, sipFiles, orgSipsDir) shouldBe true

    read("raw/old.xml") shouldBe "old"
    new File(rootDir, "raw/new.xml").exists() shouldBe false
    read("source/00000.zip") shouldBe "old"
    read("sourceTree/index.json") shouldBe "old"
    read("processed/00000.xml.zst") shouldBe "old"
    new File(rootDir, "tree").exists() shouldBe false
    sipFiles.map(_.getName) shouldBe Seq("ds__old.sip.zip")
    new File(rootDir, UploadArchive.DIR).exists() shouldBe false
  }

  it should "leave the dataset alone when there is nothing to restore" in {
    writeGeneration("only")

    UploadArchive.restore(rootDir, sipFiles, orgSipsDir) shouldBe false

    read("source/00000.zip") shouldBe "only"
  }
}
