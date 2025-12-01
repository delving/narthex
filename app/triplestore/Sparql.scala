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

  // todo: the following is a way to make the string interpolation type-aware
  implicit class QueryStringContext(stringContext: StringContext) {
    def Q(args: Any*) = stringContext.s(args.map {
      case nxProp: NXProp => "<" + nxProp.uri + ">"
      case string: String => "'" + escape(string) + "'"
        // todo: other cases
    })
  }
  val x = "4"
  val y = Q"gumby $x"
  // todo: ---------------------------------------

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

  // === dataset info ===

  val selectDatasetSpecsQ =
    s"""
      |SELECT DISTINCT ?spec
      |WHERE {
      |  GRAPH ?g {
      |    ?s <$datasetSpec> ?spec .
      |    FILTER NOT EXISTS { ?s <$deleted> true }
      |  }
      |}
      |ORDER BY ?spec
     """.stripMargin

  /**
   * Lightweight query for dataset list - only selects fields needed for collapsed view.
   * This avoids fetching the full RDF model for each dataset, significantly improving performance.
   */
  val selectDatasetsLightQ =
    s"""
      |PREFIX nx: <${NX_NAMESPACE}>
      |SELECT DISTINCT ?spec ?name ?processedValid ?processedInvalid ?recordCount
      |                ?stateDisabled ?stateRaw ?stateRawAnalyzed ?stateSourced
      |                ?stateMappable ?stateProcessable ?stateAnalyzed ?stateProcessed
      |                ?stateSaved ?stateIncrementalSaved
      |                ?currentOperation ?operationStatus
      |                ?errorMessage ?errorTime
      |                ?harvestType ?harvestDownloadURL
      |                ?harvestIncrementalMode ?processedIncrementalValid ?processedIncrementalInvalid
      |WHERE {
      |  GRAPH ?g {
      |    ?s nx:datasetSpec ?spec .
      |    OPTIONAL { ?s nx:datasetName ?name }
      |    OPTIONAL { ?s nx:processedValid ?processedValid }
      |    OPTIONAL { ?s nx:processedInvalid ?processedInvalid }
      |    OPTIONAL { ?s nx:datasetRecordCount ?recordCount }
      |    OPTIONAL { ?s nx:stateDisabled ?stateDisabled }
      |    OPTIONAL { ?s nx:stateRaw ?stateRaw }
      |    OPTIONAL { ?s nx:stateRawAnalyzed ?stateRawAnalyzed }
      |    OPTIONAL { ?s nx:stateSourced ?stateSourced }
      |    OPTIONAL { ?s nx:stateMappable ?stateMappable }
      |    OPTIONAL { ?s nx:stateProcessable ?stateProcessable }
      |    OPTIONAL { ?s nx:stateAnalyzed ?stateAnalyzed }
      |    OPTIONAL { ?s nx:stateProcessed ?stateProcessed }
      |    OPTIONAL { ?s nx:stateSaved ?stateSaved }
      |    OPTIONAL { ?s nx:stateIncrementalSaved ?stateIncrementalSaved }
      |    OPTIONAL { ?s nx:datasetCurrentOperation ?currentOperation }
      |    OPTIONAL { ?s nx:datasetOperationStatus ?operationStatus }
      |    OPTIONAL { ?s nx:datasetErrorMessage ?errorMessage }
      |    OPTIONAL { ?s nx:datasetErrorTime ?errorTime }
      |    OPTIONAL { ?s nx:harvestType ?harvestType }
      |    OPTIONAL { ?s nx:harvestDownloadURL ?harvestDownloadURL }
      |    OPTIONAL { ?s nx:harvestIncrementalMode ?harvestIncrementalMode }
      |    OPTIONAL { ?s nx:processedIncrementalValid ?processedIncrementalValid }
      |    OPTIONAL { ?s nx:processedIncrementalInvalid ?processedIncrementalInvalid }
      |    FILTER NOT EXISTS { ?s <$deleted> true }
      |  }
      |}
      |ORDER BY ?spec
     """.stripMargin

  val selectDatasetsInRetryQ =
    s"""
      |PREFIX nx: <${NX_NAMESPACE}>
      |SELECT DISTINCT ?spec ?retryCount ?lastRetryTime
      |WHERE {
      |  GRAPH ?g {
      |    ?s nx:datasetSpec ?spec .
      |    ?s nx:harvestInRetry true .
      |    OPTIONAL { ?s nx:harvestRetryCount ?retryCount }
      |    OPTIONAL { ?s nx:harvestLastRetryTime ?lastRetryTime }
      |    FILTER NOT EXISTS { ?s <$deleted> true }
      |  }
      |}
      |ORDER BY ?lastRetryTime
     """.stripMargin

  val selectDatasetsWithIncompleteOperationsQ =
    s"""
      |PREFIX nx: <${NX_NAMESPACE}>
      |SELECT DISTINCT ?spec ?operation ?startTime ?trigger ?status
      |WHERE {
      |  GRAPH ?g {
      |    ?s nx:datasetSpec ?spec .
      |    ?s nx:datasetCurrentOperation ?operation .
      |    ?s nx:datasetOperationStatus ?status .
      |    OPTIONAL { ?s nx:datasetOperationStartTime ?startTime }
      |    OPTIONAL { ?s nx:datasetOperationTrigger ?trigger }
      |    FILTER NOT EXISTS { ?s <$deleted> true }
      |    FILTER (?status = "in_progress")
      |  }
      |}
      |ORDER BY ?startTime
     """.stripMargin

  def selectDatasetSpecsWithStateFilterQ(allowedStates: List[String]) = {
    val stateFilter = if (allowedStates.nonEmpty) {
      // Check for presence of specific state properties (e.g., stateSaved, stateIncrementalSaved)  
      val stateChecks = allowedStates.map { state =>
        val stateProperty = state match {
          // Handle actual enum toString values (e.g., "stateSaved")
          case "stateSaved" => s"<$stateSaved>"
          case "stateIncrementalSaved" => s"<$stateIncrementalSaved>"
          case "stateProcessed" => s"<$stateProcessed>"
          case "stateRaw" => s"<$stateRaw>"
          case "stateAnalyzed" => s"<$stateAnalyzed>"
          // Handle enum name values (e.g., "SAVED") as fallback
          case "SAVED" => s"<$stateSaved>"
          case "INCREMENTAL_SAVED" => s"<$stateIncrementalSaved>"
          case "PROCESSED" => s"<$stateProcessed>"
          case "RAW" => s"<$stateRaw>"
          case "ANALYZED" => s"<$stateAnalyzed>"
          case _ => s"<${NX_NAMESPACE}state$state>" // fallback
        }
        s"EXISTS { ?s $stateProperty ?time }"
      }.mkString(" || ")
      
      s"""
         |    FILTER ($stateChecks)
      """.stripMargin
    } else ""
    
    s"""
      |SELECT DISTINCT ?spec
      |WHERE {
      |  GRAPH ?g {
      |    ?s <$datasetSpec> ?spec .
      |    FILTER NOT EXISTS { ?s <$deleted> true }
      |    $stateFilter
      |  }
      |}
      |ORDER BY ?spec
     """.stripMargin
  }

  def askIfDatasetExistsQ(uri: String) =
    s"""
      |ASK {
      |   GRAPH ?g { <$uri> <$datasetSpec> ?spec }
      |}
     """.stripMargin

  def updatePropertyQ(graphName: String, uri: String, prop: NXProp, value: String): String =
    s"""
      |WITH <$graphName>
      |DELETE {
      |   <$uri> <$prop> ?o
      |}
      |INSERT {
      |   <$uri> <$prop> ${literalExpression(value, None)}
      |}
      |WHERE {
      |   OPTIONAL { <$uri> <$prop> ?o }
      |}
     """.stripMargin.trim

  def updateSyncedFalseQ(graphName: String, uri: String): String =
    s"""
      |WITH <$graphName>
      |DELETE {
      |   <$uri> <$synced> ?o
      |}
      |INSERT {
      |   <$uri> <$synced> false
      |}
      |WHERE {
      |   OPTIONAL { <$uri> <$synced> ?o }
      |}
     """.stripMargin.trim

  def removeLiteralPropertyQ(graphName: String, uri: String, prop: NXProp) =
    s"""
      |WITH <$graphName>
      |DELETE {
      |   <$uri> <$prop> ?o
      |}
      |WHERE {
      |   <$uri> <$prop> ?o
      |}
     """.stripMargin

  def addLiteralPropertyToListQ(graphName: String, uri: String, prop: NXProp, value: String) =
    s"""
      |INSERT DATA {
      |  GRAPH <$graphName> {
      |    <$uri> <$prop> ${literalExpression(value, None)} .
      |  }
      |}
     """.stripMargin.trim

  def deleteLiteralPropertyFromListQ(graphName: String, uri: String, prop: NXProp, value: String) =
    s"""
      |WITH <$graphName>
      |DELETE {
      |  <$uri> <$prop> ${literalExpression(value, None)} .
      |}
      |WHERE {
      |  <$uri> <$prop> ${literalExpression(value, None)} .
      |}
     """.stripMargin

  def deleteDatasetQ(datasetGraphName: String, uri: String, skosGraphName: String) =

    s"""
      |DROP SILENT GRAPH <$datasetGraphName>;
      |DROP SILENT GRAPH <$skosGraphName>;
      |DELETE {
      |   GRAPH ?g {
      |      ?s ?p ?o .
      |   }
      |}
      |WHERE {
      |   GRAPH ?g {
      |      ?foafDoc <$foafPrimaryTopic> ?record .
      |      ?foafDoc <$belongsTo> <$uri> .
      |      ?record a <$recordEntity> .
      |   }
      |   GRAPH ?g {
      |      ?s ?p ?o .
      |   }
      |};
      |DELETE {
      |   GRAPH ?g {
      |      ?s ?p ?o .
      |   }
      |}
      |WHERE {
      |   GRAPH ?g {
      |      ?subject
      |         <$mappingVocabulary> <$uri> ;
      |         a <$terminologyMapping> .
      |   }
      |   GRAPH ?g {
      |      ?s ?p ?o .
      |   }
      |};
     """.stripMargin

  def deleteDatasetRecordsQ(uri: String) =
    s"""
      |DELETE {
      |   GRAPH ?g {
      |      ?s ?p ?o .
      |   }
      |}
      |INSERT {
      |   GRAPH ?g {
      |     ?foafDoc <$deleted> true .
      |     ?foafDoc <$synced> false .
      |     ?foafDoc <$foafPrimaryTopic> ?record .
      |     ?foafDoc <$belongsTo> <$uri> .
      |     ?record a <$recordEntity> .
      |   }
      |}
      |WHERE {
      |   GRAPH ?g {
      |      ?foafDoc <$foafPrimaryTopic> ?record .
      |      ?foafDoc <$belongsTo> <$uri> .
      |      ?record a <$recordEntity> .
      |   }
      |   GRAPH ?g {
      |      ?s ?p ?o .
      |   }
      |};
     """.stripMargin

  def markOlderRecordsDeletedQ(fromSaveTime: DateTime, uri:String) =
    s"""
      |DELETE {
      |   GRAPH ?g {
      |      ?s ?p ?o .
      |   }
      |}
      |INSERT {
      |   GRAPH ?g {
      |      ?foafDoc <$deleted> true .
      |      ?foafDoc <$foafPrimaryTopic> ?record .
      |      ?foafDoc <$belongsTo> <$uri> .
      |      ?record a <$recordEntity> .
      |   }
      |}
      |WHERE {
      |   GRAPH ?g {
      |      ?foafDoc <$saveTime> ?saveTime
      |      FILTER (?saveTime < "${Temporal.timeToUTCString(fromSaveTime)}")
      |      ?foafDoc <$foafPrimaryTopic> ?record .
      |      ?foafDoc <$belongsTo> <$uri> .
      |      ?record a <$recordEntity> .
      |   }
      |   GRAPH ?g {
      |      ?s ?p ?o .
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

  def getVocabStatisticsQ(skosGraphName: String) =
    s"""
      |PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
      |PREFIX skos: <http://www.w3.org/2004/02/skos/core#>
      |SELECT (count(?s) as ?count)
      |WHERE {
      |  GRAPH <$skosGraphName> {
      |     ?s rdf:type skos:Concept
      |  }
      |}
     """.stripMargin

  def dropVocabularyQ(graphName: String, skosGraphName: String, uri: String) =
    s"""
      |CLEAR GRAPH <$graphName>;
      |CLEAR GRAPH <$skosGraphName>;
      |DELETE {
      |   GRAPH ?g {
      |      ?s ?p ?o .
      |   }
      |}
      |WHERE {
      |   GRAPH ?g {
      |      ?subject
      |         <$mappingVocabulary> <$uri> ;
      |         a <$terminologyMapping> .
      |   }
      |   GRAPH ?g {
      |      ?s ?p ?o .
      |   }
      |};

     """.stripMargin

  // === mapping store ===

  def doesMappingExistQ(uriA: String, uriB: String) =
    s"""
      |ASK {
      |  GRAPH ?g {
      |    ?mapping
      |       a <$terminologyMapping>;
      |       <$mappingConcept> <$uriA>;
      |       <$mappingConcept> <$uriB> .
      |  }
      |}
     """.stripMargin

  def deleteMappingQ(uriA: String, uriB: String) =
    s"""
      |DELETE {
      |  GRAPH ?g {
      |    ?s ?p ?o
      |  }
      |}
      |INSERT {
      |  GRAPH ?g {
      |    ?mapping <$mappingDeleted> true
      |  }
      |}
      |WHERE {
      |  GRAPH ?g {
      |    ?mapping <$mappingConcept> <$uriA> .
      |    ?mapping <$mappingConcept> <$uriB> .
      |  }
      |  GRAPH ?g {
      |    ?s ?p ?o
      |  }
      |}
     """.stripMargin

  def insertMappingQ(graphName:String, prefix: String, uri: String, uriA: String, uriB: String, skosA: SkosGraph, skosB: SkosGraph) = {
    val connection = if (skosB.spec == CATEGORIES_SPEC) belongsToCategory.uri else exactMatch
    s"""
      |INSERT DATA {
      |  GRAPH <$graphName> {
      |    <$uriA> <$connection> <$uriB> .
      |    <$uri>
      |       a <$terminologyMapping>;
      |       <$synced> false;
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

  def askTermMappingQ(termUri: String, vocabUri: String, categories: Boolean) = {
    val connection = if (categories) belongsToCategory.uri else exactMatch
    s"""
      |ASK {
      |  GRAPH ?g {
      |    ?termUri <$connection> ?vocabUri .
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
      |      <$skosField> ?fieldPropertyUri ;
      |      a <$datasetEntity> ;
      |      <$datasetSpec> ?spec.
      |  }
      |}
     """.stripMargin

  case class SkosifiedField(spec: String, datasetUri: String, fieldPropertyValue: String) {
    val parts = fieldPropertyValue.split("=")
    val fieldPropertyTag = parts(0)
    val fieldPropertyUri = parts(1)
    val skosGraph = getSkosGraphName(datasetUri)

    val removeSkosEntriesQ =
      s"""
        |PREFIX skos: <http://www.w3.org/2004/02/skos/core#>
        |DELETE {
        |   GRAPH <$skosGraph> {
        |      ?mintedUri ?p ?o .
        |   }
        |}
        |WHERE {
        |   GRAPH <$skosGraph> {
        |      ?mintedUri
        |         a skos:Concept ;
        |         <$belongsTo> <$datasetUri> ;
        |         <$skosField> <$fieldPropertyUri> .
        |   }
        |   GRAPH <$skosGraph> {
        |      ?mintedUri ?p ?o .
        |   }
        |};
       """.stripMargin
  }

  def skosifiedFieldFromResult(resultMap: Map[String, QueryValue]) = SkosifiedField(
    resultMap("spec").text,
    resultMap("datasetUri").text,
    resultMap("fieldPropertyUri").text
  )

  def skosificationCasesExistQ(sf: SkosifiedField) =
    s"""
      |ASK {
      |  GRAPH ?g {
      |    ?anything <${sf.fieldPropertyUri}> ?literalValue .
      |    FILTER isLiteral(?literalValue)
      |    ?foafDoc <$foafPrimaryTopic> ?record .
      |    ?foafDoc <$belongsTo> <${sf.datasetUri}> .
      |    ?record a <$recordEntity> .
      |  }
      |}
     """.stripMargin

  def countSkosificationCasesQ(sf: SkosifiedField) =
    s"""
      |SELECT (COUNT(DISTINCT ?literalValue) as ?count)
      |WHERE {
      |  GRAPH ?g {
      |    ?anything <${sf.fieldPropertyUri}> ?literalValue .
      |    FILTER isLiteral(?literalValue)
      |    ?foafDoc <$foafPrimaryTopic> ?record .
      |    ?foafDoc <$belongsTo> <${sf.datasetUri}> .
      |    ?record a <$recordEntity> .
      |  }
      |}
     """.stripMargin

  def countFromResult(mapList: List[Map[String, QueryValue]]): Int = mapList.head.get("count").get.text.toInt

  def listSkosificationCasesQ(sf: SkosifiedField, chunkSize: Int) =
    s"""
      |SELECT DISTINCT ?literalValue
      |WHERE {
      |  GRAPH ?g {
      |    ?anything <${sf.fieldPropertyUri}> ?literalValue .
      |    FILTER isLiteral(?literalValue)
      |    ?foafDoc <$foafPrimaryTopic> ?record .
      |    ?foafDoc <$belongsTo> <${sf.datasetUri}> .
      |    ?record a <$recordEntity> .
      |  }
      |}
      |LIMIT $chunkSize
     """.stripMargin

  def createCasesFromQueryValues(sf: SkosifiedField, resultList: List[Map[String, QueryValue]]): List[SkosificationCase] =
    resultList.map(_("literalValue")).map(v => SkosificationCase(sf, v.text, v.language))

  def createCasesFromHistogram(dsInfo: DsInfo, json: JsValue): List[SkosificationCase] = {
    val fieldPropertyTag = (json \ "tag").as[String]
    val fieldPropertyUri = (json \ "uri").as[String]
    val fieldPropertyValue = s"$fieldPropertyTag=$fieldPropertyUri"
    val histogram = (json \ "histogram").as[List[List[String]]]
    val sf = SkosifiedField(dsInfo.spec, dsInfo.uri, fieldPropertyValue)
    // todo: no language
    histogram.map(count => SkosificationCase(sf, count(1), None, Some(count.head.toInt)))
  }

  case class SkosificationCase(sf: SkosifiedField, literalValueText: String, languageOpt: Option[String], frequencyOpt: Option[Int] = None) {
    val mintedUri = s"${sf.datasetUri}/${sf.fieldPropertyTag}/${slugify(literalValueText)}"
    val datasetUri = sf.datasetUri
    val value = literalValueText

    val ensureSkosEntryQ =
      s"""
        |PREFIX skos: <http://www.w3.org/2004/02/skos/core#>
        |INSERT {
        |   GRAPH <${sf.skosGraph}> {
        |      <$mintedUri>
        |         a skos:Concept ;
        |         a <$proxyResource> ;
        |         skos:altLabel ${literalExpression(value, languageOpt)} ;
        |         <$proxyLiteralValue> ${literalExpression(value, languageOpt)} ;
        |         <$belongsTo> <$datasetUri> ;
        |         <$skosField> <${sf.fieldPropertyUri}> ;
        |         <$skosFieldTag> ${literalExpression(sf.fieldPropertyTag, None)} ;
        |         <$proxyLiteralField> <${sf.fieldPropertyUri}> ;
        |         <$synced> false .
        |      ${frequencyOpt.map(freq => s"<$mintedUri> <$skosFrequency> '''$freq''' .").getOrElse("")}
        |   }
        |}
        |WHERE {
        |   FILTER NOT EXISTS {
        |      GRAPH <${sf.skosGraph}> {
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
        |    ?s <${sf.fieldPropertyUri}> ${literalExpression(value, languageOpt)} .
        |  }
        |}
        |INSERT {
        |  GRAPH ?g {
        |    ?s <${sf.fieldPropertyUri}> <$mintedUri> .
        |  }
        |}
        |WHERE {
        |  GRAPH ?g {
        |    ?s <${sf.fieldPropertyUri}> ${literalExpression(value, languageOpt)} .
        |    ?record a <$recordEntity> .
        |    ?foafDoc <$foafPrimaryTopic> ?record .
        |    ?foafDoc <$belongsTo> <${sf.datasetUri}> .
        |  }
        |};
       """.stripMargin
  }

}
