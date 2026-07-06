package dataset

import java.io.File

import org.apache.commons.io.FileUtils
import org.joda.time.DateTime
import org.mockito.Mockito.when
import org.scalatest.{BeforeAndAfterEach, OptionValues}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import services.ProgressReporter

import scala.jdk.CollectionConverters._

class ProcessedRepoSpec extends AnyFlatSpec with Matchers with MockitoSugar with BeforeAndAfterEach with OptionValues {

  private var tempDir: File = _

  override def beforeEach(): Unit = {
    tempDir = java.nio.file.Files.createTempDirectory("narthex-processed-repo-test-").toFile
  }

  override def afterEach(): Unit = {
    if (tempDir != null) {
      FileUtils.deleteQuietly(tempDir)
      ()
    }
  }

  "createGraphReaderXML" should "only expose graphs matching the include local id filter" in {
    val dsInfo = mock[DsInfo]
    val graphA = "http://data.example.test/doc/ds/a/graph"
    val graphB = "http://data.example.test/doc/ds/b/graph"
    when(dsInfo.extractSpecIdFromGraphName(graphA)).thenReturn("ds" -> "a")
    when(dsInfo.extractSpecIdFromGraphName(graphB)).thenReturn("ds" -> "b")

    val processed = new File(tempDir, "00000.xml")
    FileUtils.writeStringToFile(
      processed,
      s"""${rdf("a")}
         |<!--<${graphA}__hash-a>-->
         |${rdf("b")}
         |<!--<${graphB}__hash-b>-->
         |""".stripMargin,
      "UTF-8"
    )

    val repo = new ProcessedRepo(tempDir, dsInfo)
    val reader = repo.createGraphReaderXML(
      fileOpt = Some(processed),
      timeStamp = DateTime.now(),
      progressReporter = ProgressReporter(),
      includeLocalIdsOpt = Some(Set("b"))
    )

    try {
      val chunk = reader.readChunkOpt.value
      chunk.dataset.listNames().asScala.toSet shouldBe Set(graphB)
      reader.readChunkOpt shouldBe None
    } finally {
      reader.close()
    }
  }

  private def rdf(id: String): String =
    s"""<rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
       |         xmlns:dc="http://purl.org/dc/elements/1.1/">
       |  <rdf:Description rdf:about="http://example.test/$id">
       |    <dc:title>Record $id</dc:title>
       |  </rdf:Description>
       |</rdf:RDF>""".stripMargin
}
