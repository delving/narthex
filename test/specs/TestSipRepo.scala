package specs

import java.io.File

import eu.delving.groovy._
import eu.delving.metadata.MappingResultImpl
import harvest.HarvestRepo
import harvest.Harvesting.HarvestType
import org.scalatest.{FlatSpec, Matchers}
import record.PocketParser.Pocket
import services.{ProgressReporter, SipRepo}

import scala.collection.JavaConversions._

class TestSipRepo extends FlatSpec with Matchers {

  "A SipRepo" should "fully understand the content of sip files" in {

    val resource = getClass.getResource("/sip")
    val home = new File(resource.getFile)

    val sipRepo = new SipRepo(new File(home, "sips"))
    val harvestRepo = new HarvestRepo(new File(home, "harvest"), HarvestType.PMH)

    val sipFileOpt = sipRepo.latestSIPFile
    sipFileOpt.isDefined should be(true)

    sipFileOpt.foreach { sipFile =>

      sipFile.spec should be(Some("brabant-collectie-prent"))

      sipFile.uniqueElementPath should be(Some("/harvest/OAI-PMH/ListRecords/record/metadata/arno:document/arno:document-admin/arno:doc_id"))

      sipFile.schemaVersionSeq.size should be(1)

      sipFile.sipMappings.get("tib").map { tib =>

        val mappingRunner: MappingRunner = {
          val groovyCodeResource = new GroovyCodeResource(getClass.getClassLoader)
          new MappingRunner(groovyCodeResource, tib.recMapping, null, false)
        }

        val namespaces: Map[String, String] = tib.recDefTree.getRecDef.namespaces.map(ns => ns.prefix -> ns.uri).toMap

        val metadataRecordFactory = new MetadataRecordFactory(namespaces)

        val serializer = new XmlSerializer

        var caught = false

        def pocketCatcher(pocket: Pocket) = {
          if (!caught) {
//            println(pocket)
            val metadataRecord = metadataRecordFactory.metadataRecordFrom(pocket.id, pocket.text.replaceAll("pocket", "input"))
            val child = metadataRecord.getRootNode.children().get(0).asInstanceOf[GroovyNode]
            val mr = MetadataRecord.create(child, -1, -1)
//            println(XmlNodePrinter.toXml(mr.getRootNode))
            val result = new MappingResultImpl(serializer, pocket.id, mappingRunner.runMapping(mr), mappingRunner.getRecDefTree).resolve
            println(result)
          }
          caught=true
        }

        harvestRepo.parsePockets(pocketCatcher, ProgressReporter())
        true
      }.getOrElse(throw new RuntimeException)
    }
  }


}
