package specs

import java.io.File

import dataset.StagingRepo.StagingFacts
import dataset.{SipRepo, StagingRepo}
import harvest.Harvesting.HarvestType
import org.apache.commons.io.FileUtils
import org.scalatest.{FlatSpec, Matchers}
import record.PocketParser.Pocket
import services.{FileHandling, ProgressReporter}

import scala.xml.XML

class TestSipRepo extends FlatSpec with Matchers {

  "A SipRepo" should "handle a harvest sip" in {

    val home = new File(getClass.getResource("/sip_harvest").getFile)
    val sipRepo = new SipRepo(new File(home, "sips"))
    val stagingSourceDir = new File(home, "staging")
    val stagingDir = FileHandling.clearDir(new File("/tmp/test-sip-harvest"))

    val sipOpt = sipRepo.latestSipOpt
    sipOpt.isDefined should be(true)

    sipOpt.foreach { sip =>

      sip.spec should be(Some("brabant-collectie-prent"))

      sip.uniqueElementPath should be(Some("/harvest/OAI-PMH/ListRecords/record/metadata/arno:document/arno:document-admin/arno:doc_id"))

      sip.schemaVersionOpt.isDefined should be(true)

      val stagingRepo = StagingRepo.createClean(stagingDir, StagingFacts(HarvestType.PMH))
      FileUtils.copyDirectory(stagingSourceDir, stagingDir)

      var mappedPockets = List.empty[Pocket]

      sip.createSipMapper.map { sipMapper =>
        def pocketCatcher(pocket: Pocket): Unit = {
          var mappedPocket = sipMapper.map(pocket)
          mappedPockets = mappedPocket.get :: mappedPockets
        }
        stagingRepo.parsePockets(pocketCatcher, ProgressReporter())
      }

      mappedPockets.size should be(5)

      val head = XML.loadString(mappedPockets.head.text)

      println(head)

      val expectedTitle = "[Bezoek van Statenleden aan de electriciteitsfabriek te Geertruidenberg op 18 Juli 1917]."

      val titleText = (head \ "Description" \ "title").filter(_.prefix == "dc").text.trim

      titleText should be(expectedTitle)

    }
  }

  "A SipRepo" should "handle a source sip" in {

    val home = new File(getClass.getResource("/sip_source").getFile)
    val sipsDir = FileHandling.clearDir(new File("/tmp/test-sip-source-sips"))
    FileUtils.copyDirectory(home, sipsDir)
    val sipRepo = new SipRepo(sipsDir)

    val stagingDir = FileHandling.clearDir(new File("/tmp/test-sip-source-staging"))

    val sipOpt = sipRepo.latestSipOpt
    sipOpt.isDefined should be(true)
    sipOpt.foreach { sip =>

      sip.spec should be(Some("frans-hals-museum"))

      val source = sip.copySourceToTempFile
      source.isDefined should be(true)

      val stagingRepo = StagingRepo.createClean(stagingDir, StagingRepo.DELVING_SIP_SOURCE)

      stagingRepo.acceptFile(source.get, ProgressReporter())

      var mappedPockets = List.empty[Pocket]

      sip.createSipMapper.map { sipMapper =>
        def pocketCatcher(pocket: Pocket): Unit = {
          //          println(pocket)
          var mappedPocket = sipMapper.map(pocket)
          mappedPockets = mappedPocket.get :: mappedPockets
        }
        stagingRepo.parsePockets(pocketCatcher, ProgressReporter())
      }

      mappedPockets.size should be(5)

      val head = XML.loadString(mappedPockets.head.text)
      val creator = "Kees Verwey"

      println(head)

      val creatorText = (head \ "Description" \ "creator").filter(_.prefix == "dc").text.trim

      creatorText should be("Kees Verwey")
    }
  }
}
