package specs

import java.io.File

import dataset.{SipRepo, StagingRepo}
import org.apache.commons.io.FileUtils
import org.scalatest.{FlatSpec, Matchers}
import record.PocketParser.Pocket
import services.{FileHandling, ProgressReporter}

import scala.xml.XML

class TestSipRepo extends FlatSpec with Matchers {


  "A SipRepo" should "handle the arkivportalen harvest sip" in {

    // harvest looks like this
    // <OAI-PMH><ListRecords><record><metadata><abm:record>

    val home = new File(getClass.getResource("/sip_harvest_arkiv").getFile)
    val sipRepo = new SipRepo(new File(home, "sips"), "test", "http://aboutprefix")

    val stagingSourceDir = new File(home, "staging")
    val stagingDir = FileHandling.clearDir(new File("/tmp/test-sip-harvest"))

    val sipOpt = sipRepo.latestSipOpt
    sipOpt.isDefined should be(true)

    sipOpt.foreach { sip =>

      sip.spec should be(Some("arkivportalen"))

      sip.schemaVersionOpt.isDefined should be(true)

      FileUtils.copyDirectory(stagingSourceDir, stagingDir)
      val stagingRepo = StagingRepo(stagingDir)

      var mappedPockets = List.empty[Pocket]

      sip.createSipMapper.map { sipMapper =>
        def pocketCatcher(pocket: Pocket): Unit = {
          var mappedPocket = sipMapper.map(pocket)
          mappedPockets = mappedPocket.get :: mappedPockets
        }
        stagingRepo.parsePockets(pocketCatcher, ProgressReporter())
      }

      mappedPockets.size should be(3)

      val head = XML.loadString(mappedPockets.head.text)

      val expectedTitle = "Saksarkiv ordnet etter organets hovedsystem (Børge Brevhus, Børge Brevhus)"

      val titleText = (head \ "Description" \ "title").filter(_.prefix == "dc").text.trim

      titleText should be(expectedTitle)
    }
  }

  "A SipRepo" should "handle a harvest where the metadata is unwrapped fields" in {

    //    harvest looks like this, with no record wrapper
    //  <OAI-PMH><ListRecords><record><metadata><dc_identifier/><europeana_unstored/>

    val home = new File(getClass.getResource("/sip_harvest_abbe").getFile)
    val sipRepo = new SipRepo(new File(home, "sips"), "test", "http://aboutprefix")
    val stagingSourceDir = new File(home, "staging")
    val stagingDir = FileHandling.clearDir(new File("/tmp/test-sip-harvest"))

    val sipOpt = sipRepo.latestSipOpt
    sipOpt.isDefined should be(true)

    sipOpt.foreach { sip =>

      sip.spec should be(Some("van-abbe-museum"))

      sip.schemaVersionOpt.isDefined should be(true)

      FileUtils.copyDirectory(stagingSourceDir, stagingDir)
      val stagingRepo = StagingRepo(stagingDir)

      var mappedPockets = List.empty[Pocket]

      var count = 0
      sip.createSipMapper.map { sipMapper =>

        def pocketCatcher(pocket: Pocket): Unit = {
          var mappedPocket = sipMapper.map(pocket)
          count += 1
          mappedPockets = mappedPocket.get :: mappedPockets
        }

        stagingRepo.parsePockets(pocketCatcher, ProgressReporter())
      }

      mappedPockets.size should be(6)

      val first = XML.loadString(mappedPockets.last.text)
      val expectedTitle = "Tribute to the chaotics, Hommage aan de chaoten"
      val titleText = (first \ "Description" \ "title").filter(_.prefix == "dc").map(_.text).mkString(", ")
      titleText should be(expectedTitle)
    }
  }

  "A SipRepo" should "handle a harvest sip" in {

    // harvest looks like this
    // <OAI-PMH><ListRecords><record><metadata><arno:document>

    val home = new File(getClass.getResource("/sip_harvest").getFile)
    val sipRepo = new SipRepo(new File(home, "sips"), "test", "http://aboutprefix")

    val stagingSourceDir = new File(home, "staging")
    val stagingDir = FileHandling.clearDir(new File("/tmp/test-sip-harvest"))

    val sipOpt = sipRepo.latestSipOpt
    sipOpt.isDefined should be(true)

    sipOpt.foreach { sip =>

      sip.spec should be(Some("brabant-collectie-prent"))

      sip.uniqueElementPath should be(Some("/harvest/OAI-PMH/ListRecords/record/metadata/arno:document/arno:document-admin/arno:doc_id"))

      sip.schemaVersionOpt.isDefined should be(true)

      FileUtils.copyDirectory(stagingSourceDir, stagingDir)
      val stagingRepo = StagingRepo(stagingDir)

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

      val expectedTitle = "[Bezoek van Statenleden aan de electriciteitsfabriek te Geertruidenberg op 18 Juli 1917]."

      val titleText = (head \ "Description" \ "title").filter(_.prefix == "dc").text.trim

      titleText should be(expectedTitle)

    }
  }

  "A SipRepo" should "handle a source sip" in {

    val home = new File(getClass.getResource("/sip_source").getFile)
    val sipsDir = FileHandling.clearDir(new File("/tmp/test-sip-source-sips"))
    FileUtils.copyDirectory(home, sipsDir)
    val sipRepo = new SipRepo(sipsDir, "test", "http://aboutprefix")

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

//      println(head)

      val creatorText = (head \ "Description" \ "creator").filter(_.prefix == "dc").text.trim

      creatorText should be("Kees Verwey")
    }
  }

}
