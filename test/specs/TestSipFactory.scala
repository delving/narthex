package specs

import java.io.{File, FileOutputStream}

import dataset.SipFactory.SipGenerationFacts
import dataset.{SipFactory, SipPrefixRepo, StagingRepo}
import org.scalatest.{FlatSpec, Matchers}
import services.{FileHandling, ProgressReporter}

class TestSipFactory extends FlatSpec with Matchers {

  "A SipFactory" should "be able to create a sip from a harvest dataset, given a prefix" in {

    val home = new File(getClass.getResource("/sip_factory").getFile)
    val factory = new SipFactory(home)

    val prefixRepos: Array[SipPrefixRepo] = factory.prefixRepos
    prefixRepos.size should be(2)

    val icn = factory.prefixRepo("icn").getOrElse(throw new RuntimeException)

    icn.recordDefinition.getName should be("icn_1.0.4_record-definition.xml")
    icn.validation.getName should be("icn_1.0.4_validation.xsd")

    val targetDir = FileHandling.clearDir(new File("/tmp/test-sip-factory"))
    val sipFile = new File(targetDir, "test.sip.zip")

    val stagingDir = new File(getClass.getResource("/sip_harvest_abbe/staging").getFile)
    val stagingRepo = StagingRepo(stagingDir)
    val sourceXmlFile = new File(targetDir, "source.xml")
    val sourceOutput = new FileOutputStream(sourceXmlFile)
    stagingRepo.generateSource(sourceOutput, new File(targetDir, "should.not.appear"), None, ProgressReporter())
    sourceOutput.close()

    val facts = SipGenerationFacts(<info/>)

    icn.generateSip(sipFile, sourceXmlFile, facts)

    /*
     initial mapping: mapping_icn.xml
     ours icn_1.0.3_record-definition.xml icn_1.0.3_validation.xsd
     unzip/hints.txt
      unzip/narthex_facts.txt
      plus source.xml.gz
     */

  }

}
