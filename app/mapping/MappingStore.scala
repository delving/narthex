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

import java.io.StringWriter

import com.hp.hpl.jena.rdf.model.Model
import dataset.DatasetInfo
import org.UserStore.NXActor
import org.apache.jena.riot.{RDFDataMgr, RDFFormat}
import org.joda.time.DateTime
import play.api.libs.json.{Json, Writes}
import services.NarthexConfig._
import services.Temporal
import triplestore.TripleStore
import triplestore.TripleStore._

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
    val uri = s"$NX_NAMESPACE$name"
  }

  val mappingDatasetA = MAProp("mappingDatasetA", uriProp)
  val mappingUriA = MAProp("mappingUriA", uriProp)
  val mappingDatasetB = MAProp("mappingDatasetB", uriProp)
  val mappingUriB = MAProp("mappingUriB", uriProp)
  val mappingCreator = MAProp("mappingCreator", uriProp)
  val mappingTime = MAProp("mappingTime", timeProp)
  val mappingNotes = MAProp("mappingNotes")
}

class MappingStoreChoose(client: TripleStore) {
  // todo: get a whole list of the concept schemes and the accompanying datasets
}

class MappingStoreEdit(client: TripleStore, user: NXActor,
                       datasetA: DatasetInfo, datasetB: DatasetInfo) {

  import mapping.MappingStore._

  def sparqlUpdateGraph(graphUri: String, model: Model) = {
    val triples = new StringWriter()
    RDFDataMgr.write(triples, model, RDFFormat.NTRIPLES_UTF8)
    s"""
      |DROP SILENT GRAPH <$graphUri>;
      |INSERT DATA { GRAPH <$graphUri> {
      |$triples}};
     """.stripMargin.trim
  }

  def addTermMapping(mapping: TermMapping) = {
    // todo: new one overrides an existing one, tells it that it has been overridden
    // todo: give each one a URI which includes who and when?
  }

  def removeTermMapping(sourceUri: String) = {
    // todo: you can only remove your own
  }

  def getTermMappings: Seq[TermMapping] = {
    // todo: should have a dataset URI argument
    // todo: another method for a user's mappings
    Seq.empty
  }

  def setCategoryMapping(mapping: CategoryMapping, member: Boolean) = {
    if (member) {
      // todo: add the mapping
    }
    else {
      // todo: remove the mapping
    }
  }

  def getCategoryMappings: Seq[CategoryMapping] = {
    // todo: should have a dataset URI argument
    // todo: another method for a user's mappings (reverse order, N most recent)
    Seq.empty
  }

  def toggleThesaurusMapping(mapping: ThesaurusMapping) = {
    // todo: should this really toggle?  or have a boolean like categories
  }

  def getThesaurusMappings: Seq[ThesaurusMapping] = {
    Seq.empty
  }
}

class MappingStoreApply(client: TripleStore,
                        dataset: DatasetInfo) {

  // todo: get all of the mappings that relate to this dataset
  // todo: build a big map with uris from this dataset as keys
}

