package specs

import java.io.{File, StringReader, StringWriter}

import com.hp.hpl.jena.rdf.model.ModelFactory
import org.apache.commons.io.FileUtils
import org.apache.jena.riot.{RDFDataMgr, RDFFormat}
import org.scalatest.{FlatSpec, Matchers}

class TestEDM extends FlatSpec with Matchers{

  "An EDM record" should "be read into a jena model" in {

    val xmlFile = new File(getClass.getResource("/edm/edm-record.xml").getFile)
    val xmlString = FileUtils.readFileToString(xmlFile)

    println(xmlString)

    val model = ModelFactory.createDefaultModel()
    model.read(new StringReader(xmlString), null, "RDF/XML")

    val triples = new StringWriter()
    RDFDataMgr.write(triples, model, RDFFormat.NTRIPLES_UTF8)

    println(triples)

  }
}
