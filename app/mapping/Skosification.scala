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

  def listLiteralValues(sf: SkosifiedField, chunkSize: Int) = {
    s"""
      |SELECT ?literalValue
      |WHERE {
      |  GRAPH ?g {
      |    ?record
      |       <$belongsTo> <${sf.datasetUri}> ;
      |       <${sf.fieldPropertyUri}> ?literalValue .
      |    FILTER isLiteral(?literalValue)
      |  }
      |}
      |LIMIT $chunkSize
      """.stripMargin
  }

  object SkosificationCase {
    def apply(skosifiedField: SkosifiedField, resultMap: Map[String, QueryValue]): SkosificationCase = {
      SkosificationCase(skosifiedField, resultMap("literalValue"))
    }
  }

  case class SkosificationCase(sf: SkosifiedField, literalValue: QueryValue) {
    // todo: what exactly is illegal?
    val fieldValueUri = s"${sf.datasetUri}/${urlEncodeValue(literalValue.text)}"

    val checkExistence =
      s"""
      |PREFIX skos: <http://www.w3.org/2004/02/skos/core#>
      |ASK {
      |   GRAPH <${sf.datasetSkosUri}> {
      |      <$fieldValueUri> a skos:Concept .
      |      <$fieldValueUri> <$belongsTo> <${sf.datasetUri}> .
      |   }
      |}
      """.stripMargin

    val skosAddition =
      s"""
      |PREFIX skos: <http://www.w3.org/2004/02/skos/core#>
      |INSERT DATA {
      |   GRAPH <${sf.datasetSkosUri}> {
      |      <$fieldValueUri> a skos:Concept .
      |      <$fieldValueUri> skos:prefLabel "${literalValue.quoted}" .
      |      <$fieldValueUri> <$belongsTo> <${sf.datasetUri}> .
      |      <$fieldValueUri> <$synced> false .
      |   }
      |};
      """.stripMargin

    val changeLiteralToUri =
      s"""
      |DELETE {
      |  GRAPH ?g {
      |     ?s <${sf.fieldPropertyUri}> "${literalValue.quoted}" .
      |  }
      |}
      |INSERT {
      |  GRAPH ?g {
      |     ?s <${sf.fieldPropertyUri}> <$fieldValueUri> .
      |  }
      |}
      |WHERE {
      |  GRAPH ?g {
      |     ?s <${sf.fieldPropertyUri}> "${literalValue.quoted}" .
      |     ?s <$belongsTo> <${sf.datasetUri}> .
      |  }
      |};
      """.stripMargin
  }

}
