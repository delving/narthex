package specs

import java.io.{File, FileOutputStream}

import dataset.StagingRepo
import dataset.StagingRepo.StagingFacts
import harvest.Harvesting._
import org.apache.commons.io.FileUtils
import org.scalatest.{FlatSpec, Matchers}
import record.PocketParser._
import services.{FileHandling, ProgressReporter}

import scala.collection.mutable
import scala.xml.XML


class TestStagingRepo extends FlatSpec with Matchers {

  def fresh(dir: String): File = {
    val file = new File(dir)
    FileHandling.clearDir(file)
    file
  }

  val incoming = fresh("/tmp/staging-repo-incoming")

  def resourceFile(which: String): File = {
    val name = s"source-$which.zip"
    val url = getClass.getResource(s"/harvest/$name")
    new File(url.getFile)
  }

  def incomingZip(which: String): File = new File(incoming, s"source-$which.zip")

  def sendProgress(percent: Int): Boolean = true

  "A Staging Repository" should "accept regular record harvest files" in {

    List("a", "b", "c", "d").map(resourceFile).foreach(f => FileUtils.copyFile(f, new File(incoming, f.getName)))
    val recordRoot = "/envelope/list/thing"
    val uniqueId = s"$recordRoot/@which"
    val stagingDir = fresh("/tmp/test-staging-repo-regular")

    val stagingRepo = StagingRepo.createClean(stagingDir, StagingFacts("gumby", recordRoot, uniqueId, None))

    stagingRepo.countFiles should be(0)
    stagingRepo.acceptFile(incomingZip("a"), ProgressReporter())
    stagingRepo.countFiles should be(3)
    stagingRepo.acceptFile(incomingZip("b"), ProgressReporter())
    stagingRepo.countFiles should be(7)
    stagingRepo.acceptFile(incomingZip("c"), ProgressReporter())
    stagingRepo.countFiles should be(11)
    stagingRepo.acceptFile(incomingZip("d"), ProgressReporter())
    stagingRepo.countFiles should be(16)

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

    stagingRepo.parsePockets(receiveRecord, ProgressReporter())

    recordCount should be(4)

    val gitDir = fresh("/tmp/staging-repo-git")
    val pocketFile = new File(gitDir, "test-source-repo.xml")
    val pocketOut = new FileOutputStream(pocketFile)
    val unused = new File(gitDir, "unused.xml")

    FileHandling.ensureGitRepo(gitDir) should be(true)

    stagingRepo.generateSource(pocketOut, unused, None, ProgressReporter())

    FileHandling.gitCommit(pocketFile, "Several words of message") should be(true)
  }

  "A Staging Repository" should "accept pmh record harvest files" in {

    List("aa", "bb", "cc", "dd").map(resourceFile).foreach(f => FileUtils.copyFile(f, new File(incoming, f.getName)))

    val stagingDir = fresh("/tmp/test-staging-repo-pmh")
    val stagingRepo = StagingRepo.createClean(stagingDir, StagingFacts(HarvestType.PMH))

    stagingRepo.countFiles should be(0)
    stagingRepo.acceptFile(incomingZip("aa"), ProgressReporter())
    stagingRepo.countFiles should be(3)
    stagingRepo.acceptFile(incomingZip("bb"), ProgressReporter())
    stagingRepo.countFiles should be(7)
    stagingRepo.acceptFile(incomingZip("cc"), ProgressReporter())
    stagingRepo.countFiles should be(11)
    stagingRepo.acceptFile(incomingZip("dd"), ProgressReporter())
    stagingRepo.countFiles should be(16)

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

    stagingRepo.parsePockets(receiveRecord, ProgressReporter())

    recordCount should be(4)
  }

  "A Staging Repository" should "accept delving sip source files" in {
    val stagingDir = fresh("/tmp/test-staging-repo-plain")
    val sourceFile = new File(getClass.getResource("/source/Martena.xml.gz").getFile)
    val stagingRepo = StagingRepo.createClean(stagingDir, StagingRepo.DELVING_SIP_SOURCE)
    stagingRepo.countFiles should be(0)
    stagingRepo.acceptFile(sourceFile, ProgressReporter())
    stagingRepo.countFiles should be(3)
    var recordCount = 0

    def receiveRecord(record: Pocket): Unit = {
      //      println(s"${record.id}: ${record.text}")
      recordCount += 1
    }

    stagingRepo.parsePockets(receiveRecord, ProgressReporter())

    recordCount should be(4724)
  }

  "A Staging Repository" should "handle a source xml file" in {

    val home = new File(getClass.getResource("/source").getFile)
    val stagingDir = new File("/tmp/test-sip-repo-staging")
    val sourceFile = new File(getClass.getResource("/source/Martena-sample.xml").getFile)
    val stagingRepo = StagingRepo.createClean(stagingDir, StagingRepo.DELVING_SIP_SOURCE)

    stagingRepo.countFiles should be(0)
    stagingRepo.acceptFile(sourceFile, ProgressReporter())
    stagingRepo.countFiles should be(3)

    var recordCount = 0

    def receiveRecord(record: Pocket): Unit = {
      //      println(s"${record.id}: ${record.text}")
      recordCount += 1
    }

    stagingRepo.parsePockets(receiveRecord, ProgressReporter())

    recordCount should be(5)
  }
}
