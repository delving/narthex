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

import dataset.DsInfo.getSkosUri
import services.StringHandling.urlEncodeValue
import triplestore.GraphProperties._
import triplestore.TripleStore.QueryValue

trait Skosification {

  def listSkosifiedFields = {
    s"""
      |SELECT ?datasetUri ?fieldProperty
      |WHERE {
      |  GRAPH ?g {
      |    ?datasetUri <$skosField> ?fieldProperty .
      |  }
      |}
      """.stripMargin
  }

  object SkosifiedField {
    def apply(resultMap: Map[String, QueryValue]): SkosifiedField = {
      SkosifiedField(resultMap("datasetUri").text, resultMap("fieldProperty").text)
    }
  }

  case class SkosifiedField(datasetUri: String, fieldPropertyUri: String)

  def skosificationCasesExist(skosifiedField: SkosifiedField) = {
    val datasetUri = skosifiedField.datasetUri
    val fieldProperty = skosifiedField.fieldPropertyUri
    s"""
      |ASK {
      |  GRAPH ?g {
      |    ?record <$belongsTo> <$datasetUri> .
      |  }
      |  GRAPH ?g {
      |    ?anything <$fieldProperty> ?literalValue .
      |    FILTER isLiteral(?literalValue)
      |  }
      |}
      """.stripMargin
  }

  def listSkosificationCases(skosifiedField: SkosifiedField, chunkSize: Int) = {
    val datasetUri = skosifiedField.datasetUri
    val fieldProperty = skosifiedField.fieldPropertyUri
    s"""
      |SELECT DISTINCT ?literalValue
      |WHERE {
      |  GRAPH ?g {
      |    ?record <$belongsTo> <$datasetUri> .
      |  }
      |  GRAPH ?g {
      |    ?anything <$fieldProperty> ?literalValue .
      |    FILTER isLiteral(?literalValue)
      |  }
      |}
      |LIMIT $chunkSize
      """.stripMargin
  }

  def createCases(sf: SkosifiedField, resultList: List[Map[String, QueryValue]]): List[SkosificationCase] =
    resultList.map(_("literalValue")).map(v => SkosificationCase(sf, v))

  case class SkosificationCase(skosifieldField: SkosifiedField, literalValue: QueryValue) {
    val mintedUri = s"${skosifieldField.datasetUri}/${urlEncodeValue(literalValue.text)}"
    val fieldProperty = skosifieldField.fieldPropertyUri
    val skosGraph = getSkosUri(skosifieldField.datasetUri)
    val datasetUri = skosifieldField.datasetUri
    val value = literalValue.text

    val ensureSkosEntry =
      s"""
      |PREFIX skos: <http://www.w3.org/2004/02/skos/core#>
      |INSERT {
      |   GRAPH <$skosGraph> {
      |      <$mintedUri> a skos:Concept .
      |      <$mintedUri> skos:altLabel '''$value''' .
      |      <$mintedUri> <$belongsTo> <$datasetUri> .
      |      <$mintedUri> <$synced> false .
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

    val changeLiteralToUri =
      s"""
      |DELETE {
      |  GRAPH ?g {
      |     ?s <$fieldProperty> '''$value''' .
      |  }
      |}
      |INSERT {
      |  GRAPH ?g {
      |     ?s <$fieldProperty> <$mintedUri> .
      |  }
      |}
      |WHERE {
      |  GRAPH ?g {
      |     ?s <$fieldProperty> '''$value''' .
      |     ?s <$belongsTo> <$datasetUri> .
      |  }
      |};
      """.stripMargin
  }

}
