package specs

import java.io.{File, FileOutputStream}

import dataset.SourceRepo
import dataset.SourceRepo._
import harvest.Harvesting._
import org.apache.commons.io.FileUtils
import org.scalatest.{FlatSpec, Matchers}
import record.PocketParser._
import services.{FileHandling, ProgressReporter}

import scala.collection.mutable
import scala.xml.XML


class TestSourceRepo extends FlatSpec with Matchers {

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

    val hashFilter = IdFilter("sha256-hash", None)

    val hashed = List(
      "one",
      "two",
      "3.1415926535etc",
      "and what about a very long value where nobody really knows when it will end, but we can just guess"
    ).map(hashFilter.filter)

//    hashed.map(h => println(s""" "$h", """))

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

  "A Source Repository" should "accept regular record harvest files" in {
    val incoming = fresh("/tmp/source-repo-incoming")
    def incomingZip(which: String): File = new File(incoming, s"source-$which.zip")

    List("a", "b", "c", "d").map(resourceFile).foreach(f => FileUtils.copyFile(f, new File(incoming, f.getName)))

    val recordRoot = "/envelope/list/thing"
    val uniqueId = s"$recordRoot/@which"
    val sourceDir = fresh("/tmp/test-source-repo-regular")

    val sourceRepo = SourceRepo.createClean(sourceDir, SourceFacts("gumby", recordRoot, uniqueId, None))


    sourceRepo.countFiles should be(0)
    sourceRepo.acceptFile(incomingZip("a"), ProgressReporter())
    sourceRepo.countFiles should be(3)
    sourceRepo.acceptFile(incomingZip("b"), ProgressReporter())
    sourceRepo.countFiles should be(7)
    sourceRepo.acceptFile(incomingZip("c"), ProgressReporter())
    sourceRepo.countFiles should be(11)
    sourceRepo.acceptFile(incomingZip("d"), ProgressReporter())
    sourceRepo.countFiles should be(16)

    val seenIds = mutable.HashSet[String]()
    var recordCount = 0

    def receiveRecord(record: Pocket): Unit = {
      //      println(s"${record.id}: ${record.text}")
      if (seenIds.contains(record.id)) fail(s"seen id ${record.id}")
      recordCount += 1
      seenIds.add(record.id)
      val narthex = XML.loadString(record.text)
      val content = (narthex \ "thing" \ "box").text
      content should be("final")
    }

    sourceRepo.parsePockets(receiveRecord, VERBATIM_FILTER, ProgressReporter())

    recordCount should be(4)

    val gitDir = fresh("/tmp/source-repo-git")
    val pocketFile = new File(gitDir, "test-source-repo.xml")
    val pocketOut = new FileOutputStream(pocketFile)

    FileHandling.ensureGitRepo(gitDir) should be(true)

    sourceRepo.generatePockets(pocketOut, VERBATIM_FILTER, ProgressReporter())

    FileHandling.gitCommit(pocketFile, "Several words of message") should be(true)
  }

  "A Source Repository" should "accept pmh record harvest files" in {
    val incoming = fresh("/tmp/source-repo-incoming")
    def incomingZip(which: String): File = new File(incoming, s"source-$which.zip")

    List("aa", "bb", "cc", "dd").map(resourceFile).foreach(f => FileUtils.copyFile(f, new File(incoming, f.getName)))

    val sourceDir = fresh("/tmp/test-source-repo-pmh")
    val sourceRepo = SourceRepo.createClean(sourceDir, SourceFacts(HarvestType.PMH))

    sourceRepo.countFiles should be(0)
    sourceRepo.acceptFile(incomingZip("aa"), ProgressReporter())
    sourceRepo.countFiles should be(3)
    sourceRepo.acceptFile(incomingZip("bb"), ProgressReporter())
    sourceRepo.countFiles should be(7)
    sourceRepo.acceptFile(incomingZip("cc"), ProgressReporter())
    sourceRepo.countFiles should be(11)
    sourceRepo.acceptFile(incomingZip("dd"), ProgressReporter())
    sourceRepo.countFiles should be(16)

    val seenIds = mutable.HashSet[String]()
    var recordCount = 0

    def receiveRecord(record: Pocket): Unit = {
      //      println(s"${record.id}: ${record.text}")
      if (seenIds.contains(record.id)) fail(s"seen id ${record.id}")
      recordCount += 1
      seenIds.add(record.id)
      val narthex = XML.loadString(record.text)
      val content = (narthex \ "record" \ "metadata" \ "thing" \ "box").text
      content should be("final")
    }

    sourceRepo.parsePockets(receiveRecord, VERBATIM_FILTER, ProgressReporter())

    recordCount should be(4)
  }


}
