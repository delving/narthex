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

package mapping

import java.io.InputStream

import com.hp.hpl.jena.rdf.model.{Model, ModelFactory, Resource}
import com.rockymadden.stringmetric.similarity.RatcliffObershelpMetric
import mapping.ConceptScheme._
import play.api.libs.json.{JsObject, Json, Writes}

import scala.collection.JavaConversions._
import scala.collection.mutable

object ConceptScheme {

  val XML = "http://www.w3.org/XML/1998/namespace"
  val RDF = "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
  val DC = "http://purl.org/dc/elements/1.1/"
  val SKOS = "http://www.w3.org/2004/02/skos/core#"
  val IGNORE_BRACKET = """ *[(].*[)]$""".r

  def getLanguageLabel(labels: Seq[Label], language: String): Label = {
    // try language, default to whatever can be found
    val languageFit = labels.find(_.language == language)
    languageFit.getOrElse(labels.head)
  }

  def getLabels(resource: Resource, propertyName: String, preferred: Boolean, model: Model): Seq[Label] = {
    val property = model.getProperty(SKOS, propertyName)
    model.listStatements(resource, property, null).map(_.getObject.asLiteral()).map(
      literal => Label(preferred = preferred, literal.getLanguage, literal.getString)
    ).toSeq
  }

  def getPrefLabels(resource: Resource, model: Model) = getLabels(resource, "prefLabel", preferred = true, model)

  def getAltLabels(resource: Resource, model: Model) = getLabels(resource, "altLabel", preferred = false, model)

  def getRelated(resource: Resource, propertyName: String, model: Model): Seq[Resource] = {
    val property = model.getProperty(SKOS, propertyName)
    model.listStatements(resource, property, null).map(_.getObject.asResource()).toSeq
  }

  def read(inputStream: InputStream): Seq[ConceptScheme] = {
    val model = ModelFactory.createDefaultModel()
    model.read(inputStream, null)
    val inScheme = model.getProperty(SKOS, "inScheme")
    val statements = model.listStatements(null, inScheme, null).toList
    val schemeResources = statements.map(_.getObject.asResource()).distinct
    schemeResources.map(ConceptScheme(_, model))
  }

  implicit val writesLabelSearch = new Writes[LabelSearch] {

    def writeQuery(query: LabelQuery) = Json.obj(
      "language" -> query.language,
      "sought" -> query.sought,
      "count" -> query.count
    )

    def writeProximityResult(result: ProximityResult, language: String): JsObject = {
      val narrowerLabels: Seq[Label] = result.concept.narrower.map(_.getPrefLabel(result.label.language))
      val broaderLabels: Seq[Label] = result.concept.broader.map(_.getPrefLabel(result.label.language))
      Json.obj(
        "proximity" -> result.proximity,
        "preferred" -> result.label.preferred,
        "label" -> result.label.text,
        "prefLabel" -> result.prefLabel.text,
        "uri" -> result.concept.resource.toString,
        "conceptScheme" -> result.concept.scheme.name,
        "narrower" -> narrowerLabels.map(_.text),
        "broader" -> broaderLabels.map(_.text)
      )
    }

    def writes(search: LabelSearch) = Json.obj(
      "query" -> writeQuery(search.query),
      "results" -> search.results.map(writeProximityResult(_, search.query.language))
    )
  }

  case class LabelSearch(query: LabelQuery, results: List[ProximityResult])

  case class LabelQuery(language: String, sought: String, count: Int)

  case class Label(preferred: Boolean, language: String, var text: String = "") {
    override def toString: String = s"""${if (preferred) "Pref" else "Alt"}Label[$language]("$text")"""
  }

  case class ProximityResult(label: Label, prefLabel: Label, proximity: Double, concept: Concept)

  case class Concept(scheme: ConceptScheme, resource: Resource, conceptMap: mutable.HashMap[String, Concept], model: Model) {
    conceptMap.put(resource.getURI, this)
    lazy val prefLabels = getPrefLabels(resource, model)
    lazy val altLabels = getAltLabels(resource, model)
    lazy val labels = prefLabels ++ altLabels
    lazy val narrower: Seq[Concept] = getRelated(resource, "narrower", model).flatMap(resource => conceptMap.get(resource.getURI))
    lazy val broader: Seq[Concept] = getRelated(resource, "broader", model).flatMap(resource => conceptMap.get(resource.getURI))
    
    def getPrefLabel(language: String) = getLanguageLabel(prefLabels, language)
    
    def search(language: String, sought: String): Option[ProximityResult] = {
      val judged = labels.filter(_.language == language).map { label =>
        val text = IGNORE_BRACKET.replaceFirstIn(label.text, "")
        (RatcliffObershelpMetric.compare(sought, text), label)
      }
      val prefLabel = getPrefLabel(language)
      val searchResults = judged.filter(_._1.isDefined).map(p => ProximityResult(p._2, prefLabel, p._1.get, this))
      searchResults.sortBy(-1 * _.proximity).headOption
    }

    override def toString: String = s"""
         |Concept($resource)
         |      Labels: ${labels.mkString(",")}
         |    Narrower: ${narrower.map(_.prefLabels.head).mkString(",")}
         |     Broader: ${broader.map(_.prefLabels.head).mkString(",")}
       """.stripMargin.trim
  }

}

case class ConceptScheme(resource: Resource, model: Model) {

  val conceptMap = new mutable.HashMap[String, Concept]()

  lazy val name: String = {
    val prefLabels = getPrefLabels(resource, model)
    prefLabels.headOption.map(_.text).getOrElse{
      val property = model.getProperty(DC, "title")
      val titles = model.listStatements(resource, property, null).map(_.getObject.asLiteral()).map(
        literal => literal.getString
      ).toSeq
      titles.headOption.getOrElse(throw new RuntimeException(s"Unable to find name for concept scheme $resource"))
    }
  }

  lazy val concepts: Seq[Concept] = {
    val inScheme = model.getProperty(SKOS, "inScheme")
    val conceptResources = model.listStatements(null, inScheme, resource).map(_.getSubject).toSeq
    conceptResources.map(resource => Concept(this, resource, conceptMap, model))
  }

  def search(language: String, sought: String, count: Int): LabelSearch = {
    val cleanSought = IGNORE_BRACKET.replaceFirstIn(sought, "")
    val judged = concepts.flatMap(_.search(language, sought.toLowerCase))
    val results = judged.sortBy(-1 * _.proximity).take(count).toList
    LabelSearch(LabelQuery(language, cleanSought, count), results)
  }

  override def toString: String = s"ConceptScheme($resource): $name (${concepts.size})"
}
