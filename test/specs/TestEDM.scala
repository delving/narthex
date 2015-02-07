package specs

import java.io.{File, StringReader}

import com.hp.hpl.jena.rdf.model.ModelFactory
import dataset.{SipRepo, SourceRepo}
import org.scalatestplus.play.{OneAppPerSuite, PlaySpec}
import record.PocketParser.Pocket
import services.FileHandling._
import services.{FileHandling, ProgressReporter}

class TestEDM extends PlaySpec with OneAppPerSuite {

  //  "An EDM record should be read into a jena model" in {
  //
  //    val xmlFile = new File(getClass.getResource("/edm/edm-record.xml").getFile)
  //    val xmlString = FileUtils.readFileToString(xmlFile)
  //
  //    println(xmlString)
  //
  //    val model = ModelFactory.createDefaultModel()
  //    model.read(new StringReader(xmlString), null, "RDF/XML")
  //
  //    val triples = new StringWriter()
  //    RDFDataMgr.write(triples, model, RDFFormat.TURTLE)
  //
  //    println(triples)
  //
  //  }

  "A dataset should be loaded" in {
    // prepare for reading and mapping
    val sipsDir = new File(getClass.getResource("/edm").getFile)
    val datasetName = "ton_smits_huis"
    val naveDomain = "http://nave"
    val sipRepo = new SipRepo(sipsDir, datasetName, naveDomain)
    val sourceDir = FileHandling.clearDir(new File("/tmp/test-edm"))
    val targetDir = FileHandling.clearDir(new File("/tmp/test-edm-target"))
    val targetFile = new File(targetDir, "edm.xml")
    val sipOpt = sipRepo.latestSipOpt
    sipOpt.isDefined must be(true)
    val sip = sipOpt.get
    val targetOutput = writer(targetFile)
    // fill processed repo by mapping records
    sip.spec must be(Some("ton-smits-huis"))
    val source = sip.copySourceToTempFile
    source.isDefined must be(true)
    val sourceRepo = SourceRepo.createClean(sourceDir, SourceRepo.DELVING_SIP_SOURCE)
    sourceRepo.acceptFile(source.get, ProgressReporter())
    var mappedPockets = List.empty[Pocket]
    sip.createSipMapper.map { sipMapper =>
      def pocketCatcher(pocket: Pocket): Unit = {
        var mappedPocket = sipMapper.map(pocket)
        mappedPocket.map(_.writeTo(targetOutput))
        mappedPockets = mappedPocket.get :: mappedPockets
      }
      sourceRepo.parsePockets(pocketCatcher, ProgressReporter())
    }
    mappedPockets.size must be(393)
    targetOutput.close()

    mappedPockets.map { pocket =>
      var recordString = pocket.text
//      println(recordString)
      val model = ModelFactory.createDefaultModel()
      model.read(new StringReader(recordString), null, "RDF/XML")
//      val triples = new StringWriter()
//      RDFDataMgr.write(triples, model, RDFFormat.TURTLE)
//      println(triples)
    }

  }

}
