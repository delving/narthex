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

import javax.inject._
import java.io.{File, StringReader, StringWriter}
import java.nio.charset.StandardCharsets

import org.apache.jena.rdf.model.{Model, ModelFactory}
import play.api.Logger
import play.api.libs.json.JsObject
import play.api.libs.ws.{WSClient, WSResponse}
import play.shaded.ahc.org.asynchttpclient.netty.NettyResponse
import scala.concurrent._

import triplestore.TripleStore.{QueryValue, TripleStoreException}
import init.NarthexConfig

object TripleStore {

  private val logger = Logger(getClass)

  case class QueryValueType(name: String)

  val QV_LITERAL = QueryValueType("literal")
  val QV_URI = QueryValueType("uri")
  val QV_UNKNOWN = QueryValueType("unknown")

  case class QueryValue(valueObject: JsObject) {
    val text = (valueObject \ "value").as[String]
    val language = (valueObject \ "xml:lang").asOpt[String]
    val valueType = (valueObject \ "type").as[String] match {
      case "typed-literal" => QV_LITERAL // todo: worry about the type
      case "literal" => QV_LITERAL
      case "uri" => QV_URI
      case x =>
        logger.error(s"Unhandled type $x !")
        QV_UNKNOWN
    }
  }

  class TripleStoreException(message: String) extends Exception(message)


}

trait TripleStoreUpdate {

  def sparqlUpdate(sparqlUpdate: String): Future[Unit]

  def dataPost(graphUri: String, model: Model): Future[Unit]

  def dataPutXMLFile(graphUri: String, file: File): Future[Unit]

  def dataPutGraph(graphUri: String, model: Model): Future[Unit]

}

trait TripleStore {

  def ask(sparqlQuery: String): Future[Boolean]

  def query(sparqlQuery: String): Future[List[Map[String, QueryValue]]]

  def dataGet(graphName: String): Future[Model]

  val up: TripleStoreUpdate

}

class Fuseki @Inject() (wsApi: WSClient, narthexConfig: NarthexConfig) (implicit val executionContext: ExecutionContext) extends TripleStore {

  private val logger = Logger(getClass)

  var storeURL: String = narthexConfig.tripleStoreUrl
  var orgID: String = narthexConfig.orgId
  var sparqlQueryPath: String = narthexConfig.sparqlQueryPath
  var sparqlUpdatePath: String = narthexConfig.sparqlUpdatePath
  var graphStorePath: String = narthexConfig.graphStorePath
  var graphStoreParam: String = narthexConfig.graphStoreParam
  var logQueries: Boolean = narthexConfig.tripleStoreLog

  var queryIndex = 0

  private def dataRequest(graphUri: String) = wsApi.url(s"$storeURL/$orgID$graphStorePath").withQueryString(s"$graphStoreParam" -> graphUri)

  private def toLog(sparql: String): String = {
    queryIndex += 1
    val numbered = sparql.split("\n").zipWithIndex.map(tup => s"${tup._2 + 1}: ${tup._1}").mkString("\n")
    val divider = "=" * 40 + s"($queryIndex)\n"
    divider + numbered
  }

  private def logSparql(sparql: String): Unit = if (logQueries) logger.debug(toLog(sparql))

  override def ask(sparqlQuery: String): Future[Boolean] = {
    logSparql(sparqlQuery)
    val request = wsApi.url(s"$storeURL/$orgID$sparqlQueryPath").withQueryString(
      "query" -> sparqlQuery
      //"output" -> "json"
    ).withHeaders(
        "Accept-Charset" -> "utf-8",
        "Accept-Encoding" -> "utf-8",
        "ACCEPT" -> "application/sparql-results+json",
        "CONTENT_TYPE" -> "application/x-www-form-urlencoded"
      )
    request.get().map { response =>
      if (response.status / 100 != 2) {
        throw new RuntimeException(s"Ask response not 2XX, but ${response.status}: ${response.statusText}\n${toLog(sparqlQuery)}")
      }
      (response.json \ "boolean").as[Boolean]
    }
  }

  override def query(sparqlQuery: String): Future[List[Map[String, QueryValue]]] = {
    logSparql(sparqlQuery)
    val request = wsApi.url(s"$storeURL/$orgID$sparqlQueryPath")
      .withQueryString(
        "query" -> sparqlQuery
        //"output" -> "json"
      )
      .withHeaders(
        "Accept-Charset" -> "utf-8",
        "Accept-Encoding" -> "utf-8",
        "ACCEPT" -> "application/sparql-results+json",
        "CONTENT_TYPE" -> "application/x-www-form-urlencoded"
      )
      request.get().map { response =>
      if (response.status / 100 != 2) {
        throw new RuntimeException(s"Query response not 2XX, but ${response.status}: ${response.statusText}\n${toLog(sparqlQuery)}")
      }
      val json = response.json
      val vars = (json \ "head" \ "vars").as[List[String]]
      val bindings = (json \ "results" \ "bindings").as[List[JsObject]]
      bindings.flatMap { binding =>
        if (binding.keys.isEmpty)
          None
        else {
          val valueMap = vars.flatMap(v => (binding \ v).asOpt[JsObject].map(value => v -> QueryValue(value))).toMap
          Some(valueMap)
        }
      }
    }
  }

  override def dataGet(graphName: String): Future[Model] = {
    dataRequest(graphName).withHeaders(
      "Accept" -> "text/turtle"
    ).get().map { response =>
      if (response.status / 100 != 2) {
        throw new RuntimeException(s"Get response for $graphName not 2XX, but ${response.status}: ${response.statusText}")
      }
      val netty = response.underlying[NettyResponse]
      val body = netty.getResponseBody(StandardCharsets.UTF_8)
      ModelFactory.createDefaultModel().read(new StringReader(body), null, "TURTLE")
    }
  }

  val up = new FusekiUpdate

  class FusekiUpdate extends TripleStoreUpdate {

    private def checkUpdateResponse(response: WSResponse, logString: String): Unit = if (response.status / 100 != 2) {
      logger.error(logString)
      throw new TripleStoreException(s"${response.statusText}: ${response.body}:")
    }

    override def sparqlUpdate(sparqlUpdate: String) = {
      logSparql(sparqlUpdate)
      val request = wsApi.url(s"$storeURL/$orgID$sparqlUpdatePath").withHeaders(
        "Content-Type" -> "application/sparql-update; charset=utf-8"
      )
      request.post(sparqlUpdate).map(checkUpdateResponse(_, sparqlUpdate))
    }

    override def dataPost(graphUri: String, model: Model) = {
      val sw = new StringWriter()
      model.write(sw, "TURTLE")
      val turtle = sw.toString
      logSparql(turtle)
      dataRequest(graphUri).withHeaders(
        "Content-Type" -> "text/turtle; charset=utf-8"
      ).post(turtle).map(checkUpdateResponse(_, turtle))
    }

    override def dataPutXMLFile(graphUri: String, file: File) = {
      logger.debug(s"Putting $graphUri")
      dataRequest(graphUri).withHeaders(
        "Content-Type" -> "application/rdf+xml; charset=utf-8"
      ).put(file).map(checkUpdateResponse(_, graphUri))
    }

    override def dataPutGraph(graphUri: String, model: Model) = {
      val sw = new StringWriter()
      model.write(sw, "TURTLE")
      val turtle = sw.toString
      logger.debug(s"Putting $graphUri")
      dataRequest(graphUri).withHeaders(
        "Content-Type" -> "text/turtle; charset=utf-8"
      ).put(turtle).map(checkUpdateResponse(_, turtle))
    }

  }

}


