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

import services.NarthexConfig._
import services.StringHandling.urlEncodeValue

trait Skosification {

  // TODO: fieldPath is currently still just a property

  def listSkosifiedFields = {
    s"""
      |PREFIX nx: <$NX_NAMESPACE>
      |SELECT ?datasetUri ?fieldPath
      |WHERE {
      |  GRAPH ?g {
      |    ?datasetUri nx:skosField ?fieldPath .
      |  }
      |}
      """.stripMargin
  }

  object SkosifiedField {
    def apply(resultMap: Map[String, String]): SkosifiedField = {
      SkosifiedField(resultMap("datasetUri"), resultMap("fieldPath"))
    }
  }

  case class SkosifiedField(datasetUri: String, fieldPath: String) {
    val datasetSkosUri = s"$datasetUri/skos"
  }

  def listLiteralValues(skosifiedField: SkosifiedField, chunkSize: Int) = {
    s"""
      |PREFIX nx: <$NX_NAMESPACE>
      |SELECT ?literalValue
      |WHERE {
      |  GRAPH ?g {
      |    ?recordUri nx:belongsTo <${skosifiedField.datasetUri}> .
      |    ?recordUri <${skosifiedField.fieldPath}> ?literalValue .
      |    FILTER isLiteral(?literalValue)
      |  }
      |}
      |LIMIT $chunkSize
      """.stripMargin
  }

  object SkosificationCase {
    def apply(skosifiedField: SkosifiedField, resultMap: Map[String, String]): SkosificationCase = {
      SkosificationCase(skosifiedField, resultMap("literalValue"))
    }
  }

  case class SkosificationCase(skosifiedField: SkosifiedField, literalValue: String) {

    // todo: what is illegal?
    val literalForQuotes = literalValue.replaceAll("\"", "")

    val fieldValueUri = s"${skosifiedField.datasetUri}/${urlEncodeValue(literalValue)}"

    val checkExistence =
      s"""
      |PREFIX nx: <$NX_NAMESPACE>
      |PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
      |PREFIX skos: <http://www.w3.org/2004/02/skos/core#>
      |ASK {
      |   GRAPH <${skosifiedField.datasetSkosUri}> {
      |      <$fieldValueUri> rdf:type skos:Concept .
      |      <$fieldValueUri> nx:belongsTo <${skosifiedField.datasetUri}> .
      |   }
      |}
      """.stripMargin

    val skosAddition =
      s"""
      |PREFIX nx: <$NX_NAMESPACE>
      |PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
      |PREFIX skos: <http://www.w3.org/2004/02/skos/core#>
      |INSERT DATA {
      |   GRAPH <${skosifiedField.datasetSkosUri}> {
      |      <$fieldValueUri> rdf:type skos:Concept .
      |      <$fieldValueUri> skos:prefLabel "$literalForQuotes" .
      |      <$fieldValueUri> nx:belongsTo <${skosifiedField.datasetUri}> .
      |   }
      |}
      """.stripMargin

    val changeLiteralToUri =
      s"""
      |PREFIX nx: <$NX_NAMESPACE>
      |DELETE {
      |  GRAPH ?g {
      |     ?record <${skosifiedField.fieldPath}> "$literalForQuotes" .
      |  }
      |}
      |INSERT {
      |  GRAPH ?g {
      |     ?record <${skosifiedField.fieldPath}> <$fieldValueUri> .
      |  }
      |}
      |WHERE {
      |  GRAPH ?g {
      |     ?record <${skosifiedField.fieldPath}> "$literalForQuotes" .
      |     ?record nx:belongsTo <${skosifiedField.datasetUri}> .
      |  }
      |}
      """.stripMargin
  }

}
