//===========================================================================
//    Copyright 2014 Delving B.V.
//
//    Licensed under the Apache License, Version 2.0 (the "License");
//    you may not use this file except in compliance with the License.
//    You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
//    Unless required by applicable law or agreed to in writing, software
//    distributed under the License is distributed on an "AS IS" BASIS,
//    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//    See the License for the specific language governing permissions and
//    limitations under the License.
//===========================================================================

package services

import com.rockymadden.stringmetric.similarity.RatcliffObershelpMetric
import play.api.libs.json._
import services.TermDb.TermMapping

import scala.collection.mutable
import scala.io.Source
import scala.xml.pull._
import scala.xml.{MetaData, NamespaceBinding}

object SkosVocabulary {

  val XML = "http://www.w3.org/XML/1998/namespace"
  val RDF = "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
  val SKOS = "http://www.w3.org/2004/02/skos/core#"


  def apply(source: Source) = {

    val events = new XMLEventReader(source)

    def attribute(uri: String, label: String, attributes: MetaData, scope: NamespaceBinding) = {
      attributes.get(uri, scope, label) match {
        case None => None
        case Some(x) => Some(x.head.text)
      }
    }

    def about(attributes: MetaData, scope: NamespaceBinding) = attribute(RDF, "about", attributes, scope)
    def lang(attributes: MetaData, scope: NamespaceBinding) = attribute(XML, "lang", attributes, scope)
    def resource(attributes: MetaData, scope: NamespaceBinding) = attribute(RDF, "resource", attributes, scope)

    val vocabulary = new SkosVocabulary()
    var withinScheme: Option[ConceptScheme] = None
    var withinConcept: Option[Concept] = None
    var withinLabel: Option[Label] = None
    var withinDefinition: Option[Definition] = None
    var allConcepts = new mutable.HashMap[String, Concept]()
    val textBuilder = new mutable.StringBuilder()

    while (events.hasNext) {
      events.next() match {

        // ===============================
        case EvElemStart("rdf", "RDF", attributes, scope) =>
        case EvElemEnd("rdf", "RDF") =>

        // ===============================
        case EvElemStart("skos", "ConceptScheme", attributes, scope) =>
          val scheme: ConceptScheme = new ConceptScheme(about(attributes, scope).get)
          vocabulary.conceptSchemes += scheme
          withinScheme = Some(scheme)
        case EvElemEnd("skos", "ConceptScheme") =>
          withinScheme = None
          textBuilder.clear()

        // ===============================
        case EvElemStart("skos", "topConceptOf", attributes, scope) =>
          val r = resource(attributes, scope)
          vocabulary.conceptScheme(r.get).map {
            s =>
              if (s.about != r.get) throw new RuntimeException
              s.topConcepts += withinConcept.get
          }
        case EvElemEnd("skos", "topConceptOf") =>
          textBuilder.clear()

        // ===============================
        case EvElemStart("skos", "inScheme", attributes, scope) =>
          val r = resource(attributes, scope)
          val scheme: Option[ConceptScheme] = vocabulary.conceptScheme(r.get)
          scheme.map(s => withinConcept.map { concept =>
            concept.conceptScheme = scheme
            s.concepts += concept
          })
        case EvElemEnd("skos", "inScheme") =>
          textBuilder.clear()

        // ===============================
        case EvElemStart("skos", "Concept", attributes, scope) =>
          val concept: Concept = new Concept(about(attributes, scope).get)
          withinConcept = Some(concept)
          allConcepts.put(concept.about, concept)
        case EvElemEnd("skos", "Concept") =>
          withinConcept = None
          textBuilder.clear()

        // ===============================
        case EvElemStart("skos", "definition", attributes, scope) =>
          withinConcept.map(c => {
            val definition: Definition = new Definition(lang(attributes, scope).get)
            withinDefinition = Some(definition)
            c.definitions += definition
          })
        case EvElemEnd("skos", "definition") =>
          withinDefinition.map(d => d.text = textBuilder.toString())
          withinDefinition = None
          textBuilder.clear()

        // ===============================
        case EvElemStart("skos", "prefLabel", attributes, scope) =>
          withinConcept.map(c => {
            val prefLabel = new Label(true, lang(attributes, scope).get)
            withinLabel = Some(prefLabel)
            c.labels += prefLabel
          })
          withinScheme.map(s => {
            val prefLabel = new Label(true, lang(attributes, scope).get)
            withinLabel = Some(prefLabel)
            s.labels += prefLabel
          })
        case EvElemEnd("skos", "prefLabel") =>
          withinLabel.map(_.text = textBuilder.toString())
          withinLabel = None
          textBuilder.clear()

        // ===============================
        case EvElemStart("skos", "altLabel", attributes, scope) =>
          withinConcept.map(c => {
            val altLabel = new Label(false, lang(attributes, scope).get)
            withinLabel = Some(altLabel)
            c.labels += altLabel
          })
          withinScheme.map(s => {
            val prefLabel = new Label(true, lang(attributes, scope).get)
            withinLabel = Some(prefLabel)
            s.labels += prefLabel
          })
        case EvElemEnd("skos", "altLabel") =>
          withinLabel.map(d => d.text = textBuilder.toString())
          withinLabel = None
          textBuilder.clear()

        // ===============================
        case EvElemStart("skos", "broader", attributes, scope) =>
          withinConcept.map(c => c.broaderResources += resource(attributes, scope).get)
        case EvElemEnd("skos", "broader") =>
          textBuilder.clear()

        // ===============================
        case EvElemStart("skos", "narrower", attributes, scope) =>
          withinConcept.map(c => c.narrowerResources += resource(attributes, scope).get)
        case EvElemEnd("skos", "narrower") =>
          textBuilder.clear()

        // ===============================
        case EvElemStart("skos", "hiddenLabel", attributes, scope) =>
        //  println("Ignoring hiddenLabel")
        case EvElemEnd("skos", "hiddenLabel") =>
          textBuilder.clear()

        // ===============================
        case EvElemStart(pre, label, attributes, scope) =>
          println(s"!!!START $label")
          attributes.foreach {
            attr =>
              println(s"Attribute $attr")
          }
        case EvElemEnd(pre, label) =>
          println(s"!!!END $label")

        // ===============================
        case EvText(text) =>
          val crunched = FileHandling.crunchWhitespace(text)
          if (!crunched.isEmpty) {
            textBuilder.append(crunched)
            //              println(s"Text '$crunched'")
          }

        case EvEntityRef(entity) =>
        //            println(s"Entity $entity")

        case EvComment(text) =>
        //            println(s"Comment $text")
        //            FileHandling.stupidParser(text, string => node.value(FileHandling.translateEntity(string)))

        case x =>
          println("EVENT? " + x)
      }
    }
    allConcepts.values.foreach(_.resolve(allConcepts))
    vocabulary
  }
}

