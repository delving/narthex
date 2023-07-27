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

import organization.OrgContext
import play.api.libs.json.Json
import play.api.libs.ws.WSClient
import services.StringHandling.createGraphName
import triplestore.Sparql._
import triplestore.{SkosGraph, TripleStore}

import scala.concurrent.{ExecutionContext, Future}

object SkosMappingStore {

  case class SkosMapping(uriA: String, uriB: String) {

    val existenceQ = doesMappingExistQ(uriA, uriB)

    val deleteQ = deleteMappingQ(uriA, uriB)

    def insertQ(skosA: SkosGraph, skosB: SkosGraph, nxUriPrefix: String) = {

      val uuid = UUID.randomUUID().toString
      val uri = s"$nxUriPrefix/actor/admin/mapping/$uuid"

      val graphName = createGraphName(uri)

      insertMappingQ(graphName, nxUriPrefix, uri, uriA, uriB, skosA, skosB)
    }

    override def toString = s"SkosMapping($uriA, $uriB)"
  }

}

class VocabMappingStore(skosA: SkosGraph, skosB: SkosGraph, orgContext: OrgContext)(implicit ec: ExecutionContext, ts: TripleStore) {

  import mapping.SkosMappingStore._

  def toggleMapping(mapping: SkosMapping): Future[String] = {
    ts.ask(mapping.existenceQ).flatMap { exists =>
      if (exists) {
        // todo insert mapping to nave here
        ts.up.sparqlUpdate(mapping.deleteQ).map(ok => "removed")
      }
      else {
        // todo insert mapping to nave here
        ts.up.sparqlUpdate(mapping.insertQ(skosA, skosB, orgContext.appConfig.nxUriPrefix)).map(ok => "added")
      }
    }
  }

  def getMappings: Future[Seq[(String, String)]] = {
    ts.query(getVocabMappingsQ(skosA, skosB)).map(_.map(ab => (ab("a").text, ab("b").text)))
  }

}

class TermMappingStore(termGraph: SkosGraph, orgContext: OrgContext, wsApi: WSClient)(implicit ec: ExecutionContext, ts: TripleStore) {

  import mapping.SkosMappingStore._

  def toggleNaveMapping(mapping: SkosMapping, delete: Boolean = false) = {

    val skosMappingApi = s"${orgContext.appConfig.naveApiUrl}/api/index/narthex/toggle/proxymapping/"
    val request = wsApi.url(s"$skosMappingApi").withHeaders(
      "Content-Type" -> "application/json; charset=utf-8",
      "Accept" -> "application/json",
      "Authorization" -> s"Token ${orgContext.appConfig.naveApiAuthToken}"
    )
    val json = Json.obj(
      "proxy_resource_uri" -> mapping.uriA,
      "skos_concept_uri" -> mapping.uriB,
      "user_uri" -> s"${orgContext.appConfig.nxUriPrefix}/actor/admin", // preserve for compatibility purposes
      "delete" -> delete
    )

    request.post(json)
  }

  def toggleMapping(mapping: SkosMapping, vocabGraph: SkosGraph): Future[String] = {
    ts.ask(mapping.existenceQ).flatMap { exists =>
      if (exists) {
        toggleNaveMapping(mapping, true)
        ts.up.sparqlUpdate(mapping.deleteQ).map(ok => "removed")
      }
      else {
        toggleNaveMapping(mapping, false)
        ts.up.sparqlUpdate(mapping.insertQ(termGraph, vocabGraph, orgContext.appConfig.nxUriPrefix)).map(ok => "added")
      }
    }
  }

  def getMappings(categories:Boolean): Future[List[List[String]]] = {
    ts.query(getTermMappingsQ(termGraph, categories)).map { resultMap =>
      resultMap.map { ab =>
        List(ab("termUri").text, ab("vocabUri").text, ab("vocabSpec").text)
      }
    }
  }
}
