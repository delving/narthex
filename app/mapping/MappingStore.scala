//===========================================================================
//    Copyright 2015 Delving B.V.
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

import org.joda.time.DateTime
import play.api.libs.json.{Json, Writes}
import services.NarthexConfig._
import services.Temporal
import triplestore.TripleStoreClient._

object MappingStore {

  trait MappingStoreJson {
    implicit val termMappingWrites = new Writes[TermMapping] {
      def writes(mapping: TermMapping) = Json.obj(
        "sourceURI" -> mapping.sourceURI,
        "targetURI" -> mapping.targetURI,
        "conceptScheme" -> mapping.conceptScheme,
        "attributionName" -> mapping.attributionName,
        "prefLabel" -> mapping.prefLabel,
        "who" -> mapping.who,
        "when" -> mapping.whenString
      )
    }

    implicit val categoryMappingWrites = new Writes[CategoryMapping] {
      def writes(mapping: CategoryMapping) = Json.obj(
        "source" -> mapping.source,
        "categories" -> mapping.categories
      )
    }

    implicit val thesaurusMappingWrites = new Writes[ThesaurusMapping] {
      def writes(mapping: ThesaurusMapping) = Json.obj(
        "uriA" -> mapping.uriA,
        "uriB" -> mapping.uriB,
        "who" -> mapping.who,
        "when" -> mapping.whenString
      )
    }
  }

  case class TermMapping(sourceURI: String, targetURI: String, conceptScheme: String, attributionName: String, prefLabel: String, who: String, when: DateTime) {
    val whenString = Temporal.timeToString(when)
  }

  case class CategoryMapping(source: String, categories: Seq[String])

  case class ThesaurusMapping(uriA: String, uriB: String, who: String, when: DateTime) {
    val whenString = Temporal.timeToString(when)
  }

  case class MAProp(name: String, dataType: PropType = stringProp) {
    val uri = s"${NX_NAMESPACE}Mapping-Attributes#$name"
  }

  val MAPPING_TYPE_SOURCE_SKOS = "source-skos"
  val MAPPING_TYPE_SOURCE_CATEGORY = "source-category"
  val MAPPING_TYPE_SKOS_SKOS = "skos-skos"

  val mappingType = MAProp("mappingType")
  val mappingTermA = MAProp("mappingA", uriProp)
  val mappingTermB = MAProp("mappingB", uriProp)
  val mappingCreator = MAProp("mappingCreator", uriProp)
  val mappingTime = MAProp("mappingTime", timeProp)
  val mappingNotes = MAProp("mappingNotes")
}

class MappingStore() {

  import mapping.MappingStore._

  def addTermMapping(mapping: TermMapping) = {
  }

  def removeTermMapping(sourceUri: String) = {
  }

  def getTermMappings: Seq[TermMapping] = {
    Seq.empty
  }

  def setCategoryMapping(mapping: CategoryMapping) = {
  }

  def getCategoryMappings: Seq[CategoryMapping] = {
    Seq.empty
  }

  def toggleThesaurusMapping(mapping: ThesaurusMapping) = {
  }

  def getThesaurusMappings: Seq[ThesaurusMapping] = {
    Seq.empty
  }
}
