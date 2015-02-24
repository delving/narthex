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

import com.hp.hpl.jena.rdf.model.{Model, Resource}
import com.rockymadden.stringmetric.similarity.RatcliffObershelpMetric
import mapping.SkosVocabulary._
import play.api.Logger
import play.api.libs.json.{JsObject, Json, Writes}
import triplestore.GraphProperties._
import triplestore.{GraphProperties, TripleStore}

import scala.collection.JavaConversions._
import scala.collection.mutable
import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

object SkosVocabulary {

  val IGNORE_BRACKET = """ *[(].*[)]$""".r

  def getLanguageLabel(labels: Seq[Label], language: String): Label = {
    // try language, default to whatever can be found
    val languageFit: Option[Label] = labels.find(_.language == language)
    languageFit.getOrElse(labels.headOption.getOrElse(Label(true, language, "Unknown!")))
  }

  def getLabels(resource: Resource, propertyName: String, preferred: Boolean, model: Model): Seq[Label] = {
    val property = model.getProperty(GraphProperties.SKOS, propertyName)
    model.listStatements(resource, property, null).map(_.getObject.asLiteral()).map(
      literal => Label(preferred = preferred, literal.getLanguage, literal.getString)
    ).toSeq
  }

  def getPrefLabels(resource: Resource, model: Model) = getLabels(resource, "prefLabel", preferred = true, model)

  def getAltLabels(resource: Resource, model: Model) = getLabels(resource, "altLabel", preferred = false, model)

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
//        "conceptScheme" -> result.concept.vocabulary.name,
        "attributionName" -> result.concept.vocabulary.spec,
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

  case class Concept(vocabulary: SkosVocabulary, resource: Resource, conceptMap: mutable.HashMap[String, Concept], model: Model) {
    def getRelated(resource: Resource, propertyName: String, model: Model): Seq[Resource] = {
      val property = model.getProperty(GraphProperties.SKOS, propertyName)
      model.listStatements(resource, property, null).map(_.getObject.asResource()).toSeq
    }

    conceptMap.put(resource.getURI, this)
    lazy val prefLabels = getPrefLabels(resource, model)
    lazy val altLabels = getAltLabels(resource, model)
    lazy val labels = prefLabels ++ altLabels
    lazy val narrower: Seq[Concept] = getRelated(resource, "narrower", model).flatMap(resource => conceptMap.get(resource.getURI))
    lazy val broader: Seq[Concept] = getRelated(resource, "broader", model).flatMap(resource => conceptMap.get(resource.getURI))
    lazy val frequency: Option[Int] = {
      val frequencyValue = model.listObjectsOfProperty(resource, model.getProperty(skosFrequency)).toList.headOption
      frequencyValue.map(_.asLiteral().getInt)
    }

    def getPrefLabel(language: String) = getLanguageLabel(prefLabels, language)

    def getAltLabel(language: String) = getLanguageLabel(altLabels, language)

    def search(language: String, sought: String): Option[ProximityResult] = {
      val judged = labels.filter(_.language == language).map { label =>
        val text = IGNORE_BRACKET.replaceFirstIn(label.text.toLowerCase, "")
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

case class SkosVocabulary(spec: String, graphName: String, ts: TripleStore) {

  lazy val futureModel = ts.dataGet(graphName)
  futureModel.onFailure {
    case e: Throwable => Logger.warn(s"No data found for skos vocabulary $graphName", e)
  }
  futureModel.onSuccess {
    case x => Logger.info(s"Loaded $graphName")
  }
  lazy val m: Model = Await.result(futureModel, 60.seconds)

  private val conceptMap = new mutable.HashMap[String, Concept]()

  lazy val concepts: Seq[Concept] = {
    val typeProperty = m.getProperty(rdfType)
    val conceptResource = m.getResource(s"${SKOS}Concept")
    val subjects = m.listSubjectsWithProperty(typeProperty, conceptResource).toSeq
    subjects.map(statement => Concept(this, statement, conceptMap, m))
  }

  def search(language: String, sought: String, count: Int): LabelSearch = {
    val cleanSought = IGNORE_BRACKET.replaceFirstIn(sought, "")
    val judged = concepts.flatMap(_.search(language, sought.toLowerCase))
    val results = judged.sortBy(-1 * _.proximity).take(count).toList
    LabelSearch(LabelQuery(language, cleanSought, count), results)
  }

  override def toString: String = graphName
}
