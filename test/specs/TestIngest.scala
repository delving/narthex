package specs

import java.io.File

import dataset.SourceRepo.SourceFacts
import dataset.{SipRepo, SourceRepo}
import org.apache.commons.io.FileUtils
import org.scalatest.{FlatSpec, Matchers}
import record.PocketParser.Pocket
import services.{FileHandling, ProgressReporter}

import scala.xml.XML

class TestIngest extends FlatSpec with Matchers {

  "A raw PMH harvest with no record" should "be parsed" in {
    // <metadata> contains fields like dc:identifier
    // Africa Museum
    // harvestType=pmh-rec
    // harvestUrl=http://62.221.199.184:5001/oai
    // harvestPrefix=oai_dc
    val sourceDir = new File(getClass.getResource("/ingest/pmh_no_record").getFile)
    val sourceRepo = new SourceRepo(sourceDir)
    var recordCount = 0
    def receiveRecord(pocket: Pocket): Unit = {
      //      println(s"${pocket.id}: ${pocket.text}")
      recordCount += 1
    }
    sourceRepo.parsePockets(receiveRecord, ProgressReporter())
    recordCount should be(40)
  }

  "A sip source file" should "be interpreted properly" in {
    val home = new File(getClass.getResource("/ingest/sip_source").getFile)
    val sipsDir = FileHandling.clearDir(new File("/tmp/test-sip-source-sips"))
    FileUtils.copyDirectory(home, sipsDir)
    val sipRepo = new SipRepo(sipsDir, "test", "http://aboutprefix")
    val sourceDir = FileHandling.clearDir(new File("/tmp/test-sip-source-3"))
    val sipOpt = sipRepo.latestSipOpt
    sipOpt.isDefined should be(true)
    sipOpt.foreach { sip =>
      sip.spec should be(Some("frans-hals-museum"))
      val source = sip.copySourceToTempFile
      source.isDefined should be(true)
      val sourceFacts = SourceFacts(
        "delving-sip-source",
        "/delving-sip-source/input",
        "/delving-sip-source/input/@id",
        Some("/delving-sip-source/input/@id")
      )
      val sourceRepo = SourceRepo.createClean(sourceDir, sourceFacts)
      sourceRepo.acceptFile(source.get, ProgressReporter())
      var mappedPockets = List.empty[Pocket]
      sip.createSipMapper.map { sipMapper =>
        def pocketCatcher(pocket: Pocket): Unit = {
          //          println(pocket)
          var mappedPocket = sipMapper.map(pocket)
          mappedPockets = mappedPocket.get :: mappedPockets
        }
        sourceRepo.parsePockets(pocketCatcher, ProgressReporter())
      }
      mappedPockets.size should be(5)

      println(mappedPockets.head.text)

      val head = XML.loadString(mappedPockets.head.text)
      val creator = "Kees Verwey"
      val creatorText = (head \ "creator").filter(_.prefix == "dc").text.trim
      creatorText should be("Kees Verwey")
    }
  }
}
