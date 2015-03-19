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

package triplestore

import dataset.DsInfo
import dataset.DsInfo._
import mapping.VocabInfo.CATEGORIES_SPEC
import org.ActorStore.{NXActor, NXProfile}
import org.joda.time.DateTime
import play.api.libs.json.JsValue
import services.StringHandling._
import services.Temporal
import triplestore.GraphProperties._
import triplestore.TripleStore.QueryValue

object Sparql {
  // === actor store ===
  private val SPARQL_ESCAPE: Map[Char, String] = Map(
    '\t' -> "\\t",
    '\n' -> "\\n",
    '\r' -> "\\r",
    '\b' -> "\\b",
    '\f' -> "\\f",
    '"' -> "\\\"",
    '\'' -> "\\'",
    '\\' -> "\\\\"
  )

  private def escape(value: String): String = value.map(c => SPARQL_ESCAPE.getOrElse(c, c.toString)).mkString

  private def literalExpression(value: String, languageOpt: Option[String]) = languageOpt.map { language =>
    s"'${escape(value)}'@$language"
  } getOrElse {
    s"'${escape(value)}'"
  }

  def graphExistsQ(graphName: String) =
    s"""
      |ASK {
      |   GRAPH <$graphName> { ?s ?p ?o }
      |}
     """.stripMargin


  def insertActorQ(actor: NXActor, passwordHashString: String, adminActor: NXActor) =
    s"""
      |WITH <$actorsGraph>
      |DELETE {
      |   <$actor>
      |      a <$actorEntity>;
      |      <$username> ?userName ;
      |      <$actorOwner> ?userMaker ;
      |      <$passwordHash> ?passwordHash .
      |}
      |INSERT {
      |   <$actor>
      |      a <$actorEntity>;
      |      <$username> ${literalExpression(actor.actorName, None)} ;
      |      <$actorOwner> <$adminActor> ;
      |      <$passwordHash> '$passwordHashString' .
      |}
      |WHERE {
      |   OPTIONAL {
      |      <$actor>
      |         a <$actorEntity>;
      |         <$username> ?username ;
      |         <$actorOwner> ?userMaker ;
      |         <$passwordHash> ?passwordHash .
      |   }
      |}
     """.stripMargin

  def setActorProfileQ(actor: NXActor, profile: NXProfile) =
    s"""
      |WITH <$actorsGraph>
      |DELETE {
      |   <$actor>
      |      <$userFirstName> ?firstName ;
      |      <$userLastName> ?lastName ;
      |      <$userEMail> ?email .
      |}
      |INSERT {
      |   <$actor>
      |      <$userFirstName> ${literalExpression(profile.firstName, None)} ;
      |      <$userLastName> ${literalExpression(profile.lastName, None)} ;
      |      <$userEMail> ${literalExpression(profile.email, None)} .
      |}
      |WHERE {
      |   <$actor>
      |      a <$actorEntity> ;
      |      <$userFirstName> ?firstName ;
      |      <$userLastName> ?lastName ;
      |      <$userEMail> ?email .
      |}
     """.stripMargin

  def setActorPasswordQ(actor: NXActor, passwordHashString: String) =
    s"""
      |WITH <$actorsGraph>
      |DELETE { <$actor> <$passwordHash> ?oldPassword }
      |INSERT { <$actor> <$passwordHash> '$passwordHashString' }
      |WHERE { <$actor> <$passwordHash> ?oldPassword }
     """.stripMargin

  // === dataset info ===

  val selectDatasetSpecsQ =
    s"""
      |SELECT ?spec
      |WHERE {
      |  GRAPH ?g {
      |    ?s <$datasetSpec> ?spec .
      |    FILTER NOT EXISTS { ?s <$deleted> true }
      |  }
      |}
      |ORDER BY ?spec
     """.stripMargin

  def askIfDatasetExistsQ(uri: String) =
    s"""
      |ASK {
      |   GRAPH <$uri> { <$uri> <$datasetSpec> ?spec }
      |}
     """.stripMargin

  def updatePropertyQ(uri: String, prop: NXProp, value: String): String =
    s"""
      |WITH <$uri>
      |DELETE { <$uri> <$prop> ?o }
      |INSERT { <$uri> <$prop> ${literalExpression(value, None)} }
      |WHERE {
      |   OPTIONAL { <$uri> <$prop> ?o }
      |}
     """.stripMargin.trim

  def updateSyncedFalseQ(uri: String): String =
    s"""
      |WITH <$uri>
      |DELETE { <$uri> <$synced> ?o }
      |INSERT { <$uri> <$synced> false }
      |WHERE {
      |   OPTIONAL { <$uri> <$synced> ?o }
      |}
     """.stripMargin.trim

  def removeLiteralPropertyQ(uri: String, prop: NXProp) =
    s"""
      |WITH <$uri>
      |DELETE { <$uri> <$prop> ?o }
      |WHERE { <$uri> <$prop> ?o }
     """.stripMargin

