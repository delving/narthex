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

import java.io.StringWriter

import com.hp.hpl.jena.rdf.model._
import org.ActorStore.NXActor
import org.OrgRepo
import org.apache.jena.riot.{RDFDataMgr, RDFFormat}
import org.joda.time.DateTime
import play.api.Logger
import play.api.Play.current
import play.api.cache.Cache
import play.api.libs.json.{JsValue, Json, Writes}
import services.NarthexConfig._
import services.StringHandling.urlEncodeValue
import services.Temporal._
import triplestore.GraphProperties._
import triplestore.TripleStore

import scala.collection.JavaConversions._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent._
import scala.concurrent.duration._

object SkosInfo {

  case class DsMetadata(name: String,
                        description: String,
                        owner: String,
                        language: String,
                        rights: String)

  implicit val skosInfoWrites = new Writes[SkosInfo] {
    def writes(dsInfo: SkosInfo): JsValue = {
      val out = new StringWriter()
      RDFDataMgr.write(out, dsInfo.m, RDFFormat.JSONLD_FLAT)
      Json.parse(out.toString)
    }
  }

  def listSkosInfo(ts: TripleStore): Future[List[SkosInfo]] = {
    val q =
      s"""
         |SELECT ?spec
         |WHERE {
         |  GRAPH ?g {
         |    ?s <${skosSpec.uri}> ?spec .
         |  }
         |}
         |ORDER BY ?spec
       """.stripMargin
    ts.query(q).map { list =>
      list.map { entry =>

        Logger.warn(s"skos entry: $entry")


        val spec = entry("spec").text
        new SkosInfo(spec, ts)
      }
    }
  }

  def getInfoUri(spec: String) = s"$NX_URI_PREFIX/skos-info/${urlEncodeValue(spec)}"

  def getDataUri(spec: String) = s"$NX_URI_PREFIX/skos/${urlEncodeValue(spec)}"

  def create(owner: NXActor, spec: String, ts: TripleStore): Future[SkosInfo] = {
    val m = ModelFactory.createDefaultModel()
    val uri = m.getResource(getInfoUri(spec))
    m.add(uri, m.getProperty(skosSpec.uri), m.createLiteral(spec))
    m.add(uri, m.getProperty(actorOwner.uri), m.createResource(owner.uri))
    ts.dataPost(uri.getURI, m).map(ok => new SkosInfo(spec, ts))
  }

  def withSkosInfo[T](spec: String)(block: SkosInfo => T) = {
    val cacheName = getInfoUri(spec)
    Cache.getAs[SkosInfo](cacheName) map { skosInfo =>
      block(skosInfo)
    } getOrElse {
      val skosInfo = Await.result(check(spec, OrgRepo.repo.ts), 10.seconds).getOrElse{
        throw new RuntimeException(s"No skos info for $spec")
      }
      Cache.set(cacheName, skosInfo, 5.minutes)
      block(skosInfo)
    }
  }

  def check(spec: String, ts: TripleStore): Future[Option[SkosInfo]] = {
    val skosUri = getInfoUri(spec)
    val q =
      s"""
         |ASK {
         |   GRAPH ?g {
         |       <$skosUri> <${skosSpec.uri}> ?spec .
         |   }
         |}
       """.stripMargin
    ts.ask(q).map(answer => if (answer) Some(new SkosInfo(spec, ts)) else None)
  }
}

class SkosInfo(val spec: String, val ts: TripleStore) {

  import mapping.SkosInfo._

  def now: String = timeToString(new DateTime())

  val uri = getInfoUri(spec)
  
  val dataUri = getDataUri(spec)

  // could cache as well so that the get happens less
  lazy val futureModel = ts.dataGet(uri)
  futureModel.onFailure {
    case e: Throwable => Logger.warn(s"No data found for dataset $spec", e)
  }
  lazy val m: Model = Await.result(futureModel, 20.seconds)
  lazy val uriResource = m.getResource(uri)

