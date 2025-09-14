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
import java.util.Base64

import org.apache.jena.rdf.model.{Model, ModelFactory}
import play.api.Logger
import play.api.libs.json.JsObject
import play.api.libs.ws.{WSClient, WSRequest, WSResponse}
import play.shaded.ahc.org.asynchttpclient.netty.NettyResponse
import scala.concurrent._
import scala.concurrent.duration._
import java.util.concurrent.atomic.AtomicInteger

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
  
  def batchCheckGraphExistence(graphUris: List[String]): Future[Map[String, Boolean]]

  val up: TripleStoreUpdate

}

class Fuseki @Inject() (wsApi: WSClient, narthexConfig: NarthexConfig) (implicit val executionContext: ExecutionContext) extends TripleStore {

  private val logger = Logger(getClass)
  
  // Connection metrics
  private val activeConnections = new AtomicInteger(0)
  private val totalRequests = new AtomicInteger(0)
  private val failedRequests = new AtomicInteger(0)

  var storeURL: String = narthexConfig.tripleStoreUrl
  var orgID: String = narthexConfig.orgId
  var sparqlQueryPath: String = narthexConfig.sparqlQueryPath
  var sparqlUpdatePath: String = narthexConfig.sparqlUpdatePath
  var graphStorePath: String = narthexConfig.graphStorePath
  var graphStoreParam: String = narthexConfig.graphStoreParam
  var logQueries: Boolean = narthexConfig.tripleStoreLog

  var queryIndex = 0

  private def configureRequest(request: WSRequest): WSRequest = {
    val baseRequest = request
      .withRequestTimeout(scala.concurrent.duration.Duration(narthexConfig.fusekiRequestTimeoutMs, "milliseconds"))
      .withHeaders(
        "Connection" -> "keep-alive",
        "Accept-Charset" -> "utf-8",
        "Accept-Encoding" -> "gzip, deflate"
      )
    
    // Add HTTP Basic Authentication if configured
    (narthexConfig.fusekiUsername, narthexConfig.fusekiPassword) match {
      case (Some(username), Some(password)) =>
        val credentials = Base64.getEncoder.encodeToString(s"$username:$password".getBytes(StandardCharsets.UTF_8))
        logger.debug(s"Adding Basic Auth for user: $username")
        baseRequest.withHeaders("Authorization" -> s"Basic $credentials")
      case _ =>
        logger.debug("No Fuseki authentication configured - using anonymous access")
        baseRequest
    }
  }

  private def dataRequest(graphUri: String): WSRequest = {
    val baseRequest = wsApi.url(s"$storeURL/$orgID$graphStorePath")
      .withQueryString(s"$graphStoreParam" -> graphUri)
    configureRequest(baseRequest)
  }

  private def queryRequest(sparqlQuery: String): WSRequest = {
    val baseRequest = wsApi.url(s"$storeURL/$orgID$sparqlQueryPath")
      .withQueryString("query" -> sparqlQuery)
    configureRequest(baseRequest).withHeaders(
      "ACCEPT" -> "application/sparql-results+json",
      "CONTENT_TYPE" -> "application/x-www-form-urlencoded"
    )
  }

  private def updateRequest(): WSRequest = {
    val baseRequest = wsApi.url(s"$storeURL/$orgID$sparqlUpdatePath")
    configureRequest(baseRequest).withHeaders(
      "Content-Type" -> "application/sparql-update; charset=utf-8"
    )
  }

  private def toLog(sparql: String): String = {
    queryIndex += 1
    val numbered = sparql.split("\n").zipWithIndex.map(tup => s"${tup._2 + 1}: ${tup._1}").mkString("\n")
    val divider = "=" * 40 + s"($queryIndex)\n"
    divider + numbered
  }

  private def logSparql(sparql: String): Unit = if (logQueries) logger.debug(toLog(sparql))

  private def executeWithMetrics[T](operation: String)(block: => Future[T]): Future[T] = {
    activeConnections.incrementAndGet()
    totalRequests.incrementAndGet()
    val startTime = System.currentTimeMillis()
    
    block.andThen {
      case scala.util.Success(_) =>
        val duration = System.currentTimeMillis() - startTime
        logger.debug(s"$operation completed in ${duration}ms")
        activeConnections.decrementAndGet()
        
      case scala.util.Failure(ex) =>
        failedRequests.incrementAndGet()
        activeConnections.decrementAndGet()
        logger.warn(s"$operation failed after ${System.currentTimeMillis() - startTime}ms: ${ex.getMessage}")
    }
  }

  def getConnectionStats: (Int, Int, Int) = (activeConnections.get(), totalRequests.get(), failedRequests.get())

  override def ask(sparqlQuery: String): Future[Boolean] = {
    logSparql(sparqlQuery)
    executeWithMetrics("ASK query") {
      val request = queryRequest(sparqlQuery)
      request.get().map { response =>
        if (response.status / 100 != 2) {
          response.status match {
            case 401 =>
              logger.error("Fuseki authentication failed for ASK query. Check credentials.")
              throw new TripleStoreException(s"Unauthorized ASK query. Status: ${response.status}")
            case 403 =>
              logger.error("Fuseki access forbidden for ASK query.")
              throw new TripleStoreException(s"Forbidden ASK query. Status: ${response.status}")
            case _ =>
              throw new RuntimeException(s"Ask response not 2XX, but ${response.status}: ${response.statusText}\n${toLog(sparqlQuery)}")
          }
        }
        (response.json \ "boolean").as[Boolean]
      }
    }
  }

