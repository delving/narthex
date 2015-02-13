package specs

import java.io._
import java.util.zip.{GZIPOutputStream, ZipEntry, ZipOutputStream}

import com.hp.hpl.jena.rdf.model.ModelFactory
import dataset.{SipFactory, SipRepo, SourceRepo}
import org.apache.commons.io.IOUtils
import org.apache.jena.riot.{RDFDataMgr, RDFFormat}
import org.scalatestplus.play.{OneAppPerSuite, PlaySpec}
import record.PocketParser
import record.PocketParser.Pocket
import services.FileHandling._
import services.{FileHandling, ProgressReporter}

import scala.collection.JavaConversions._

class TestEDM extends PlaySpec with OneAppPerSuite {

  val naveDomain = "http://nave"
  val sipsDir = new File(getClass.getResource("/edm").getFile)
  val sipZipFile = new File(sipsDir, "test-edm.sip.zip")

  def copyFile(zos: ZipOutputStream, sourceFile: File): Unit = {
    val file = if (sourceFile.getName == "source.xml") {
      val gzFile = new File(sourceFile.getParentFile, "source.xml.gz")
      val gz = new GZIPOutputStream(new FileOutputStream(gzFile))
      val is = new FileInputStream(sourceFile)
      IOUtils.copy(is, gz)
      gz.close()
      gzFile
    }
    else {
      sourceFile
    }
    println(s"including $file")
    zos.putNextEntry(new ZipEntry(file.getName))
    val is = new FileInputStream(file)
    IOUtils.copy(is, zos)
    zos.closeEntry()
  }

  def createSipRepoFromDir(dirName: String): SipRepo = {
    val source = new File(sipsDir, dirName)
    val zos = new ZipOutputStream(new FileOutputStream(sipZipFile))
    source.listFiles().toList.filter(!_.getName.endsWith(".gz")).map(f => copyFile(zos, f))
    zos.close()
    println(s"created $sipZipFile")
    new SipRepo(sipsDir, dirName, naveDomain)
  }

  "A dataset should be loaded" in {
    val whichOne = 1
    val dirName = List("ton-smits", "difo")(whichOne)
    val sipRepo = createSipRepoFromDir(dirName)
    val sourceDir = FileHandling.clearDir(new File(s"/tmp/test-edm/$dirName/source"))
    val targetDir = FileHandling.clearDir(new File(s"/tmp/test-edm/$dirName/target"))
    val targetFile = new File(targetDir, "edm.xml")
    val sipOpt = sipRepo.latestSipOpt
    sipOpt.isDefined must be(true)
    val sip = sipOpt.get
    val targetOutput = writer(targetFile)
    // fill processed repo by mapping records
    val source = sip.copySourceToTempFile
    source.isDefined must be(true)
    val sourceRepo = SourceRepo.createClean(sourceDir, PocketParser.POCKET_SOURCE_FACTS)
    sourceRepo.acceptFile(source.get, ProgressReporter())
    var mappedPockets = List.empty[Pocket]
    sip.createSipMapper.map { sipMapper =>
      def pocketCatcher(pocket: Pocket): Unit = {
        //        println(s"### parsed pocket:\n$pocket")
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
      println(s"### mapped pocket:\n$pocket")
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
      (dcSubjectList.size() > 2) must be(true)
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
