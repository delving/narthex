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

import org.joda.time.DateTime
import services.Temporal
import triplestore.GraphProperties._

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

  // Phase D4: everything below this file's former contents is deleted —
  // datasets.db replaced the info graph; only the migration slice reads
  // Fuseki (spec listing above + TripleStore.dataGet).
}