  def addUriPropertyQ(uri: String, prop: NXProp, uriValue: String) =
    s"""
      |INSERT DATA {
      |  GRAPH <$uri> { <$uri> <$prop> ${literalExpression(uriValue, None)} . }
      |}
     """.stripMargin.trim

  def deleteUriPropertyQ(uri: String, prop: NXProp, uriValue: String) =
    s"""
      |WITH <$uri>
      |DELETE {
      |  <$uri> <$prop> ${literalExpression(uriValue, None)} .
      |}
      |WHERE {
      |  <$uri> <$prop> ${literalExpression(uriValue, None)} .
      |}
     """.stripMargin

  def deleteDatasetQ(uri: String, skosUri: String) =
    s"""
      |INSERT DATA {
      |   GRAPH <$uri> {
      |     <$uri> <$deleted> true .
      |   }
      |};
      |DROP SILENT GRAPH <$skosUri>;
      |DELETE {
      |   GRAPH ?g {
      |      ?s ?p ?o .
      |   }
      |}
      |WHERE {
      |   GRAPH ?g {
      |      ?record a <$recordEntity> .
      |      ?record <$belongsTo> <$uri> .
      |   }
      |};
     """.stripMargin

  // === vocab info ===

  val listVocabInfoQ =
    s"""
      |SELECT ?spec
      |WHERE {
      |  GRAPH ?g {
      |    ?s <$skosSpec> ?spec .
      |  }
      |}
      |ORDER BY ?spec
     """.stripMargin

  def checkVocabQ(skosUri: String) =
    s"""
      |ASK {
      |   GRAPH ?g {
      |     <$skosUri> <$skosSpec> ?spec .
      |   }
      |}
     """.stripMargin

  def getVocabStatisticsQ(uri: String) =
    s"""
      |PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
      |PREFIX skos: <http://www.w3.org/2004/02/skos/core#>
      |SELECT (count(?s) as ?count)
      |WHERE {
      |  GRAPH <$uri> { ?s rdf:type skos:Concept }
      |}
     """.stripMargin

  def dropVocabularyQ(uri: String) =
    s"""
      |WITH <$uri>
      |DELETE { ?s ?p ?o }
      |WHERE { ?s ?p ?o }
     """.stripMargin

  // === mapping store ===

  def doesMappingExistQ(uriA: String, uriB: String) =
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

  def deleteMappingQ(uriA: String, uriB: String) =
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
      |    ?mapping <$mappingDeleted> true
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

  def insertMappingQ(actor: NXActor, uri: String, uriA: String, uriB: String, skosA: SkosGraph, skosB: SkosGraph) = {
    val connection = if (skosB.spec == CATEGORIES_SPEC) belongsToCategory.uri else exactMatch
    s"""
      |INSERT DATA {
      |  GRAPH <$uri> {
      |    <$uriA> <$connection> <$uriB> .
      |    <$uri>
      |       a <$mappingEntity>;
      |       <$synced> false;
      |       <$belongsTo> <$actor> ;
      |       <$mappingTime> '''${Temporal.timeToString(new DateTime)}''' ;
      |       <$mappingConcept> <$uriA> ;
      |       <$mappingConcept> <$uriB> ;
      |       <$mappingVocabulary> <${skosA.uri}> ;
      |       <$mappingVocabulary> <${skosB.uri}> .
      |  }
      |}
     """.stripMargin
  }

  def getVocabMappingsQ(skosA: SkosGraph, skosB: SkosGraph) = {
    s"""
      |SELECT ?a ?b
      |WHERE {
      |  GRAPH ?g {
      |    ?a <$exactMatch> ?b .
      |    ?s <$mappingVocabulary> <${skosA.uri}> .
      |    ?s <$mappingVocabulary> <${skosB.uri}> .
      |  }
      |}
     """.stripMargin
  }

  def getTermMappingsQ(terms: SkosGraph, categories: Boolean) = {
    val connection = if (categories) belongsToCategory.uri else exactMatch
    s"""
      |SELECT ?termUri ?vocabUri ?vocabSpec
      |WHERE {
      |  GRAPH ?vocabGraph {
      |    ?vocab <$skosSpec> ?vocabSpec
      |  }
      |  GRAPH ?mappingGraph {
      |    ?termUri <$connection> ?vocabUri .
      |    ?s <$mappingVocabulary> <${terms.uri}> .
      |    ?s <$mappingVocabulary> ?vocab .
      |    FILTER (?vocab != <${terms.uri}>)
      |  }
      |}
     """.stripMargin
  }

  // === skosification ===

  val listSkosifiedFieldsQ =
    s"""
      |SELECT ?spec ?datasetUri ?fieldPropertyUri
      |WHERE {
      |  GRAPH ?g {
      |    ?datasetUri
      |      a <$datasetEntity> ;
      |      <$datasetSpec> ?spec ;
      |      <$skosField> ?fieldPropertyUri .
      |  }
      |}
     """.stripMargin

