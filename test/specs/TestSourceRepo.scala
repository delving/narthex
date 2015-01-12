package specs

import java.io.{File, FileOutputStream}

import dataset.SourceRepo
import dataset.SourceRepo.SourceFacts
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

  val incoming = fresh("/tmp/source-repo-incoming")

  def resourceFile(which: String): File = {
    val name = s"source-$which.zip"
    val url = getClass.getResource(s"/harvest/$name")
    new File(url.getFile)
  }

  def incomingZip(which: String): File = new File(incoming, s"source-$which.zip")

  def sendProgress(percent: Int): Boolean = true

  "A Source Repository" should "accept regular record harvest files" in {

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

    sourceRepo.parsePockets(receiveRecord, ProgressReporter())

    recordCount should be(4)

    val gitDir = fresh("/tmp/source-repo-git")
    val pocketFile = new File(gitDir, "test-source-repo.xml")
    val pocketOut = new FileOutputStream(pocketFile)

    FileHandling.ensureGitRepo(gitDir) should be(true)

    sourceRepo.generatePockets(pocketOut, ProgressReporter())

    FileHandling.gitCommit(pocketFile, "Several words of message") should be(true)
  }

  "A Source Repository" should "accept pmh record harvest files" in {

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
      val content = (narthex \ "thing" \ "box").text
      content should be("final")
    }

    sourceRepo.parsePockets(receiveRecord, ProgressReporter())

    recordCount should be(4)
  }

  "A Source Repository" should "accept delving sip source files" in {
    val sourceDir = fresh("/tmp/test-source-repo-plain")
    val sourceFile = new File(getClass.getResource("/source/Martena.xml.gz").getFile)
    val sourceRepo = SourceRepo.createClean(sourceDir, SourceRepo.DELVING_SIP_SOURCE)
    sourceRepo.countFiles should be(0)
    sourceRepo.acceptFile(sourceFile, ProgressReporter())
    sourceRepo.countFiles should be(3)
    var recordCount = 0

    def receiveRecord(record: Pocket): Unit = {
      //      println(s"${record.id}: ${record.text}")
      recordCount += 1
    }

    sourceRepo.parsePockets(receiveRecord, ProgressReporter())

    recordCount should be(4724)
  }

  "A Source Repository" should "handle a source xml file" in {

    val home = new File(getClass.getResource("/source").getFile)
    val sourceDir = new File("/tmp/test-sip-repo-source")
    val sourceFile = new File(getClass.getResource("/source/Martena-sample.xml").getFile)
    val sourceRepo = SourceRepo.createClean(sourceDir, SourceRepo.DELVING_SIP_SOURCE)

    sourceRepo.countFiles should be(0)
    sourceRepo.acceptFile(sourceFile, ProgressReporter())
    sourceRepo.countFiles should be(3)

    var recordCount = 0

    def receiveRecord(record: Pocket): Unit = {
      //      println(s"${record.id}: ${record.text}")
      recordCount += 1
    }

    sourceRepo.parsePockets(receiveRecord, ProgressReporter())

    recordCount should be(5)
  }
}
