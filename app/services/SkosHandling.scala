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

import java.io.InputStream

import com.rockymadden.stringmetric.similarity.RatcliffObershelpMetric
import play.api.libs.json._
import services.TermDb.TermMapping

import scala.collection.mutable
import scala.xml.Node

object SkosVocabulary {

  val XML = "http://www.w3.org/XML/1998/namespace"
  val RDF = "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
  val SKOS = "http://www.w3.org/2004/02/skos/core#"


  def apply(source: InputStream): SkosVocabulary = {

    val conceptTag = "Concept"
    val conceptSchemeTag = "ConceptScheme"

    def lang(n: Node) = (n \ s"@{$XML}lang").text
    def about(n: Node) = (n \ s"@{$RDF}about").text
    def resource(n: Node) = (n \ s"@{$RDF}resource").text

    val tree = scala.xml.XML.load(source)

    val conceptSchemeList = (tree \ conceptSchemeTag).map { s =>
      ConceptScheme(
        about(s),
        (s \ "prefLabel").map(pl => Label(preferred = true, lang(pl), pl.text))
      )
    }
    val conceptSchemeMap = conceptSchemeList.map(s => s.about -> s).toMap

    val conceptList = (tree \ conceptTag).map { c =>
      val con = Concept(
        about =
          about(c),
        labels =
          (c \ "prefLabel").map(pl => Label(preferred = true, lang(pl), pl.text))
            ++ (c \ "altLabel").map(pl => Label(preferred = false, lang(pl), pl.text)),
        definitions =
          (c \ "definition").map(de => Definition(lang(de), de.text.replaceAll("\\s*\n\\s*", " ").trim)),
        narrower =
          Some((c \ "narrower").map(resource)),
        broader =
          Some((c \ "broader").map(resource))
      )
      con.conceptScheme = (c \ "inScheme").flatMap(ins => conceptSchemeMap.get(resource(ins))).headOption
      con.conceptScheme.foreach(cs => cs.concepts += con)
      val topConcept = (c \ "topConceptOf").flatMap(tco => conceptSchemeMap.get(resource(tco))).headOption
      topConcept.foreach(cs => cs.topConcepts += con)
      con
    }
    val conceptMap = conceptList.map(con => con.about -> con).toMap

    conceptList.foreach(_.resolve(conceptMap))

    SkosVocabulary(conceptSchemeList)
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

case class SkosVocabulary(conceptSchemes: Seq[ConceptScheme]) {

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

case class ConceptScheme(about: String, labels: Seq[Label]) {
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
         |$concepts
         |TopConcepts:
         |$topConcepts
       """.stripMargin
}

case class Concept
(
  about: String,
  labels: Seq[Label],
  definitions: Seq[Definition],
  var narrower: Option[Seq[String]],
  var broader: Option[Seq[String]]
  ) {
  val IGNORE_BRACKET = """ *[(].*[)]$""".r
  val narrowerConcepts = mutable.MutableList[Concept]()
  val broaderConcepts = mutable.MutableList[Concept]()
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

  def resolve(concepts: Map[String, Concept]) = {
    narrower.foreach(list => list.foreach {
      uri =>
        concepts.get(uri).map(assignNarrower).getOrElse(println(s"SKOS ERROR! Cannot find concept for narrower: $uri"))
    })
    narrower = None
    broader.foreach(list => list.foreach {
      uri =>
        concepts.get(uri).map(_.assignNarrower(this)).getOrElse(println(s"SKOS ERROR! Cannot find concept for broader: $uri"))
    })
    broader = None
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

  def relatedNarrower(language: String): mutable.MutableList[RelatedConcept] = narrowerConcepts.map(RelatedConcept(_, language))

  def relatedBroader(language: String) = broaderConcepts.map(RelatedConcept(_, language))

  override def toString: String =
    s"""
         |Concept($about)
         |      Labels: ${labels.mkString(",")}
         | Definitions: ${definitions.mkString(",")}
         |    Narrower: ${narrowerConcepts.map(_.labels.head).mkString(",")}
         |     Broader: ${broaderConcepts.map(_.labels.head).mkString(",")}
       """.stripMargin
}

case class Definition(language: String, text: String) {
  override def toString: String = s"""Definition[$language]("$text")"""
}

case class Label(preferred: Boolean, language: String, var text: String = "") {

  override def toString: String = s"""${if (preferred) "Pref" else "Alt"}Label[$language]("$text")"""
}