  case class SkosifiedField(spec: String, datasetUri: String, fieldPropertyUri: String)

  def skosifiedFieldFromResult(resultMap: Map[String, QueryValue]) = SkosifiedField(
    resultMap("spec").text,
    resultMap("datasetUri").text,
    resultMap("fieldPropertyUri").text
  )

  def skosificationCasesExistQ(sf: SkosifiedField) =
    s"""
      |ASK {
      |  GRAPH ?g {
      |    ?record a <$recordEntity> .
      |    ?record <$belongsTo> <${sf.datasetUri}> .
      |    ?anything <${sf.fieldPropertyUri}> ?literalValue .
      |    FILTER isLiteral(?literalValue)
      |  }
      |}
     """.stripMargin

  def countSkosificationCasesQ(sf: SkosifiedField) =
    s"""
      |SELECT (COUNT(DISTINCT ?literalValue) as ?count)
      |WHERE {
      |  GRAPH ?g {
      |    ?record a <$recordEntity> .
      |    ?record <$belongsTo> <${sf.datasetUri}> .
      |    ?anything <${sf.fieldPropertyUri}> ?literalValue .
      |    FILTER isLiteral(?literalValue)
      |  }
      |}
     """.stripMargin

  def countFromResult(resultMap: List[Map[String, QueryValue]]): Int = resultMap.head.get("count").get.text.toInt

  def listSkosificationCasesQ(sf: SkosifiedField, chunkSize: Int) =
    s"""
      |SELECT DISTINCT ?literalValue
      |WHERE {
      |  GRAPH ?g {
      |    ?record a <$recordEntity> .
      |    ?record <$belongsTo> <${sf.datasetUri}> .
      |    ?anything <${sf.fieldPropertyUri}> ?literalValue .
      |    FILTER isLiteral(?literalValue)
      |  }
      |}
      |LIMIT $chunkSize
     """.stripMargin

  def createCasesFromQueryValues(sf: SkosifiedField, resultList: List[Map[String, QueryValue]]): List[SkosificationCase] =
    resultList.map(_("literalValue")).map(v => SkosificationCase(sf, v.text, v.language))

  def createCasesFromHistogram(dsInfo: DsInfo, json: JsValue): List[SkosificationCase] = {
    val fieldPropertyUri = (json \ "uri").as[String]
    val histogram = (json \ "histogram").as[List[List[String]]]
    val sf = SkosifiedField(dsInfo.spec, dsInfo.uri, fieldPropertyUri)
    // todo: no language
    histogram.map(count => SkosificationCase(sf, count(1), None, Some(count(0).toInt)))
  }

  case class SkosificationCase(sf: SkosifiedField, literalValueText: String, languageOpt: Option[String], frequencyOpt: Option[Int] = None) {
    val fieldProperty = sf.fieldPropertyUri
    val ExtractLocalName(localName) = fieldProperty
    val mintedUri = s"${sf.datasetUri}/$localName/${slugify(literalValueText)}"
    val skosGraph = getDsSkosUri(sf.datasetUri)
    val datasetUri = sf.datasetUri
    val value = literalValueText

    val ensureSkosEntryQ =
      s"""
        |PREFIX skos: <http://www.w3.org/2004/02/skos/core#>
        |INSERT {
        |   GRAPH <$skosGraph> {
        |      <$mintedUri> a skos:Concept .
        |      <$mintedUri> skos:altLabel ${literalExpression(value, languageOpt)} .
        |      <$mintedUri> <$belongsTo> <$datasetUri> .
        |      <$mintedUri> <$skosField> <$fieldProperty> .
        |      <$mintedUri> <$synced> false .
        |      ${frequencyOpt.map(freq => s"<$mintedUri> <$skosFrequency> '''$freq''' .").getOrElse("")}
        |   }
        |}
        |WHERE {
        |   FILTER NOT EXISTS {
        |      GRAPH <$skosGraph> {
        |         ?existing a skos:Concept
        |         FILTER( ?existing = <$mintedUri> )
        |      }
        |   }
        |};
       """.stripMargin

    val literalToUriQ =
      s"""
        |DELETE {
        |  GRAPH ?g {
        |    ?s <$fieldProperty> ${literalExpression(value, languageOpt)} .
        |  }
        |}
        |INSERT {
        |  GRAPH ?g {
        |    ?s <$fieldProperty> <$mintedUri> .
        |  }
        |}
        |WHERE {
        |  GRAPH ?g {
        |    ?record a <$recordEntity> .
        |    ?record <$belongsTo> <$datasetUri> .
        |    ?s <$fieldProperty> ${literalExpression(value, languageOpt)} .
        |  }
        |};
       """.stripMargin
  }

}
