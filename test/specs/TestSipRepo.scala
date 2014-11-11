package specs

import java.io.File

import harvest.HarvestRepo
import harvest.Harvesting.HarvestType
import org.scalatest.{FlatSpec, Matchers}
import services.{ProgressReporter, SipRepo}

class TestSipRepo extends FlatSpec with Matchers {

  "A SipRepo" should "fully understand the content of sip files" in {

    val resource = getClass.getResource("/sip")
    val home = new File(resource.getFile)

    val sipRepo = new SipRepo(new File(home, "sips"))
    val harvestRepo = new HarvestRepo(new File(home, "harvest"), HarvestType.PMH)

    val sipFileOpt = sipRepo.latestSIPFile
    sipFileOpt.isDefined should be(true)

    sipFileOpt.foreach { sipFile =>

      sipFile.spec should be(Some("brabant-collectie-prent"))

      sipFile.uniqueElementPath should be(Some("/harvest/OAI-PMH/ListRecords/record/metadata/arno:document/arno:document-admin/arno:doc_id"))

      sipFile.schemaVersionSeq.size should be(1)

      sipFile.createSipMapper("tib") map { sipMapper =>
        harvestRepo.parsePockets(pocket => println(sipMapper.map(pocket)), ProgressReporter())
      }
    }
  }
}
