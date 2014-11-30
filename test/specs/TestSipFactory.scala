package specs

import java.io.File

import dataset.{SipFactory, SipPrefixRepo}
import org.scalatest.{FlatSpec, Matchers}

class TestSipFactory extends FlatSpec with Matchers {

  "A SipFactory" should "be able to create a sip from a harvest dataset, given a prefix" in {

    val home = new File(getClass.getResource("/sip_factory").getFile)
    val factory = SipFactory(home)

    val prefixRepos: Array[SipPrefixRepo] = factory.prefixes
    prefixRepos.size should be(2)

    val icn = prefixRepos.find(_.prefix == "icn").getOrElse(throw new RuntimeException)

    icn.recordDefinition.getName should be("icn_1.0.4_record-definition.xml")
    icn.validation.getName should be("icn_1.0.4_validation.xsd")

    /*
     initial mapping: mapping_icn.xml
     ours icn_1.0.3_record-definition.xml icn_1.0.3_validation.xsd
     unzip/hints.txt
      unzip/narthex_facts.txt
      plus source.xml.gz
     */

  }

}
