package specs

import java.io.File

import org.scalatest.{FlatSpec, Matchers}
import services.SipRepo


class TestSipRepo extends FlatSpec with Matchers {

  val resource = getClass.getResource("/sip")
  val home = new File(resource.getFile)
  val repo = new SipRepo(home)

  "A SipRepo" should "fully understand the content of sip files" in {

    val sipFileOpt = repo.latestSIPFile
    sipFileOpt.isDefined should be(true)

    sipFileOpt.foreach { sipFile =>

      sipFile.spec should be(Some("brabant-collectie-prent"))

      sipFile.uniqueElementPath should be(Some("/harvest/OAI-PMH/ListRecords/record/metadata/arno:document/arno:document-admin/arno:doc_id"))

      sipFile.schemaVersionSeq.size should be(1)

      println(sipFile.sipMappings)
    }
  }


}
