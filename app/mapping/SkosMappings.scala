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

import com.hp.hpl.jena.query.{Dataset, DatasetFactory}
import mapping.SkosVocabulary.SKOS
import org.ActorStore.NXActor
import org.joda.time.DateTime
import play.api.libs.json.{Json, Writes}
import services.NarthexConfig._
import services.{StringHandling, Temporal}
import triplestore.TripleStore
import triplestore.TripleStore._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

object SkosMappings {

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


  case class SkosMapping(actor: NXActor,
                         uriA: String,
                         vocabularyA: String,
                         uriB: String,
                         vocabularyB: String,
                         when: DateTime = new DateTime) {
    val whenString = Temporal.timeToString(when)
    val uri = s"${actor.uri}/mapping/${StringHandling.urlEncodeValue(whenString)}"
  }

  case class MAProp(name: String, dataType: PropType = stringProp) {
    val uri = s"$NX_NAMESPACE$name"
  }

  val exactMatch = s"${SKOS}exactMatch"
  val mappingJoins = MAProp("mappingJoins", uriProp)

  //  val mappingUriA = MAProp("mappingUriA", uriProp)
  //  val mappingDatasetB = MAProp("mappingDatasetB", uriProp)
  //  val mappingUriB = MAProp("mappingUriB", uriProp)
  //  val mappingCreator = MAProp("mappingCreator", uriProp)
  //  val mappingTime = MAProp("mappingTime", timeProp)
  //  val mappingNotes = MAProp("mappingNotes")
}

class SkosMappings(skosA: SkosInfo, skosB: SkosInfo, ts: TripleStore) {

  import mapping.SkosMappings._

  lazy val futureDataset = {
    val dataset = DatasetFactory.createMem()
    val q =
      s"""
         |SELECT ?s ?p ?o ?g
         |WHERE {
         |  GRAPH ?g {
         |    ?s ?p ?o
         |    ?g <${mappingJoins.uri}> <${skosA.uri}> 
         |    ?g <${mappingJoins.uri}> <${skosB.uri}> 
         |  }
         |}
       """.stripMargin
    ts.query(q).map { quadList =>
      quadList.map { quad =>
        val (s, p, o, g) = (quad("s"), quad("p"), quad("o"), quad("g"))
        val m = dataset.getNamedModel(g.text)
        val v = o.qvt match {
          case QV_LITERAL => m.getResource(o.text)
          case QV_URI => m.createLiteral(o.text)
          case _ => throw new RuntimeException
        }
        m.add(m.getResource(s.text), m.getProperty(p.text), v)
      }
    }
    Future(dataset)
  }
  lazy val d: Dataset = Await.result(futureDataset, 30.seconds)

  def toggleMapping(skosMapping: SkosMapping) = {

//    val a = d.getResource(skosMapping.uriA)
//    val b = d.getResource(skosMapping.uriB)

    // todo: add same-as
    // todo:


    // todo: should this really toggle?  or have a boolean like categories
  }

  def getSkosMappings: Seq[SkosMapping] = {
    Seq.empty
  }

  //  def addTermMapping(mapping: TermMapping) = {
  //    // todo: new one overrides an existing one, tells it that it has been overridden
  //    // todo: give each one a URI which includes who and when?
  //  }
  //
  //  def removeTermMapping(sourceUri: String) = {
  //    // todo: you can only remove your own
  //  }
  //
  //  def getTermMappings: Seq[TermMapping] = {
  //    // todo: should have a dataset URI argument
  //    // todo: another method for a user's mappings
  //    Seq.empty
  //  }
  //
  //  def setCategoryMapping(mapping: CategoryMapping, member: Boolean) = {
  //    if (member) {
  //      // todo: add the mapping
  //    }
  //    else {
  //      // todo: remove the mapping
  //    }
  //  }
  //
  //  def getCategoryMappings: Seq[CategoryMapping] = {
  //    // todo: should have a dataset URI argument
  //    // todo: another method for a user's mappings (reverse order, N most recent)
  //    Seq.empty
  //  }
  //
}


