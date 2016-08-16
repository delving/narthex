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

import org.{OrgContext, User}
import play.api.Logger
import play.api.libs.json.{JsObject, Json}
import play.api.libs.ws.{WS, WSResponse}
import services.StringHandling.createGraphName
import triplestore.Sparql._
import triplestore.{SkosGraph, TripleStore}

import scala.concurrent.{ExecutionContext, Future}

object SkosMappingStore {

  case class SkosMapping(actor: User, uriA: String, uriB: String) {

    val existenceQ = doesMappingExistQ(uriA, uriB)

    val deleteQ = deleteMappingQ(uriA, uriB)

    def insertQ(skosA: SkosGraph, skosB: SkosGraph) = {

      val uuid = UUID.randomUUID().toString

      val uri = s"${actor.uri(OrgContext.NX_URI_PREFIX)}/mapping/$uuid"

      val graphName = createGraphName(uri)

      insertMappingQ(graphName, actor, uri, uriA, uriB, skosA, skosB)
    }

    override def toString = s"SkosMapping($uriA, $uriB)"
  }

}

class VocabMappingStore(skosA: SkosGraph, skosB: SkosGraph)(implicit ec: ExecutionContext, ts: TripleStore) {

  import mapping.SkosMappingStore._

  def toggleMapping(mapping: SkosMapping): Future[String] = {
    ts.ask(mapping.existenceQ).flatMap { exists =>
      if (exists) {
        // todo insert mapping to nave here
        ts.up.sparqlUpdate(mapping.deleteQ).map(ok => "removed")
      }
      else {
        // todo insert mapping to nave here
        ts.up.sparqlUpdate(mapping.insertQ(skosA, skosB)).map(ok => "added")
      }
    }
  }

  def getMappings: Future[Seq[(String, String)]] = {
    ts.query(getVocabMappingsQ(skosA, skosB)).map(_.map(ab => (ab("a").text, ab("b").text)))
  }

}

class TermMappingStore(termGraph: SkosGraph)(implicit ec: ExecutionContext, ts: TripleStore) {

  import mapping.SkosMappingStore._
  import play.api.Play.current

  def toggleNaveMapping(mapping: SkosMapping, delete: Boolean = false) = {
    def checkUpdateResponse(response: WSResponse, logString: JsObject): Unit = {
      if (response.status != 201) {
        Logger.error(logString.toString())
        throw new Exception(s"${response.statusText}: ${response.body}:")
      }
    }

    val skosMappingApi = s"${OrgContext.NAVE_API_URL}/api/index/narthex/toggle/proxymapping/"
    val request = WS.url(s"$skosMappingApi").withHeaders(
      "Content-Type" -> "application/json; charset=utf-8",
      "Accept" -> "application/json",
      "Authorization" -> s"Token ${OrgContext.NAVE_BULK_API_AUTH_TOKEN}"
    )
    val json = Json.obj(
      "proxy_resource_uri" -> mapping.uriA,
      "skos_concept_uri" -> mapping.uriB,
      "user_uri" -> mapping.actor.uri(OrgContext.NX_URI_PREFIX),
      "delete" -> delete
    )

    request.post(json) // .map(checkUpdateResponse(_, json))
  }

  def toggleMapping(mapping: SkosMapping, vocabGraph: SkosGraph): Future[String] = {
    ts.ask(mapping.existenceQ).flatMap { exists =>
      if (exists) {
        toggleNaveMapping(mapping, true)
        ts.up.sparqlUpdate(mapping.deleteQ).map(ok => "removed")
      }
      else {
        toggleNaveMapping(mapping, false)
        ts.up.sparqlUpdate(mapping.insertQ(termGraph, vocabGraph)).map(ok => "added")
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

