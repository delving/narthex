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

import java.util.UUID

import mapping.SkosVocabulary.SKOS
import org.ActorStore.NXActor
import org.joda.time.DateTime
import play.api.libs.json.{Json, Writes}
import services.NarthexConfig._
import services.Temporal.timeToString
import triplestore.TripleStore
import triplestore.TripleStore._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

object SkosMappingStore {

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
  }

  case class TermMapping(sourceURI: String, targetURI: String, conceptScheme: String, attributionName: String, prefLabel: String, who: String, when: DateTime) {
    val whenString = timeToString(when)
  }

  case class MAProp(name: String, dataType: PropType = stringProp) {
    val uri = s"$NX_NAMESPACE$name"
    override def toString = uri
  }

  val exactMatch = s"${SKOS}exactMatch"
  val mappingConcept = MAProp("mappingConcept", uriProp)
  val mappingVocabulary = MAProp("mappingVocabulary", uriProp)
  val mappingCreator = MAProp("mappingCreator", uriProp)
  val mappingDeleted = MAProp("mappingDeleted", booleanProp)
  val mappingDirty = MAProp("mappingDirty", booleanProp)
  val mappingTime = MAProp("mappingTime", timeProp)

  case class SkosMapping(actor: NXActor, uriA: String, uriB: String) {

    val uri = s"${actor.uri}/mapping/${UUID.randomUUID().toString}"

    val doesMappingExist =
      s"""
         |ASK {
         |  GRAPH ?g {
         |    ?mapping <$mappingConcept> <$uriA> .
         |    ?mapping <$mappingConcept> <$uriB> .
         |  }
         |}
       """.stripMargin

    val deleteMapping =
      s"""
         |DELETE {
         |  GRAPH ?g {
         |    ?mapping ?p ?o .
         |  }
         |}
         |INSERT {
         |  GRAPH ?g {
         |    ?mapping <$mappingDeleted> true .
         |  }
         |}
         |WHERE {
         |  GRAPH ?g {
         |    ?mapping 
         |      ?p ?o ;
         |      <$mappingConcept> <$uriA> ;
         |      <$mappingConcept> <$uriB> .
         |  }
         |}
       """.stripMargin

    def insertMapping(skosA: SkosInfo, skosB: SkosInfo) =
      s"""
         |PREFIX skos: <$SKOS>
         |INSERT DATA {
         |  GRAPH <$uri> {
         |    <$uriA> skos:exactMatch <$uriB> .
         |    <$uri>
         |       <$mappingCreator> <$actor> ;
         |       <$mappingDirty> true ;
         |       <$mappingConcept> <$uriA> ;
         |       <$mappingConcept> <$uriB> ;
         |       <$mappingVocabulary> <$skosA> ;
         |       <$mappingVocabulary> <$skosB> .
         |  }
         |}
       """.stripMargin

    override def toString = uri
  }

}

class SkosMappingStore(skosA: SkosInfo, skosB: SkosInfo, ts: TripleStore) {

  import mapping.SkosMappingStore._

  def toggleMapping(mapping: SkosMapping) = {
    for (
      exists <- ts.ask(mapping.doesMappingExist);
      errorOpt <- ts.update(if (exists) mapping.deleteMapping else mapping.insertMapping(skosA, skosB))
    ) yield errorOpt
  }

  def getMappings: Future[Seq[(String, String)]] = {
    val selectMappings =
      s"""
         |PREFIX skos: <$SKOS>
         |SELECT ?a ?b
         |WHERE {
         |  GRAPH ?g {
         |    ?a skos:exactMatch ?b .
         |    ?s
         |      <$mappingVocabulary> <$skosA> ;
         |      <$mappingVocabulary> <$skosB> .
         |  }
         |}
       """.stripMargin
    ts.query(selectMappings).map(_.map(ab => (ab("a").text, ab("b").text)))
  }

  /*
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

    lazy val futureDataset = {
    val dataset = DatasetFactory.createMem()
    val q =
      s"""
         |SELECT ?s ?p ?o ?g
         |WHERE {
         |  GRAPH ?g {
         |    ?s ?p ?o
         |    ?g <$mappingJoins> <$skosA>
         |    ?g <$mappingJoins> <$skosB>
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


   */
}


