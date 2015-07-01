package specs

import java.io._
import java.util.concurrent.Executors

import com.hp.hpl.jena.rdf.model.ModelFactory
import dataset.SipFactory
import dataset.SourceRepo.VERBATIM_FILTER
import org.scalatestplus.play.PlaySpec
import record.PocketParser.Pocket
import services.FileHandling._
import services.{FileHandling, ProgressReporter}

import scala.collection.JavaConversions._
import scala.concurrent.ExecutionContext

class TestEDM extends PlaySpec with PrepareEDM {

  implicit val ec = ExecutionContext.fromExecutor(Executors.newSingleThreadExecutor())

  "A dataset should be loaded" in {
    val sipRepo = createSipRepoFromDir(0)
    val targetDir = FileHandling.clearDir(new File(s"/tmp/test-edm/$sipRepo/target"))
    val targetFile = new File(targetDir, "edm.xml")
    val sipOpt = sipRepo.latestSipOpt
    sipOpt.isDefined must be(true)
    val sip = sipOpt.get
    val targetOutput = createWriter(targetFile)
    // fill processed repo by mapping records
    val source = sip.copySourceToTempFile
    source.isDefined must be(true)
    val sourceRepo = createSourceRepo
    sourceRepo.acceptFile(source.get, ProgressReporter())
    var mappedPockets = List.empty[Pocket]
    sip.createSipMapper.map { sipMapper =>
      def pocketCatcher(pocket: Pocket): Unit = {
        //        println(s"### parsed pocket:\n$pocket")
        var mappedPocket = sipMapper.executeMapping(pocket)
        mappedPocket.map(_.writeTo(targetOutput))
        mappedPockets = mappedPocket.get :: mappedPockets
      }
      sourceRepo.parsePockets(pocketCatcher, VERBATIM_FILTER, ProgressReporter())
    }
    mappedPockets.size must be(3)
    targetOutput.close()

    mappedPockets.take(1).foreach { pocket =>
      var recordString = pocket.text
//      println(s"### mapped pocket:\n$pocket")
      val model = ModelFactory.createDefaultModel()
      model.read(new StringReader(recordString), null, "RDF/XML")

      val isShownBy = "http://www.europeana.eu/schemas/edm/isShownBy"
      val shownList = model.listObjectsOfProperty(model.getProperty(isShownBy)).toList
      shownList.size() must be(1)
      shownList.foreach { obj =>
        println(s"isShownBy: $obj")
      }
      val dcSubject = "http://purl.org/dc/elements/1.1/subject"
      val dcSubjectList = model.listObjectsOfProperty(model.getProperty(dcSubject)).toList
      //      (dcSubjectList.size() > 2) must be(true)
      dcSubjectList.foreach { obj =>
        println(s"dcSubject: $obj")
      }

//      val turtle = new StringWriter()
//      RDFDataMgr.write(turtle, model, RDFFormat.TURTLE)
//      println(turtle)
    }

    val pocketFile = new File(targetDir, "pockets.xml")
    val pocketOutput = new FileOutputStream(pocketFile)
    val genPock = sourceRepo.generatePockets(pocketOutput, VERBATIM_FILTER, ProgressReporter())
    pocketOutput.close()

    val sipFactoryDir = new File(sipsDir, "factory")
    val sipFactory = new SipFactory(sipFactoryDir)
    val sipFile = new File(targetDir, "sip-creator-download.sip.zip")
    sip.copyWithSourceTo(sipFile, pocketFile, sipFactory.prefixRepo("edm"))
  }

  // todo: revive this in the context of EDM
  //  "A SipFactory" should "be able to create a sip from a harvest dataset, given a prefix" in {
  //
  //    val home = new File(getClass.getResource("/factory/sip_factory").getFile)
  //    val factory = new SipFactory(home)
  //
  //    val prefixRepos: Array[SipPrefixRepo] = factory.prefixRepos
  //    prefixRepos.size should be(2)
  //
  //    val prefixRepoOpt = factory.prefixRepo("icn")
  //    val icn = prefixRepoOpt.getOrElse(throw new RuntimeException)
  //
  //    icn.recordDefinition.getName should be("icn_1.0.4_record-definition.xml")
  //    icn.validation.getName should be("icn_1.0.4_validation.xsd")
  //
  //    val targetDir = FileHandling.clearDir(new File("/tmp/test-sip-factory"))
  //    val testSipZip = new File(targetDir, "test.sip.zip")
  //    val existingSipZip = new File(targetDir, "existing.sip.zip")
  //    val copiedSipZip = new File(targetDir, "copied.sip.zip")
  //
  //    val sourceDir = new File(getClass.getResource("/factory/van_abbe/source").getFile)
  //    val sourceRepo = new SourceRepo(sourceDir)
  //    val sourceXmlFile = new File(targetDir, "source.xml")
  //    val sourceOutput = new FileOutputStream(sourceXmlFile)
  //    sourceRepo.generatePockets(sourceOutput, ProgressReporter())
  //    sourceOutput.close()
  //
  //    val facts = SipGenerationFacts(<info/>)
  //
  //    icn.initiateSipZip(testSipZip, sourceXmlFile, facts)
  //
  //    val sipRepo = new SipRepo(targetDir, "test", "http://about.what/")
  //
  //    val schemaVersionOpt = sipRepo.latestSipOpt.flatMap(sip => sip.schemaVersionOpt)
  //
  //    schemaVersionOpt.get should be("icn_1.0.4")
  //
  //    deleteQuietly(testSipZip)
  //
  //    val existingSip = new File(getClass.getResource("/factory/van_abbe/sips/van-abbe-museum__2014_11_25_11_44__icn.sip.zip").getFile)
  //
  //    copyFile(existingSip, existingSipZip, false)
  //
  //    val existingSchemaVersionOpt = sipRepo.latestSipOpt.flatMap(sip => sip.schemaVersionOpt)
  //
  //    existingSchemaVersionOpt.get should be("icn_1.0.3")
  //
  //    sipRepo.latestSipOpt.map { sip =>
  //      sip.copyWithSourceTo(copiedSipZip, new File(targetDir, "should.not.appear"), prefixRepoOpt)
  //    }.getOrElse(throw new RuntimeException)
  //
  //    deleteQuietly(existingSipZip)
  //
  //    sipRepo.latestSipOpt.map { sip =>
  //      sip.createSipMapper.isDefined should be(true)
  //      sip.schemaVersionOpt.get should be("icn_1.0.4")
  //      sip.sipMappingOpt.map { sipMapping =>
  //        sipMapping.recMapping.getSchemaVersion.toString should be("icn_1.0.4")
  //      }.getOrElse(throw new RuntimeException)
  //    }.getOrElse(throw new RuntimeException)
  //  }
}
