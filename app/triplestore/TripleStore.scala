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
import play.api.Play.current
import play.api.libs.json.JsObject
import play.api.libs.ws.WS

import scala.concurrent.Future

// todo: use an actor's exec context?

import scala.concurrent.ExecutionContext.Implicits.global

object TripleStore {

  case class PropType(uriOpt: Option[String])

  val stringProp = PropType(None)
  val timeProp = PropType(None)
  val intProp = PropType(None)
  val booleanProp = PropType(None)
  val uriProp = PropType(None)

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

  def query(sparqlQuery: String): Future[List[Map[String, String]]] = {
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
        vars.map { v =>
          var valueObject = (binding \ v).as[JsObject]
          // there is type: uri, literal, bnode and also datatype and xml:lang
          var value = (valueObject \ "value").as[String]
          v -> value
        }.toMap
      }
    }
  }

  def update(sparqlUpdate: String): Future[Unit] = {
    if (printQueries) println(sparqlUpdate)
    val request = WS.url(s"$storeURL/update").withHeaders("Content-Type" -> "application/sparql-update")
    //    println(s"update:\n$sparqlUpdate")
    request.post(sparqlUpdate).map { response =>
      if (response.status / 100 != 2) {
        throw new RuntimeException(s"Response not 2XX, but ${response.status}: ${response.statusText}")
      }
    }
  }

  private def dataRequest(graphURI: String) = WS.url(s"$storeURL/data").withQueryString("graph" -> graphURI)

  def dataPost(graphURI: String, model: Model): Future[Boolean] = {
    val sw = new StringWriter()
    model.write(sw, "TURTLE")
    //    println(s"posting: $sw")
    dataRequest(graphURI).withHeaders("Content-Type" -> "text/turtle").post(sw.toString).map { response =>
      //      println(s"post response: ${response.status}")
      if (response.status / 100 != 2) {
        throw new RuntimeException(s"Response not 2XX, but ${response.status}: ${response.statusText}")
      }
      true
    }
  }

  def dataPostXMLFile(graphURI: String, file: File): Future[Boolean] = {
    println(s"Posting $file")
    dataRequest(graphURI).withHeaders("Content-Type" -> "application/rdf+xml").post(file).map { response =>
      //      println(s"post response: ${response.status}")
      if (response.status / 100 != 2) {
        throw new RuntimeException(s"Response not 2XX, but ${response.status}: ${response.statusText}")
      }
      true
    }
  }

  def dataGet(graphURI: String): Future[Model] = {
    dataRequest(graphURI).withHeaders("Accept" -> "text/turtle").get().map { response =>
      if (response.status / 100 != 2) {
        throw new RuntimeException(s"Response not 2XX, but ${response.status}: ${response.statusText}")
      }
      val rdf = response.body
      ModelFactory.createDefaultModel().read(new StringReader(rdf), null, "TURTLE")
    }
  }

}


