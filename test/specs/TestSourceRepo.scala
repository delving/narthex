package specs

import java.io.{File, FileOutputStream}
import org.scalatest.flatspec._
import org.scalatest.matchers._

import dataset.SourceRepo
import dataset.SourceRepo._
import harvest.Harvesting._
import org.apache.commons.io.FileUtils
import record.PocketParser._
import services.{FileHandling, ProgressReporter}

import scala.collection.mutable
import scala.xml.XML
import organization.OrgContext
import org.scalatestplus.mockito.MockitoSugar.mock


class TestSourceRepo extends AnyFlatSpec with should.Matchers {

  def fresh(dir: String): File = {
    val file = new File(dir)
    FileHandling.clearDir(file)
    file
  }

  def resourceFile(which: String): File = {
    val name = s"source-$which.zip"
    val url = getClass.getResource(s"/source_repo/$name")
    new File(url.getFile)
  }

  def sendProgress(percent: Int): Boolean = true

  "id filters" should "work well for urls" in {

    val hashed = List(
      "one",
      "two",
      "3.1415926535etc",
      "and what about a very long value where nobody really knows when it will end, but we can just guess"
    ).map(SHA256_FILTER.filter)

    val exprectedHashed = List(
      "O2JMHLJVIC5YAPACBM5O4ZWNRCDREMRU5IGG44KDYCW5OP7UGHWQ",
      "H7CMZ7TULBYOFQGZT5Y7GD7QMVWI33OUDTA5PU6TO2YNXZUF4LZQ",
      "JVOZCQPQVUMV6PXC7Q7GM5G4ELITHN7J5JYWGBMTG6SCIMAPMD4Q"
    )

    hashed.zip(exprectedHashed).map { pair =>
      pair._1 should be(pair._2)
    }

    val replaceFilter = IdFilter("replacement", Some("[.].*:::. No more."))

    val replaced = List(
      "Nothing replaced",
      "One sentence. Woops, here is another!"
    ).map(replaceFilter.filter)

    val exprectedReplaced = List(
      "Nothing replaced",
      "One sentence. No more."
    )

    replaced.zip(exprectedReplaced).map { pair =>
      pair._1 should be(pair._2)
    }

  }



  private val thingFacts = SourceFacts("test", "/envelope/list/thing", "/envelope/list/thing/@which", None)

  private def harvested(dir: File, n: Int): File = {
    val copy = new File(dir.getParentFile, s"harvest-$n.zip")
    FileUtils.copyFile(resourceFile("a"), copy)
    copy
  }

  "a source repo" should "drop files that a later file fully replaces" in {
    val home = fresh("/tmp/narthex-source-repo-gc/source")
    val repo = SourceRepo.createClean(home, thingFacts, mock[OrgContext])
    repo.acceptHarvestedFile(harvested(home, 0), Set("1", "2", "3"))
    repo.acceptHarvestedFile(harvested(home, 1), Set("1", "2"))
    repo.acceptHarvestedFile(harvested(home, 2), Set("3"))

    // 00000 is now fully replaced, 00001 and 00002 still carry active ids
    new File(home, "00000.zip").exists() shouldBe false
    new File(home, "00000.ids").exists() shouldBe false
    new File(home, "00000.ids_00001.ids").exists() shouldBe false
    new File(home, "00001.zip").exists() shouldBe true
    new File(home, "00002.zip").exists() shouldBe true
  }

  it should "let the 100th file supersede the files moved into a subdirectory" in {
    val home = fresh("/tmp/narthex-source-repo-boundary/source")
    val repo = SourceRepo.createClean(home, thingFacts, mock[OrgContext])
    FileUtils.writeStringToFile(new File(home, "deleted.ids"), "gone", "UTF-8")
    (0 until 100).foreach(n => repo.acceptHarvestedFile(harvested(home, n), Set(s"id$n")))
    repo.acceptHarvestedFile(harvested(home, 100), Set("id0", "id100"))

    new File(home, "00000").listFiles().map(_.getName) should not contain "00000.zip" // replaced and dropped
    new File(home, "00100.zip").exists() shouldBe true
    new File(home, "deleted.ids").exists() shouldBe true // stays where it is read
  }
}