trait SkosJson {

  implicit val relatedWrites = new Writes[RelatedConcept] {
    def writes(related: RelatedConcept) = Json.obj(
      "label" -> related.concept.preferred(related.language).text,
      "uri" -> related.concept.about
    )
  }

  implicit val resultWrites = new Writes[ProximityResult] {
    def writes(result: ProximityResult) = Json.obj(
      "proximity" -> result.proximity,
      "preferred" -> result.label.preferred,
      "label" -> result.label.text,
      "prefLabel" -> result.prefLabel.text,
      "uri" -> result.concept.about,
      "conceptScheme" -> result.concept.conceptScheme.get.preferred("dut").text,
      "narrower" -> result.concept.relatedNarrower(result.label.language),
      "broader" -> result.concept.relatedBroader(result.label.language)
    )
  }

  implicit val queryWrites = new Writes[LabelQuery] {
    def writes(query: LabelQuery) = Json.obj(
      "language" -> query.language,
      "sought" -> query.sought,
      "count" -> query.count
    )
  }

  implicit val searchWrites = new Writes[LabelSearch] {
    def writes(search: LabelSearch) = Json.obj(
      "query" -> search.query,
      "results" -> search.results
    )
  }

  implicit val mappingWrites = new Writes[TermMapping] {
    def writes(mapping: TermMapping) = Json.obj(
      "source" -> mapping.source,
      "target" -> mapping.target,
      "vocabulary" -> mapping.vocabulary,
      "prefLabel" -> mapping.prefLabel
    )
  }

}

case class LabelQuery(language: String, sought: String, count: Int)

case class ProximityResult(label: Label, prefLabel: Label, proximity: Double, concept: Concept)

case class LabelSearch(query: LabelQuery, results: List[ProximityResult])

case class RelatedConcept(concept: Concept, language: String)

class SkosVocabulary() {
  val conceptSchemes = mutable.MutableList[ConceptScheme]()

  def conceptScheme(uri: String): Option[ConceptScheme] = {
    conceptSchemes.find(_.about == uri)
  }

