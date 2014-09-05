package services

import java.io.File

import org.apache.commons.io.FileUtils._
import org.scalatest.{FlatSpec, Matchers}

class TestTermMatching extends FlatSpec with Matchers with TreeHandling with RecordHandling {

    val userHome: String = "/tmp/narthex-user"
    System.setProperty("user.home", userHome)
    deleteQuietly(new File(userHome))

//    "A NodeRepo" should "allow terminology mapping" in {
//      val repo = Repo("test@narthex.delving.org")
//      repo.create("password")
//      val datasetRepo = repo.datasetRepo("pretend-file.xml.gz")
//
//      def createNodeRepo(path: String) = {
//        val nodeDir = path.split('/').toList.foldLeft(datasetRepo.dir)((file, tag) => new File(file, Repo.tagToDirectory(tag)))
//        nodeDir.mkdirs()
//        datasetRepo.nodeRepo(path).get
//      }
//
//      Repo.startBaseX()
//
//      datasetRepo.setMapping(TermMapping("a", "http://gumby.com/gumby-is-a-fink", "cusses", "finky"))
//      datasetRepo.setMapping(TermMapping("bb", "http://gumby.com/pokey", "cusses", "horsey"))
//      datasetRepo.setMapping(TermMapping("a", "http://gumby.com/gumby", "cusses", "clayman"))
//
//      datasetRepo.getMappings.toString() should be("List(TermMapping(a,http://gumby.com/gumby,cusses,clayman), TermMapping(bb,http://gumby.com/pokey,cusses,horsey))")
//      datasetRepo.getMapping("a") should be("http://gumby.com/gumby")
//    }

  "A transformer" should "insert an enrichment" in {

    val filePrefix = "gerald_delving_eu/RCE_Beeldbank"
    val recordRoot = "/adlibXML/recordList/record"

    val mappings = Map(
      s"$filePrefix$recordRoot/inner/content.subject/Glas%20in%20loodraam" -> TargetConcept("uri", "vocab", "glasinloody"),
      s"$filePrefix$recordRoot/inner/content.subject" -> TargetConcept("uri", "vocab", "close but no cigar")
    )

    val storedString =
      """
        |<narthex id="666" xmlns:very="http://veryother.org/#">
        |  <record>
        |    <inner>
        |      <content.subject>Glas in loodraam</content.subject>
        |    </inner>
        |    <very:other>Ignore</very:other>
        |    <empty/>
        |  </record>
        |</narthex>
      """.stripMargin.trim

    val expectedString =
      """
        |<record xmlns:very="http://veryother.org/#">
        |  <inner>
        |    <content.subject enrichmentUri="uri" enrichmentVocabulary="vocab" enrichmentPrefLabel="glasinloody">Glas in loodraam</content.subject>
        |  </inner>
        |  <very:other>Ignore</very:other>
        |  <empty/>
        |</record>
      """.stripMargin.trim

    val parser = new StoredRecordParser(filePrefix, mappings)
    val record = parser.parse(storedString)
    val recordText = record.text.toString().trim
    recordText should be(expectedString)
  }
}
