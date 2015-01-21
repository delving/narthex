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

  val checkForWork = {
    // future SkosificiationCase, actor can use pipeTo
    val limit = 12
    s"""
      |@PREFIX nx: <$NX_NAMESPACE>
      |SELECT ?dataset ?fieldProperty ?fieldValue
      |WHERE {
      |  GRAPH ?g {
      |    ?dataset nx:skosField ?fieldProperty
      |    ?record nx:belongsTo ?dataset
      |    ?record ?fieldProperty ?fieldValue
      |    FILTER isLiteral(?fieldValue)
      |  }
      |}
      |LIMIT $limit
      """.stripMargin
  }

  case class SkosificationCase(datasetUri: String,
                               datasetSkosUri: String,
                               fieldValue: String,
                               fieldProperty: String) {
    // todo: what is illegal?
    val fieldValueQuoted = fieldValue.replaceAll("\"", "")

    val fieldValueUri = s"$NX_URI_PREFIX/$datasetUri/${urlEncodeValue(fieldValue)}"

    val checkExistence = {
      s"""
      |@PREFIX nx: <$NX_NAMESPACE>
      |@PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
      |@PREFIX skos: <http://www.w3.org/2004/02/skos/core#>
      |ASK {
      |   GRAPH <$datasetSkosUri> {
      |      <$fieldValueUri> rdf:type skos:Concept .
      |      <$fieldValueUri> nx:belongsTo <$datasetUri> .
      |   }
      |}
      """.stripMargin
    }

    val skosAddition = {
      s"""
      |@PREFIX nx: <$NX_NAMESPACE>
      |@PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
      |@PREFIX skos: <http://www.w3.org/2004/02/skos/core#>
      |INSERT DATA {
      |   GRAPH <$datasetSkosUri> {
      |      <$fieldValueUri> rdf:type skos:Concept .
      |      <$fieldValueUri> skos:prefLabel "$fieldValueQuoted" .
      |      <$fieldValueUri> nx:belongsTo <$datasetUri> .
      |   }
      |}
      """.stripMargin
    }

    val changeLiteralToUri = {
      s"""
      |@PREFIX nx: <$NX_NAMESPACE>
      |WITH GRAPH ?g
      |DELETE {
      |  ?record <$fieldProperty> "$fieldValueQuoted" .
      |}
      |INSERT {
      |  ?record <$fieldProperty> <$fieldValueUri> .
      |}
      |WHERE {
      |  ?record <$fieldProperty> "$fieldValueQuoted" .
      |  ?record nx:belongsTo <$datasetUri> .
      |}
      """.stripMargin
    }
  }

}
