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

import org.ActorStore.NXActor
import triplestore.GraphProperties._
import triplestore.{SkosGraph, TripleStore}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

object SkosMappingStore {
  
  case class SkosMapping(actor: NXActor, uriA: String, uriB: String) {

    val uri = s"${actor.uri}/mapping/${UUID.randomUUID().toString}"

    val doesMappingExist =
      s"""
         |ASK {
         |  GRAPH ?g {
         |    ?mapping
         |       a <$mappingEntity>;
         |       <$mappingConcept> <$uriA>;
         |       <$mappingConcept> <$uriB> .
         |  }
         |}
       """.stripMargin

    val deleteMapping =
      s"""
         |DELETE {
         |  GRAPH ?g {
         |    ?mapping
         |      ?p ?o;
         |      <$mappingConcept> <$uriA> ;
         |      <$mappingConcept> <$uriB> .
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

    def insertMapping(skosA: SkosGraph, skosB: SkosGraph) =
      s"""
         |PREFIX skos: <$SKOS>
         |INSERT DATA {
         |  GRAPH <$uri> {
         |    <$uriA> skos:exactMatch <$uriB> .
         |    <$uri>
         |       a <$mappingEntity>;
         |       <$synced> false;
         |       <$belongsTo> <$actor> ;
         |       <$mappingConcept> <$uriA> ;
         |       <$mappingConcept> <$uriB> ;
         |       <$mappingVocabulary> <${skosA.uri}> ;
         |       <$mappingVocabulary> <${skosB.uri}> .
         |  }
         |}
       """.stripMargin

    override def toString = uri
  }

}

class VocabMappingStore(skosA: SkosGraph, skosB: SkosGraph, ts: TripleStore) {

  import mapping.SkosMappingStore._

  def toggleMapping(mapping: SkosMapping): Future[String] = {
    ts.ask(mapping.doesMappingExist).flatMap { exists =>
      if (exists) {
        ts.update(mapping.deleteMapping).map(ok => "removed")
      }
      else {
        ts.update(mapping.insertMapping(skosA, skosB)).map(ok => "added")
      }
    }
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
         |      <$mappingVocabulary> <${skosA.uri}> ;
         |      <$mappingVocabulary> <${skosB.uri}> .
         |  }
         |}
       """.stripMargin
    ts.query(selectMappings).map(_.map(ab => (ab("a").text, ab("b").text)))
  }

}


class TermMappingStore(termGraph: SkosGraph, ts: TripleStore) {

  import mapping.SkosMappingStore._

  def toggleMapping(mapping: SkosMapping, vocabGraph: SkosGraph): Future[String] = {
    ts.ask(mapping.doesMappingExist).flatMap { exists =>
      if (exists) {
        ts.update(mapping.deleteMapping).map(ok => "removed")
      }
      else {
        ts.update(mapping.insertMapping(termGraph, vocabGraph)).map(ok => "added")
      }
    }
  }

  def getMappings: Future[Seq[(String, String)]] = {
    val selectMappings =
      s"""
         |PREFIX skos: <$SKOS>
         |SELECT ?a ?b
         |WHERE {
         |  GRAPH ?g {
         |    ?a skos:exactMatch ?b .
         |    ?s <$mappingVocabulary> <${termGraph.uri}> .
         |  }
         |}
       """.stripMargin
    ts.query(selectMappings).map(_.map(ab => (ab("a").text, ab("b").text)))
  }
}

class CategoryMappingStore(termGraph: SkosGraph, ts: TripleStore) {
  /*
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
  */
}


