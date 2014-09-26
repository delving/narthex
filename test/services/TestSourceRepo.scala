package services

import java.io.File

import org.apache.commons.io.FileUtils
import org.apache.commons.io.FileUtils._
import org.scalatest.{FlatSpec, Matchers}
import services.RecordHandling.RawRecord

import scala.collection.mutable
import scala.xml.XML


class TestSourceRepo extends FlatSpec with Matchers with RecordHandling {

  val recordRoot = "/envelope/list/thing"
  val uniqueId = s"$recordRoot/@which"
  val dir = new File("/tmp/test-source-repo")
  deleteQuietly(dir)
  val incoming = new File("/tmp/test-source-repo-incoming")
  deleteQuietly(incoming)

  def resourceFile(letter: String): File = {
    val name = s"source-$letter.xml"
    val url = getClass.getResource(s"/source/$name")
    new File(url.getFile)
  }

  List("a", "b", "c").map(resourceFile).foreach(f => FileUtils.copyFile(f, new File(incoming, f.getName)))

  def incomingFile(letter: String): File = {
    val name = s"source-$letter.xml"
    new File(incoming, name)
  }

  val sourceRepo = new SourceRepo(dir, recordRoot, uniqueId)

  "A Source Repository" should "accept files and pages" in {

    sourceRepo.countFiles should be(0)
    sourceRepo.acceptFile(incomingFile("a")).get
    sourceRepo.countFiles should be(3)
    sourceRepo.acceptFile(incomingFile("b")).get
    sourceRepo.countFiles should be(7)
    sourceRepo.acceptFile(incomingFile("c")).get
    sourceRepo.countFiles should be(11)

    sourceRepo.acceptPage(s"""
      |<envelope>
      |  <list>
      |    <thing which="3">
      |      <box>final</box>
      |    </thing>
      |  </list>
      |</envelope>
      """.stripMargin.trim).get

    sourceRepo.countFiles should be(16)

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

    sourceRepo.parse(receiveRecord, sendProgress)

    recordCount should be(4)

    println(s"file: ${sourceRepo.getSourceFile.getAbsolutePath}")
  }

}
