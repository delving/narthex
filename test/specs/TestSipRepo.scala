package specs

import java.io.File

import harvest.HarvestRepo
import harvest.Harvesting.HarvestType
import org.scalatest.{FlatSpec, Matchers}
import record.PocketParser.Pocket
import services.{ProgressReporter, SipRepo}

import scala.xml.XML

class TestSipRepo extends FlatSpec with Matchers {

  "A SipRepo" should "fully understand the content of sip files" in {

    val resource = getClass.getResource("/sip")
    val home = new File(resource.getFile)

    val sipRepo = new SipRepo(new File(home, "sips"))
    val harvestRepo = new HarvestRepo(new File(home, "harvest"), HarvestType.PMH)

    val sipFileOpt = sipRepo.latestSipFile
    sipFileOpt.isDefined should be(true)

    sipFileOpt.foreach { sipFile =>

      sipFile.spec should be(Some("brabant-collectie-prent"))

      sipFile.uniqueElementPath should be(Some("/harvest/OAI-PMH/ListRecords/record/metadata/arno:document/arno:document-admin/arno:doc_id"))

      sipFile.schemaVersionSeq.size should be(1)

      var mappedPockets = List.empty[Pocket]

      sipFile.createSipMapper("tib") map { sipMapper =>
        def pocketCatcher(pocket: Pocket): Unit = {
          var mappedPocket = sipMapper.map(pocket)
          mappedPockets = mappedPocket :: mappedPockets
        }
        harvestRepo.parsePockets(pocketCatcher, ProgressReporter())
      }

      mappedPockets.size should be (25)

      val head = XML.loadString(mappedPockets.head.text)

      println(head)

      val eglise = "L'Eglise collegiale de Notre Dame a Breda.Harrewijn fecit"

      val titleText = (head \ "Description" \ "title").filter(_.prefix == "dc").text.trim

      titleText should be(eglise)

    }
  }
}
