package specs

import java.io.{File, FileOutputStream, StringReader, StringWriter}

import com.hp.hpl.jena.rdf.model.ModelFactory
import dataset.{SipFactory, SipRepo, SourceRepo}
import org.apache.jena.riot.{RDFDataMgr, RDFFormat}
import org.scalatestplus.play.{OneAppPerSuite, PlaySpec}
import record.PocketParser.Pocket
import services.FileHandling._
import services.{FileHandling, ProgressReporter}

import scala.collection.JavaConversions._

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
//        println(s"parsed pocket:\n$pocket")
        var mappedPocket = sipMapper.map(pocket)
        mappedPocket.map(_.writeTo(targetOutput))
        mappedPockets = mappedPocket.get :: mappedPockets
      }
      sourceRepo.parsePockets(pocketCatcher, ProgressReporter())
    }
    mappedPockets.size must be(3)
    targetOutput.close()

    mappedPockets.take(1).map { pocket =>
      var recordString = pocket.text
//      println(s"mapped pocket:\n$pocket")
      val model = ModelFactory.createDefaultModel()
      model.read(new StringReader(recordString), null, "RDF/XML")

      val subject = "http://acc.brabantcloud.delving.org/resource/delving/ton-smits-huis/R003"
      val isShownBy = "http://www.europeana.eu/schemas/edm/isShownBy"
      val shownList = model.listObjectsOfProperty(model.getProperty(isShownBy)).toList
      shownList.size() must be(1)
      shownList.map { obj =>
        println(s"isShownBy: $obj")
      }
      val dcSubject = "http://purl.org/dc/elements/1.1/subject"
      val dcSubjectList = model.listObjectsOfProperty(model.getProperty(dcSubject)).toList
      dcSubjectList.size() must be(6)
      dcSubjectList.map { obj =>
        println(s"dcSubject: $obj")
      }

      val turtle = new StringWriter()
      RDFDataMgr.write(turtle, model, RDFFormat.TURTLE)
      println(turtle)
    }

    val pocketFile = new File(targetDir, "pockets.xml")
    val pocketOutput = new FileOutputStream(pocketFile)
    val genPock = sourceRepo.generatePockets(pocketOutput, ProgressReporter())
    pocketOutput.close()

    val sipFactoryDir = new File(sipsDir, "factory")
    val sipFactory = new SipFactory(sipFactoryDir)
    val sipFile = new File(targetDir, "sip-creator-download.sip.zip")
    sip.copyWithSourceTo(sipFile, pocketFile, sipFactory.prefixRepo("edm"))

  }

}
