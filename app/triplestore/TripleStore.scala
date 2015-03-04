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

import java.io.{File, PrintWriter, StringReader, StringWriter}

import com.hp.hpl.jena.rdf.model.{Model, ModelFactory}
import com.ning.http.client.providers.netty.NettyResponse
import play.api.Logger
import play.api.Play.current
import play.api.libs.json.JsObject
import play.api.libs.ws.{WS, WSResponse}
import triplestore.TripleStore.{QueryValue, TripleStoreException}

import scala.concurrent.{ExecutionContext, Future}

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
  }

  class TripleStoreException(message: String) extends Exception(message)

  def single(storeUrl: String, logQueries: Boolean = false)(implicit ec: ExecutionContext) = {
    new Fuseki(storeUrl, logQueries)
  }

  def double(acceptanceStoreUrl: String, productionStoreUrl: String, logQueries: Boolean = false)(implicit ec: ExecutionContext) =
    new DoubleFuseki(acceptanceStoreUrl, productionStoreUrl, logQueries)
}

trait TripleStoreUpdate {

  def sparqlUpdate(sparqlUpdate: String): Future[Unit]

  def dataPost(graphUri: String, model: Model): Future[Unit]

  def dataPutXMLFile(graphUri: String, file: File): Future[Unit]

  def dataPutGraph(graphUri: String, model: Model): Future[Unit]

  def acceptanceOnly(acceptanceOnly: Boolean): TripleStoreUpdate

  val production: TripleStoreUpdate

}

trait TripleStore {

  def ask(sparqlQuery: String): Future[Boolean]

  def query(sparqlQuery: String): Future[List[Map[String, QueryValue]]]

  def dataGet(graphUri: String): Future[Model]

  val up: TripleStoreUpdate

}

class DoubleFuseki(acceptanceStoreUrl: String, productionStoreUrl: String, logQueries: Boolean)(implicit val executionContext: ExecutionContext) extends TripleStore {

  val accFuseki = new Fuseki(acceptanceStoreUrl, logQueries)
  val prodFuseki = new Fuseki(productionStoreUrl, false)

  override def ask(sparqlQuery: String) = accFuseki.ask(sparqlQuery)

  override def query(sparqlQuery: String) = accFuseki.query(sparqlQuery)

  override def dataGet(graphUri: String) = accFuseki.dataGet(graphUri)

  val up = new DoubleUpdate(false)

  class DoubleUpdate(acceptanceOnly: Boolean) extends TripleStoreUpdate {

    private def reportOnFailure(futureUpdate: Future[Unit]) = futureUpdate.onFailure {
      case e: Throwable =>
        Logger.error(s"Unable to update production", e)
    }

    override def sparqlUpdate(sparqlUpdate: String) = {
      if (!acceptanceOnly) reportOnFailure(prodFuseki.up.sparqlUpdate(sparqlUpdate))
      accFuseki.up.sparqlUpdate(sparqlUpdate)
    }

    override def dataPutGraph(graphUri: String, model: Model) = {
      if (!acceptanceOnly) reportOnFailure(prodFuseki.up.dataPutGraph(graphUri, model))
      accFuseki.up.dataPutGraph(graphUri, model)
    }

    override def dataPost(graphUri: String, model: Model) = {
      if (!acceptanceOnly) reportOnFailure(prodFuseki.up.dataPost(graphUri, model))
      accFuseki.up.dataPost(graphUri, model)
    }

    override def dataPutXMLFile(graphUri: String, file: File) = {
      if (!acceptanceOnly) reportOnFailure(prodFuseki.up.dataPutXMLFile(graphUri, file))
      accFuseki.up.dataPutXMLFile(graphUri, file)
    }

    lazy val other = new DoubleUpdate(!acceptanceOnly)

    override def acceptanceOnly(wanted: Boolean) = if (acceptanceOnly == wanted) this else other

    override val production = prodFuseki.up
  }

}

class Fuseki(storeURL: String, logQueries: Boolean)(implicit val executionContext: ExecutionContext) extends TripleStore {

  val logFile = new File("/tmp/triple-store.log")
  val logOutput = if (logQueries) Some(new PrintWriter(logFile)) else None
  var queryIndex = 0

  private def dataRequest(graphUri: String) = WS.url(s"$storeURL/data").withQueryString("graph" -> graphUri)

  private def logSparql(sparql: String) = logOutput.map { w =>
    //    val numbered = sparql.split("\n").zipWithIndex.map(tup => s"${tup._2 + 1}: ${tup._1}").mkString("\n")
    queryIndex += 1
    w.synchronized {
      w.println("=" * 40 + s"($queryIndex)")
      w.println(sparql)
      w.flush()
    }
  }

  private def checkResponse(response: WSResponse): Unit = if (response.status / 100 != 2) {
    throw new TripleStoreException(s"${response.statusText}: ${response.body}")
  }

  override def ask(sparqlQuery: String): Future[Boolean] = {
    logSparql(sparqlQuery)
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

  override def query(sparqlQuery: String): Future[List[Map[String, QueryValue]]] = {
    logSparql(sparqlQuery)
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

  override def dataGet(graphUri: String): Future[Model] = {
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

  val up = new FusekiUpdate

  class FusekiUpdate extends TripleStoreUpdate {
    override def sparqlUpdate(sparqlUpdate: String) = {
      logSparql(sparqlUpdate)
      val request = WS.url(s"$storeURL/update").withHeaders(
        "Content-Type" -> "application/sparql-update; charset=utf-8"
      )
      request.post(sparqlUpdate).map(checkResponse)
    }

    override def dataPost(graphUri: String, model: Model) = {
      val sw = new StringWriter()
      model.write(sw, "TURTLE")
      println(s"posting: $graphUri")
      dataRequest(graphUri).withHeaders(
        "Content-Type" -> "text/turtle; charset=utf-8"
      ).post(sw.toString).map(checkResponse)
    }

    override def dataPutXMLFile(graphUri: String, file: File) = {
      println(s"Posting $graphUri")
      dataRequest(graphUri).withHeaders(
        "Content-Type" -> "application/rdf+xml; charset=utf-8"
      ).put(file).map(checkResponse)
    }

    override def dataPutGraph(graphUri: String, model: Model) = {
      val sw = new StringWriter()
      model.write(sw, "TURTLE")
      println(s"posting: $graphUri")
      dataRequest(graphUri).withHeaders(
        "Content-Type" -> "text/turtle; charset=utf-8"
      ).put(sw.toString).map(checkResponse)
    }

    override def acceptanceOnly(acceptanceOnly: Boolean) = this

    override val production: TripleStoreUpdate = this
  }

}


