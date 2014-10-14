package services

import java.io.File

import org.apache.commons.io.FileUtils
import org.apache.commons.io.FileUtils._
import org.scalatest.{FlatSpec, Matchers}
import services.RecordHandling.RawRecord

import scala.collection.mutable
import scala.xml.XML


class TestHarvestRepo extends FlatSpec with Matchers with RecordHandling {

  val recordRoot = "/envelope/list/thing"
  val uniqueId = s"$recordRoot/@which"
  
  def fresh(dir: String): File = {
    val file = new File(dir)
    deleteQuietly(file)
    file.mkdirs()
    file
  }

  val incoming = fresh("/tmp/test-source-repo-incoming")
  val sourceDir = fresh("/tmp/test-source-repo")
  val gitDir = fresh("/tmp/test-source-repo-git")
  val gitFile = new File(gitDir, s"test-source-repo.xml")

  def resourceFile(letter: String): File = {
    val name = s"source-$letter.xml"
    val url = getClass.getResource(s"/source/$name")
    new File(url.getFile)
  }

  List("a", "b", "c").map(resourceFile).foreach(f => FileUtils.copyFile(f, new File(incoming, f.getName)))
//  deleteQuietly(incoming)

  def incomingPage(letter: String): String = {
    val name = s"source-$letter.xml"
    val file = new File(incoming, name)
    FileUtils.readFileToString(file)
  }

  val harvestRepo = new HarvestRepo(sourceDir, recordRoot, uniqueId, gitFile)

  "A Source Repository" should "accept files and pages" in {

    harvestRepo.countFiles should be(0)
    harvestRepo.acceptPage(incomingPage("a")).get
    harvestRepo.countFiles should be(3)
    harvestRepo.acceptPage(incomingPage("b")).get
    harvestRepo.countFiles should be(7)
    harvestRepo.acceptPage(incomingPage("c")).get
    harvestRepo.countFiles should be(11)

    harvestRepo.acceptPage(s"""
      |<envelope>
      |  <list>
      |    <thing which="3">
      |      <box>final</box>
      |    </thing>
      |  </list>
      |</envelope>
      """.stripMargin.trim).get

    harvestRepo.countFiles should be(16)

    val seenIds = mutable.HashSet[String]()
    var recordCount = 0

    def receiveRecord(record: RawRecord): Unit = {
      //      println(s"${record.id}: ${record.text}")
      if (seenIds.contains(record.id)) fail(s"seen id ${record.id}")
      recordCount += 1
      seenIds.add(record.id)
      val narthex = XML.loadString(record.text)
      val content = (narthex \ "thing" \ "box").text
      content should be("final")
    }

    def sendProgress(percent: Int): Boolean = {
//      println(s"$percent%")
      true
    }

    harvestRepo.parse(receiveRecord, sendProgress)

    recordCount should be(4)

    FileHandling.ensureGitRepo(gitDir) should be(true)
    
    harvestRepo.generateSourceFile(percent => true, map => Unit)

    FileHandling.gitCommit(gitFile, "Several words of message") should be(true)
  }

}
