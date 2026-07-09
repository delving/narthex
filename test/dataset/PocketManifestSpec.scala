package dataset

import java.io.File

import org.apache.commons.io.FileUtils
import org.scalatest.BeforeAndAfterEach
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import dataset.SourceRepo.IdFilter
import dataset.pipeline.PocketManifest

class PocketManifestSpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach {

  private var dir: File = _
  private val idFilter = IdFilter("verbatim", None)

  override def beforeEach(): Unit = {
    dir = java.nio.file.Files.createTempDirectory("narthex-pocket-manifest-").toFile
  }
  override def afterEach(): Unit = FileUtils.deleteQuietly(dir)

  private def write(name: String, content: String): File = {
    val f = new File(dir, name)
    FileUtils.writeStringToFile(f, content, "UTF-8")
    f
  }

  "PocketManifest" should "reuse pockets only while inputs and output are untouched" in {
    write("00001.zip", "source data")
    val pockets = write("pockets.xml", "<pockets/>")
    val manifestFile = PocketManifest.manifestFileFor(pockets)

    val inputs = PocketManifest.inputs(dir, idFilter)
    PocketManifest.cachedCount(manifestFile, inputs, pockets) shouldBe None  // no manifest yet

    PocketManifest.write(manifestFile, inputs, pocketCount = 42, pockets)
    PocketManifest.cachedCount(manifestFile, inputs, pockets) shouldBe Some(42)

    // A new source zip invalidates
    write("00002.zip", "more data")
    val newInputs = PocketManifest.inputs(dir, idFilter)
    PocketManifest.cachedCount(manifestFile, newInputs, pockets) shouldBe None

    // A different id filter invalidates
    val filtered = PocketManifest.inputs(dir, IdFilter("sha256-hash", None))
    PocketManifest.cachedCount(manifestFile, filtered, pockets) shouldBe None
  }

  it should "ignore the cache when the pocket file itself changed" in {
    write("00001.zip", "source data")
    val pockets = write("pockets.xml", "<pockets/>")
    val manifestFile = PocketManifest.manifestFileFor(pockets)
    val inputs = PocketManifest.inputs(dir, idFilter)
    PocketManifest.write(manifestFile, inputs, 7, pockets)

    FileUtils.writeStringToFile(pockets, "<pockets>tampered</pockets>", "UTF-8")
    PocketManifest.cachedCount(manifestFile, inputs, pockets) shouldBe None
  }
}
