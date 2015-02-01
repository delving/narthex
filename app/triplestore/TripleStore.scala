//===========================================================================
//    Copyright 2014 Delving B.V.
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

import java.io.{File, StringReader, StringWriter}

import com.hp.hpl.jena.rdf.model.{Model, ModelFactory}
import com.ning.http.client.providers.netty.NettyResponse
import play.api.Play.current
import play.api.libs.json.JsObject
import play.api.libs.ws.{WS, WSResponse}
import triplestore.TripleStore.QueryValue

import scala.concurrent.Future

// todo: use an actor's exec context?

import scala.concurrent.ExecutionContext.Implicits.global

object TripleStore {

  case class QueryValueType(name: String)

  val QV_LITERAL = QueryValueType("literal")
  val QV_URI = QueryValueType("uri")
  val QV_UNKNOWN = QueryValueType("unknown")

  case class QueryValue(valueObject: JsObject) {
    val text = (valueObject \ "value").as[String]
    val qvt = (valueObject \ "type").as[String] match {
      case "typed-literal" => QV_LITERAL // todo: worry about the type
      case "literal" => QV_LITERAL
      case "uri" => QV_URI
      case x =>
        println(s"Unhandled type $x !")
        QV_UNKNOWN
      // there is type: uri, literal, bnode and also datatype and xml:lang
    }
    // todo: find out what needs replacing
    lazy val quoted = text.replaceAll("\"", "")
  }

}

class TripleStore(storeURL: String, printQueries: Boolean = false) {

  def ask(sparqlQuery: String): Future[Boolean] = {
    if (printQueries) println(sparqlQuery)
    val request = WS.url(s"$storeURL/query").withQueryString(
      "query" -> sparqlQuery,
      "output" -> "json"
    )
    request.get().map { response =>
      if (response.status / 100 != 2) {
        throw new RuntimeException(s"Response not 2XX, but ${response.status}: ${response.statusText}")
      }
      (response.json \ "boolean").as[Boolean]
    }
  }

  def query(sparqlQuery: String): Future[List[Map[String, QueryValue]]] = {
    if (printQueries) println(sparqlQuery)
    val request = WS.url(s"$storeURL/query").withQueryString(
      "query" -> sparqlQuery,
      "output" -> "json"
    )
    request.get().map { response =>
      if (response.status / 100 != 2) {
        throw new RuntimeException(s"Response not 2XX, but ${response.status}: ${response.statusText}")
      }
      val json = response.json
      val vars = (json \ "head" \ "vars").as[List[String]]
      val bindings = (json \ "results" \ "bindings").as[List[JsObject]]
      bindings.map { binding =>
        vars.map(v => v -> QueryValue((binding \ v).as[JsObject])).toMap
      }
    }
  }

  private def errorResponse(response: WSResponse) =
    if (response.status / 100 == 2) None
    else {
      Some(response.statusText)
    }


  def update(sparqlUpdate: String): Future[Option[String]] = {
    if (printQueries) println(sparqlUpdate)
    val request = WS.url(s"$storeURL/update").withHeaders(
      "Content-Type" -> "application/sparql-update; charset=utf-8"
    )
    request.post(sparqlUpdate).map(errorResponse)
  }

  private def dataRequest(graphUri: String) = WS.url(s"$storeURL/data").withQueryString("graph" -> graphUri)

  def dataPost(graphUri: String, model: Model): Future[Option[String]] = {
    val sw = new StringWriter()
    model.write(sw, "TURTLE")
    println(s"posting: $graphUri")
    dataRequest(graphUri).withHeaders(
      "Content-Type" -> "text/turtle; charset=utf-8"
    ).post(sw.toString).map(errorResponse)
  }

  def dataPutXMLFile(graphUri: String, file: File): Future[Option[String]] = {
    println(s"Posting $graphUri")
    dataRequest(graphUri).withHeaders(
      "Content-Type" -> "application/rdf+xml; charset=utf-8"
    ).put(file).map(errorResponse)
  }

  def dataGet(graphUri: String): Future[Model] = {
    dataRequest(graphUri).withHeaders(
      "Accept" -> "text/turtle"
    ).get().map { response =>
      if (response.status / 100 != 2) {
        throw new RuntimeException(s"Response not 2XX, but ${response.status}: ${response.statusText}")
      }
      val netty = response.underlying[NettyResponse]
      val body = netty.getResponseBody("UTF-8")
      ModelFactory.createDefaultModel().read(new StringReader(body), null, "TURTLE")
    }
  }

}