  override def query(sparqlQuery: String): Future[List[Map[String, QueryValue]]] = {
    logSparql(sparqlQuery)
    executeWithMetrics("SELECT query") {
      val request = queryRequest(sparqlQuery)
      request.get().map { response =>
        if (response.status / 100 != 2) {
          response.status match {
            case 401 =>
              logger.error("Fuseki authentication failed for SELECT query. Check credentials.")
              throw new TripleStoreException(s"Unauthorized SELECT query. Status: ${response.status}")
            case 403 =>
              logger.error("Fuseki access forbidden for SELECT query.")
              throw new TripleStoreException(s"Forbidden SELECT query. Status: ${response.status}")
            case _ =>
              throw new RuntimeException(s"Query response not 2XX, but ${response.status}: ${response.statusText}\n${toLog(sparqlQuery)}")
          }
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
  }

  override def dataGet(graphName: String): Future[Model] = {
    executeWithMetrics(s"GET graph $graphName") {
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
  }
  
  override def batchCheckGraphExistence(graphUris: List[String]): Future[Map[String, Boolean]] = {
    if (graphUris.isEmpty) {
      Future.successful(Map.empty)
    } else {
      // Create efficient SPARQL query to check multiple graphs at once
      val graphValues = graphUris.map(uri => s"<$uri>").mkString(" ")
      val batchQuery = s"""
        |SELECT ?graphUri ?hasData WHERE {
        |  VALUES ?graphUri { $graphValues }
        |  OPTIONAL { 
        |    GRAPH ?graphUri { 
        |      ?s ?p ?o 
        |    } 
        |  }
        |  BIND(BOUND(?s) AS ?hasData)
        |}
      """.stripMargin
      
      executeWithMetrics(s"Batch existence check for ${graphUris.length} graphs") {
        val request = queryRequest(batchQuery)
        request.get().map { response =>
          if (response.status / 100 != 2) {
            response.status match {
              case 401 =>
                logger.error("Fuseki authentication failed for batch existence query. Check credentials.")
                throw new TripleStoreException(s"Unauthorized batch query. Status: ${response.status}")
              case 403 =>
                logger.error("Fuseki access forbidden for batch existence query.")
                throw new TripleStoreException(s"Forbidden batch query. Status: ${response.status}")
              case _ =>
                throw new RuntimeException(s"Batch query response not 2XX, but ${response.status}: ${response.statusText}")
            }
          }
          
          val json = response.json
          val bindings = (json \ "results" \ "bindings").as[List[JsObject]]
          
          // Convert results to Map[graphUri -> exists]
          val results = bindings.map { binding =>
            val graphUri = (binding \ "graphUri" \ "value").as[String]
            val hasData = (binding \ "hasData" \ "value").asOpt[String].contains("true")
            graphUri -> hasData
          }.toMap
          
          // Ensure all requested URIs are in result (missing ones = false)
          graphUris.map(uri => uri -> results.getOrElse(uri, false)).toMap
        }
      }
    }
  }

  val up = new FusekiUpdate

  class FusekiUpdate extends TripleStoreUpdate {

    private def checkUpdateResponse(response: WSResponse, logString: String): Unit = if (response.status / 100 != 2) {
      response.status match {
        case 401 =>
          logger.error("Fuseki authentication failed. Check fuseki-username and fuseki-password configuration.")
          throw new TripleStoreException(s"Unauthorized access to Fuseki. Status: ${response.status}")
        case 403 =>
          logger.error("Fuseki access forbidden. User may not have required permissions.")
          throw new TripleStoreException(s"Forbidden access to Fuseki. Status: ${response.status}")
        case _ =>
          logger.error(s"Fuseki request failed with status ${response.status}: $logString")
          throw new TripleStoreException(s"${response.statusText}: ${response.body}")
      }
    }

    override def sparqlUpdate(sparqlUpdate: String) = {
      logSparql(sparqlUpdate)
      executeWithMetrics("SPARQL UPDATE") {
        val request = updateRequest()
        request.post(sparqlUpdate).map(checkUpdateResponse(_, sparqlUpdate))
      }
    }

    override def dataPost(graphUri: String, model: Model) = {
      val sw = new StringWriter()
      model.write(sw, "TURTLE")
      val turtle = sw.toString
      logSparql(turtle)
      executeWithMetrics(s"POST graph $graphUri") {
        dataRequest(graphUri).withHeaders(
          "Content-Type" -> "text/turtle; charset=utf-8"
        ).post(turtle).map(checkUpdateResponse(_, turtle))
      }
    }

    override def dataPutXMLFile(graphUri: String, file: File) = {
      logger.debug(s"Putting $graphUri")
      executeWithMetrics(s"PUT XML file to $graphUri") {
        dataRequest(graphUri).withHeaders(
          "Content-Type" -> "application/rdf+xml; charset=utf-8"
        ).put(file).map(checkUpdateResponse(_, graphUri))
      }
    }

    override def dataPutGraph(graphUri: String, model: Model) = {
      val sw = new StringWriter()
      model.write(sw, "TURTLE")
      val turtle = sw.toString
      logger.debug(s"Putting $graphUri")
      executeWithMetrics(s"PUT graph $graphUri") {
        dataRequest(graphUri).withHeaders(
          "Content-Type" -> "text/turtle; charset=utf-8"
        ).put(turtle).map(checkUpdateResponse(_, turtle))
      }
    }

  }

}


