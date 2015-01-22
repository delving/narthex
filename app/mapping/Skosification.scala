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

  def checkForWork(chunkSize: Int) = {
    // todo: it seems fieldProperty cannot be used as a property because it is a resource
    // todo: may have to split it into two queries: first get DS/FP and then use DS/FP to get FV
    s"""
      |PREFIX nx: <$NX_NAMESPACE>
      |SELECT ?dataset ?fieldProperty ?fieldValue
      |WHERE {
      |  GRAPH ?g {
      |    ?dataset nx:skosField ?fieldProperty .
      |    ?record nx:belongsTo ?dataset .
      |    ?record ?fieldProperty ?fieldValue .
      |    FILTER isLiteral(?fieldValue)
      |  }
      |}
      |LIMIT $chunkSize
      """.stripMargin
  }

  object SkosificationCase {
    def apply(workList: List[Map[String, String]]): List[SkosificationCase] = workList.map { workMap =>
      SkosificationCase(
        datasetUri = workMap("datasetUri"),
        fieldProperty = workMap("fieldProperty"),
        fieldValue = workMap("fieldValue")
      )
    }
  }

  case class SkosificationCase(datasetUri: String,
                               fieldProperty: String,
                               fieldValue: String) {

    val datasetSkosUri = s"$datasetUri/skos"

    // todo: what is illegal?
    val fieldValueQuoted = fieldValue.replaceAll("\"", "")

    val fieldValueUri = s"$datasetUri/${urlEncodeValue(fieldValue)}"

    val checkExistence =
      s"""
      |PREFIX nx: <$NX_NAMESPACE>
      |PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
      |PREFIX skos: <http://www.w3.org/2004/02/skos/core#>
      |ASK {
      |   GRAPH <$datasetSkosUri> {
      |      <$fieldValueUri> rdf:type skos:Concept .
      |      <$fieldValueUri> nx:belongsTo <$datasetUri> .
      |   }
      |}
      """.stripMargin

    val skosAddition =
      s"""
      |PREFIX nx: <$NX_NAMESPACE>
      |PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
      |PREFIX skos: <http://www.w3.org/2004/02/skos/core#>
      |INSERT DATA {
      |   GRAPH <$datasetSkosUri> {
      |      <$fieldValueUri> rdf:type skos:Concept .
      |      <$fieldValueUri> skos:prefLabel "$fieldValueQuoted" .
      |      <$fieldValueUri> nx:belongsTo <$datasetUri> .
      |   }
      |}
      """.stripMargin

    val changeLiteralToUri =
      s"""
      |PREFIX nx: <$NX_NAMESPACE>
      |DELETE {
      |  GRAPH ?g {
      |     ?record <$fieldProperty> "$fieldValueQuoted" .
      |  }
      |}
      |INSERT {
      |  GRAPH ?g {
      |     ?record <$fieldProperty> <$fieldValueUri> .
      |  }
      |}
      |WHERE {
      |  GRAPH ?g {
      |     ?record <$fieldProperty> "$fieldValueQuoted" .
      |     ?record nx:belongsTo <$datasetUri> .
      |  }
      |}
      """.stripMargin
  }

}
