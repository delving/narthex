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

import services.StringHandling.urlEncodeValue
import triplestore.GraphProperties._
import triplestore.TripleStore.QueryValue

trait Skosification {

  /*
      scan for fields
      list literal values for each
      discard the fields which have no literal values
      skosify remaining fields until no liter
   */

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

  case class SkosifiedField(datasetUri: String, fieldPropertyUri: String) {
    val datasetSkosUri = s"$datasetUri/skos"
  }

  def listLiteralValues(skosifiedField: SkosifiedField, chunkSize: Int) = {
    val datasetUri = skosifiedField.datasetUri
    val fieldProperty = skosifiedField.fieldPropertyUri
    s"""
      |SELECT DISTINCT ?literalValue
      |WHERE {
      |  GRAPH ?g {
      |    ?record
      |       <$belongsTo> <$datasetUri> ;
      |       <$fieldProperty> ?literalValue .
      |    FILTER isLiteral(?literalValue)
      |  }
      |}
      |LIMIT $chunkSize
      """.stripMargin
  }

  def literalValuesList(resultList: List[Map[String, QueryValue]]): List[QueryValue] = resultList.map(_("literalValue"))

  object SkosificationCase {
    def apply(skosifiedField: SkosifiedField, resultMap: Map[String, QueryValue]): SkosificationCase = {
      SkosificationCase(skosifiedField, resultMap("literalValue"))
    }
  }

  case class SkosificationCase(skosifieldField: SkosifiedField, literalValue: QueryValue) {
    private val fieldValueUri = s"${skosifieldField.datasetUri}/${urlEncodeValue(literalValue.text)}"
    private val fieldProperty = skosifieldField.fieldPropertyUri
    private val skosGraph = skosifieldField.datasetSkosUri
    private val datasetUri = skosifieldField.datasetUri
    private val quotedValue = literalValue.quoted

    val checkSkosEntry =
      s"""
      |PREFIX skos: <http://www.w3.org/2004/02/skos/core#>
      |ASK {
      |   GRAPH <$skosGraph> {
      |      <$fieldValueUri> a skos:Concept .
      |   }
      |}
      """.stripMargin

    val addSkosEntry =
      s"""
      |PREFIX skos: <http://www.w3.org/2004/02/skos/core#>
      |INSERT DATA {
      |   GRAPH <$skosGraph> {
      |      <$fieldValueUri> a skos:Concept .
      |      <$fieldValueUri> skos:hiddenLabel "$quotedValue" .
      |      <$fieldValueUri> <$belongsTo> <$datasetUri> .
      |      <$fieldValueUri> <$synced> false .
      |   }
      |}
      """.stripMargin

    val changeLiteralToUri =
      s"""
      |DELETE {
      |  GRAPH ?g {
      |     ?s <$fieldProperty> "$quotedValue" .
      |  }
      |}
      |INSERT {
      |  GRAPH ?g {
      |     ?s <$fieldProperty> <$fieldValueUri> .
      |  }
      |}
      |WHERE {
      |  GRAPH ?g {
      |     ?s <$fieldProperty> "$quotedValue" .
      |     ?s <$belongsTo> <$datasetUri> .
      |  }
      |};
      """.stripMargin
  }

}
