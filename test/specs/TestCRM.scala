package specs

import java.util

import com.hp.hpl.jena.rdf.model.{ModelFactory, Property, Resource, Statement}
import org.scalatest.{FlatSpec, Matchers}

import scala.collection.JavaConversions._

class TestCRM extends FlatSpec with Matchers {

  "The CRM" should "appear in JSON" in {

    val RDF = "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
    val RDFS = "http://www.w3.org/2000/01/rdf-schema#"
    val CRM = "http://www.cidoc-crm.org/cidoc-crm/"

    val inputStream = getClass.getResource("/crm/cidoc_crm_v5.0.4_official_release.rdfs.xml").openStream()
    val model = ModelFactory.createDefaultModel()
    model.read(inputStream, null)

    val rdfsLabel = model.getProperty(RDFS, "label")
    val rdfsDomain = model.getProperty(RDFS, "domain")
    val rdfsRange = model.getProperty(RDFS, "range")
    val rdfsSubClassOf = model.getProperty(RDFS, "subClassOf")
    val rdfsSubPropertyOf = model.getProperty(RDFS, "subPropertyOf")
    val rdfsClass = model.getProperty(RDFS, "Class")
    val rdfProperty = model.getProperty(RDF, "Property")
    val rdfType = model.getProperty(RDF, "type")

    //    def englishLiteral(sub: Resource, pred: Property) = {
    //      val literals = statements(Some(sub), Some(pred), None).map(_.getObject.asLiteral())
    //      literals.filter(_.getLanguage == "en").head.getString
    //    }

    case class CRMClass(resource: Resource) {
      override def toString = resource.getLocalName
    }

    case class CRMProperty(resource: Resource) {
      override def toString = resource.getLocalName
    }

    def statements(sub: Option[Resource], pred: Option[Property], obj: Option[Resource]): util.List[Statement] = {
      model.listStatements(sub.orNull, pred.orNull, obj.orNull).toList
    }

    def subjects(pred: Property, obj: Resource) = statements(None, Some(pred), Some(obj)).map(_.getSubject)

    def objects(sub: Resource, pred: Property) = statements(Some(sub), Some(pred), None).map(_.getObject.asResource())

    def domains(sub: Resource) = objects(sub, rdfsDomain).map(CRMClass)

    def ranges(sub: Resource) = objects(sub, rdfsRange).map(CRMClass)

    val crmClasses = subjects(rdfType, rdfsClass).map(CRMClass)

    val crmProperties = subjects(rdfType, rdfProperty).map(CRMProperty)

    val crmClassesSuperclasses = crmClasses.map(c =>
      (c, objects(c.resource, rdfsSubClassOf).map(CRMClass))
    )

    val crmPropertiesSuperproperties = crmProperties.map(c =>
      (c, objects(c.resource, rdfsSubPropertyOf).map(CRMProperty))
    )

    val crmClassesProperties = crmClasses.map(c =>
      (c, subjects(rdfsDomain, c.resource).map(CRMProperty))
    )

//    println(s"classes/properties:\n${crmClassesProperties.mkString("\n")}")

    //    val crmPropertiesDomainRange = crmProperties.map(p =>
//      (p, objects(p.resource, rdfsDomain).map(CRMClass), objects(p.resource, rdfsRange).map(CRMClass))
//    )
//
//    println(s"prop, domain, range:\n${crmPropertiesDomainRange.mkString("\n")}")

    //    println(s"classes, supers:\n${crmClassesSuperclasses.mkString("\n")}")

    //    case class CRMClass(uri: String, name: String, var domains: List[CRMClass] = List.empty)

    //    val crmClasses = crmClassResources.map(sub => sub.getURI -> CRMClass(sub.getURI, sub.getLocalName)).toMap

    //    println(s"classes:\n${crmClassResources.mkString("\n")}")

    //    println(s"properties:\n${crmProperties.mkString("\n")}")

    model.listStatements().toList.map { s =>
      if (s.toString.contains("P13_")) println(s)
    }
    //
    //    val classes = model.listStatements(null, property, null).map(_.getSubject).map(
    //      subject => subject.getURI
    //    ).toSeq
    //
    //    println(s"classes:\n ${classes.mkString("\n")}")
  }
}
