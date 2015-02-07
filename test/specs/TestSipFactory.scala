package specs

import java.io.{File, FileOutputStream}

import dataset.SipFactory.SipGenerationFacts
import dataset.{SipFactory, SipPrefixRepo, SipRepo, SourceRepo}
import org.apache.commons.io.FileUtils._
import org.scalatest.{FlatSpec, Matchers}
import services.{FileHandling, ProgressReporter}

class TestSipFactory extends FlatSpec with Matchers {

  "A SipFactory" should "be able to create a sip from a harvest dataset, given a prefix" in {

    val home = new File(getClass.getResource("/factory/sip_factory").getFile)
    val factory = new SipFactory(home)

    val prefixRepos: Array[SipPrefixRepo] = factory.prefixRepos
    prefixRepos.size should be(2)

    val prefixRepoOpt = factory.prefixRepo("icn")
    val icn = prefixRepoOpt.getOrElse(throw new RuntimeException)

    icn.recordDefinition.getName should be("icn_1.0.4_record-definition.xml")
    icn.validation.getName should be("icn_1.0.4_validation.xsd")

    val targetDir = FileHandling.clearDir(new File("/tmp/test-sip-factory"))
    val testSipZip = new File(targetDir, "test.sip.zip")
    val existingSipZip = new File(targetDir, "existing.sip.zip")
    val copiedSipZip = new File(targetDir, "copied.sip.zip")

    val sourceDir = new File(getClass.getResource("/factory/van_abbe/source").getFile)
    val sourceRepo = new SourceRepo(sourceDir)
    val sourceXmlFile = new File(targetDir, "source.xml")
    val sourceOutput = new FileOutputStream(sourceXmlFile)
    sourceRepo.generatePockets(sourceOutput, ProgressReporter())
    sourceOutput.close()

    val facts = SipGenerationFacts(<info/>)

    icn.initiateSipZip(testSipZip, sourceXmlFile, facts)

    val sipRepo = new SipRepo(targetDir, "test", "http://about.what/")

    val schemaVersionOpt = sipRepo.latestSipOpt.flatMap(sip => sip.schemaVersionOpt)

    schemaVersionOpt.get should be("icn_1.0.4")

    deleteQuietly(testSipZip)

    val existingSip = new File(getClass.getResource("/factory/van_abbe/sips/van-abbe-museum__2014_11_25_11_44__icn.sip.zip").getFile)

    copyFile(existingSip, existingSipZip, false)

    val existingSchemaVersionOpt = sipRepo.latestSipOpt.flatMap(sip => sip.schemaVersionOpt)

    existingSchemaVersionOpt.get should be("icn_1.0.3")

    sipRepo.latestSipOpt.map { sip =>
      sip.copyWithSourceTo(copiedSipZip, new File(targetDir, "should.not.appear"), prefixRepoOpt)
    }.getOrElse(throw new RuntimeException)

    deleteQuietly(existingSipZip)

    sipRepo.latestSipOpt.map { sip =>
      sip.createSipMapper.isDefined should be(true)
      sip.schemaVersionOpt.get should be("icn_1.0.4")
      sip.sipMappingOpt.map { sipMapping =>
        sipMapping.recMapping.getSchemaVersion.toString should be("icn_1.0.4")
      }.getOrElse(throw new RuntimeException)
    }.getOrElse(throw new RuntimeException)


  }
}