  def getLiteralProp(prop: SIProp): Option[String] = {
    val objects = m.listObjectsOfProperty(uriResource, m.getProperty(prop.uri))
    if (objects.hasNext) Some(objects.next().asLiteral().getString) else None
  }

  def getTimeProp(prop: SIProp): Option[DateTime] = getLiteralProp(prop).map(stringToTime)

  def getBooleanProp(prop: SIProp) = getLiteralProp(prop).exists(_ == "true")

  def setSingularLiteralProps(tuples: (SIProp, String)*): Future[Model] = {
    val propVal = tuples.map(t => (m.getProperty(t._1.uri), t._2))
    val sparqlPerProp = propVal.map { pv =>
      val propUri = pv._1
      s"""
         |WITH <$uri>
         |DELETE { 
         |   <$uri> <$propUri> ?o .
         |}
         |INSERT { 
         |   <$uri> <$propUri> "${pv._2}" .
         |}
         |WHERE { 
         |   OPTIONAL {
         |      <$uri> <$propUri> ?o .
         |   } 
         |}
       """.stripMargin.trim
    }
    val sparql = sparqlPerProp.mkString(";\n")
    ts.update(sparql).map { ok =>
      propVal.foreach { pv =>
        m.removeAll(uriResource, pv._1, null)
        m.add(uriResource, pv._1, m.createLiteral(pv._2))
      }
      m
    }
  }

  def removeLiteralProp(prop: SIProp): Future[Model] = {
    val propUri = m.getProperty(prop.uri)
    val sparql =
      s"""
         |WITH <$uri>
         |DELETE {
         |   <$uri> <$propUri> ?o .
         |}
         |WHERE {
         |   <$uri> <$propUri> ?o .
         |}
       """.stripMargin
    ts.update(sparql).map { ok =>
      m.removeAll(uriResource, propUri, null)
      m
    }
  }

  def getUriPropValueList(prop: SIProp): List[String] = {
    val propUri = m.getProperty(prop.uri)
    m.listObjectsOfProperty(uriResource, propUri).map(node => node.asResource().toString).toList
  }

  def addUriProp(prop: SIProp, uriValue: String): Future[Model] = {
    val propUri = m.getProperty(prop.uri)
    val uriValueUri = m.getResource(uriValue)
    val sparql = s"""
         |INSERT DATA {
         |   GRAPH <$uri> {
         |      <$uri> <$propUri> <$uriValueUri> .
         |   }
         |}
       """.stripMargin.trim
    ts.update(sparql).map { ok =>
      m.add(uriResource, propUri, uriValueUri)
      m
    }
  }

  def removeUriProp(prop: SIProp, uriValue: String): Future[Model] = futureModel.flatMap { m =>
    val propUri = m.getProperty(prop.uri)
    val uriValueUri = m.getProperty(uriValue)
    val sparql =
      s"""
         |DELETE DATA FROM <$uri> {
         |   <$uri> <$propUri> <$uriValueUri> .
         |}
       """.stripMargin
    ts.update(sparql).map { ok =>
      m.remove(uriResource, propUri, uriValueUri)
      m
    }
  }

  def dropDataset = {
    // todo: delete the data too!
    val sparql =
      s"""
         |DELETE {
         |   GRAPH <$uri> {
         |      <$uri> ?p ?o .
         |   }
         |}
         |WHERE {
         |   GRAPH <$uri> {
         |      <$uri> ?p ?o .
         |   }
         |}
       """.stripMargin
    ts.update(sparql).map { ok =>
      true
    }
  }

  def getStatistics = {
    val countQuery =
      s"""
         |PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
         |PREFIX skos: <http://www.w3.org/2004/02/skos/core#>
         |SELECT (count(?s) as ?count)
         |WHERE {
         |    GRAPH <$dataUri> {
         |       ?s rdf:type skos:Concept .
         |    }
         |}
       """.stripMargin
    for (
      cqList <- ts.query(countQuery);
      c = cqList.head("count")
    ) yield Map(
      "conceptCount" -> c.text.toInt
    )
  }

  lazy val vocabulary = new SkosVocabulary(this)

  override def toString = uri
}