  def search(language: String, sought: String, count: Int): LabelSearch = {
    val concepts = conceptSchemes.flatMap(_.concepts)
    val judged = concepts.flatMap(_.search(language, sought.toLowerCase))
    val results = judged.sortBy(-1 * _.proximity).take(count).toList
    LabelSearch(LabelQuery(language, sought, count), results)
  }

}

class ConceptScheme(val about: String) {
  val labels = mutable.MutableList[Label]()
  val concepts = mutable.MutableList[Concept]()
  val topConcepts = mutable.MutableList[Concept]()

  def get(uri: String): Option[Concept] = concepts.find(_.about == uri)

  def preferred(language: String): Label = {
    // try language, default to "sys"
    val languageFit = labels.filter(_.preferred).find(_.language == language)
    languageFit.getOrElse(labels.filter(_.preferred).find(_.language == "sys").get)
  }

  override def toString: String =
    s"""
         |ConceptScheme($about):
         |Concepts:
         |${concepts.mkString("")}
         |TopConcepts:
         |${topConcepts.mkString("")}
       """.stripMargin
}

class Concept(val about: String) {
  val IGNORE_BRACKET = """ *[(].*[)]$""".r
  val labels = mutable.MutableList[Label]()
  val definitions = mutable.MutableList[Definition]()
  val narrowerConcepts = mutable.MutableList[Concept]()
  val broaderConcepts = mutable.MutableList[Concept]()
  val narrowerResources = mutable.MutableList[String]()
  val broaderResources = mutable.MutableList[String]()
  var conceptScheme: Option[ConceptScheme] = None

  def search(language: String, sought: String): Option[ProximityResult] = {
    val judged = languageLabels(language).map {
      label =>
        val cleanSought = IGNORE_BRACKET.replaceFirstIn(sought, "")
        val text = IGNORE_BRACKET.replaceFirstIn(label.text, "")
        (RatcliffObershelpMetric.compare(cleanSought, text), label)
    }
    val prefLabel = preferred(language)
    val searchResults = judged.filter(_._1.isDefined).map(p => ProximityResult(p._2, prefLabel, p._1.get, this))
    searchResults.sortBy(-1 * _.proximity).headOption
  }

  def assignNarrower(other: Concept) = {
    narrowerConcepts.find(_.about == other.about) match {
      case None => narrowerConcepts += other
      case _ =>
    }
    other.broaderConcepts.find(_.about == about) match {
      case None => other.broaderConcepts += this
      case _ =>
    }
  }

  def resolve(concepts: mutable.HashMap[String, Concept]) = {
    narrowerResources.foreach {
      uri =>
        concepts.get(uri) match {
          case Some(concept) => this.assignNarrower(concept)
          case None => println(s"SKOS ERROR! Cannot find concept for narrower: $uri")
        }
    }
    narrowerResources.clear()
    broaderResources.foreach {
      uri =>
        concepts.get(uri) match {
          case Some(concept) => concept.assignNarrower(this)
          case None => println(s"SKOS ERROR! Cannot find concept for broader: $uri")
        }
    }
    broaderResources.clear()
  }

  def preferred(language: String): Label = {
    // try language, default to "sys"
    val languageFit = labels.filter(_.preferred).find(_.language == language)
    languageFit.getOrElse(labels.filter(_.preferred).find(_.language == "sys").get)
  }

  def languageLabels(language: String) = {
    // try language, default to "sys"
    val languageFit = labels.filter(_.language == language)
    if (languageFit.isEmpty) {
      labels.filter(_.language == "sys")
    }
    else {
      languageFit
    }
  }

  def relatedNarrower(language: String) = narrowerConcepts.map(RelatedConcept(_, language))

  def relatedBroader(language: String) = broaderConcepts.map(RelatedConcept(_, language))

  override def toString: String =
    s"""
         |Concept($about)
         |  PrefLabels: ${labels.mkString(",")}
         | Definitions: ${definitions.mkString(",")}
         |    Narrower: ${narrowerConcepts.map(_.labels.head).mkString(",")}
         |     Broader: ${broaderConcepts.map(_.labels.head).mkString(",")}
       """.stripMargin
}

class Definition(val language: String) {
  var text: String = ""

  override def toString: String = s"""Definition[$language]("$text")"""
}

class Label(val preferred: Boolean, val language: String, var text: String = "") {

  override def toString: String = s"""${if (preferred) "Pref" else "Alt"}Label[$language]("$text")"""
}
