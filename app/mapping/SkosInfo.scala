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
import org.apache.jena.riot.{RDFDataMgr, RDFFormat}
import org.joda.time.DateTime
import play.api.Logger
import play.api.libs.json.{JsValue, Json, Writes}
import services.NarthexConfig._
import services.StringHandling.urlEncodeValue
import services.Temporal._
import triplestore.TripleStore
import triplestore.TripleStore._

import scala.collection.JavaConversions._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent._
import scala.concurrent.duration._

object SkosInfo {

  var allProps = Map.empty[String, SIProp]

  case class SIProp(name: String, dataType: PropType = stringProp) {
    val uri = s"$NX_NAMESPACE$name"
    allProps = allProps + (name -> this)
  }

  val skosSpec = SIProp("skosSpec")
  val skosName = SIProp("skosName")
  val skosOwner = SIProp("skosName", uriProp)

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

  def listDsInfo(ts: TripleStore): Future[List[SkosInfo]] = {
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
        val spec = entry("spec")
        new SkosInfo(spec, ts)
      }
    }
  }

  def getSkosUri(spec: String) = s"$NX_URI_PREFIX/dataset/${urlEncodeValue(spec)}"

  def create(spec: String, ts: TripleStore): Future[SkosInfo] = {
    val m = ModelFactory.createDefaultModel()
    val uri = m.getResource(getSkosUri(spec))
    m.add(uri, m.getProperty(skosSpec.uri), m.createLiteral(spec))
    ts.dataPost(uri.getURI, m).map(ok => new SkosInfo(spec, ts))
  }

  def check(spec: String, ts: TripleStore): Future[Option[SkosInfo]] = {
    val dsUri = getSkosUri(spec)
    val q =
      s"""
         |ASK {
         |   GRAPH <$dsUri> {
         |       <$dsUri> <${skosSpec.uri}> ?spec .
         |   }
         |}
       """.stripMargin
    ts.ask(q).map(answer => if (answer) Some(new SkosInfo(spec, ts)) else None)
  }
}

class SkosInfo(val spec: String, ts: TripleStore) {

  import mapping.SkosInfo._

  def now: String = timeToString(new DateTime())

  val skosUri = getSkosUri(spec)

  // could cache as well so that the get happens less
  lazy val futureModel = ts.dataGet(skosUri)
  futureModel.onFailure {
    case e: Throwable => Logger.warn(s"No data found for dataset $spec", e)
  }
  lazy val m: Model = Await.result(futureModel, 20.seconds)
  lazy val uri = m.getResource(skosUri)

  def getLiteralProp(prop: SIProp): Option[String] = {
    val objects = m.listObjectsOfProperty(uri, m.getProperty(prop.uri))
    if (objects.hasNext) Some(objects.next().asLiteral().getString) else None
  }

  def getTimeProp(prop: SIProp): Option[DateTime] = getLiteralProp(prop).map(stringToTime)

  def getBooleanProp(prop: SIProp) = getLiteralProp(prop).exists(_ == "true")

  def setSingularLiteralProps(tuples: (SIProp, String)*): Future[Model] = {
    val propVal = tuples.map(t => (m.getProperty(t._1.uri), t._2))
    val sparqlPerProp = propVal.map { pv =>
      val propUri = pv._1
      s"""
         |WITH <$skosUri>
         |DELETE { 
         |   <$skosUri> <$propUri> ?o .
         |}
         |INSERT { 
         |   <$skosUri> <$propUri> "${pv._2}" .
         |}
         |WHERE { 
         |   OPTIONAL {
         |      <$skosUri> <$propUri> ?o .
         |   } 
         |}
       """.stripMargin.trim
    }
    val sparql = sparqlPerProp.mkString(";\n")
    ts.update(sparql).map { ok =>
      propVal.foreach { pv =>
        m.removeAll(uri, pv._1, null)
        m.add(uri, pv._1, m.createLiteral(pv._2))
      }
      m
    }
  }

  def removeLiteralProp(prop: SIProp): Future[Model] = {
    val propUri = m.getProperty(prop.uri)
    val sparql =
      s"""
         |WITH <$skosUri>
         |DELETE {
         |   <$skosUri> <$propUri> ?o .
         |}
         |WHERE {
         |   <$skosUri> <$propUri> ?o .
         |}
       """.stripMargin
    ts.update(sparql).map { ok =>
      m.removeAll(uri, propUri, null)
      m
    }
  }

  def getUriPropValueList(prop: SIProp): List[String] = {
    val propUri = m.getProperty(prop.uri)
    m.listObjectsOfProperty(uri, propUri).map(node => node.asResource().toString).toList
  }

  def addUriProp(prop: SIProp, uriValue: String): Future[Model] = {
    val propUri = m.getProperty(prop.uri)
    val uriValueUri = m.getResource(uriValue)
    val sparql = s"""
         |INSERT DATA {
         |   GRAPH <$skosUri> {
         |      <$skosUri> <$propUri> <$uriValueUri> .
         |   }
         |}
       """.stripMargin.trim
    ts.update(sparql).map { ok =>
      m.add(uri, propUri, uriValueUri)
      m
    }
  }

  def removeUriProp(prop: SIProp, uriValue: String): Future[Model] = futureModel.flatMap { m =>
    val propUri = m.getProperty(prop.uri)
    val uriValueUri = m.getProperty(uriValue)
    val sparql =
      s"""
         |DELETE DATA FROM <$skosUri> {
         |   <$skosUri> <$propUri> <$uriValueUri> .
         |}
       """.stripMargin
    ts.update(sparql).map { ok =>
      m.remove(uri, propUri, uriValueUri)
      m
    }
  }

  def dropDataset = {
    val sparql =
      s"""
         |DELETE {
         |   GRAPH <$skosUri> {
         |      <$skosUri> ?p ?o .
         |   }
         |}
         |WHERE {
         |   GRAPH <$skosUri> {
         |      <$skosUri> ?p ?o .
         |   }
         |}
       """.stripMargin
    ts.update(sparql).map { ok =>
      true
    }
  }

  override def toString = spec
}
