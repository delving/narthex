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
import org.OrgContext.NX_URI_PREFIX
import org.apache.jena.riot.{RDFDataMgr, RDFFormat}
import org.joda.time.DateTime
import play.api.Logger
import play.api.Play.current
import play.api.cache.Cache
import play.api.libs.json.{JsValue, Json, Writes}
import services.StringHandling.urlEncodeValue
import services.Temporal._
import triplestore.GraphProperties._
import triplestore.Sparql._
import triplestore.{SkosGraph, TripleStore}

import scala.concurrent._
import scala.concurrent.duration._

object VocabInfo {

  val CATEGORIES_SPEC = "categories"

  val patience = 1.minute

  val cacheTime = 10.minutes

  case class DsMetadata(name: String,
                        description: String,
                        owner: String,
                        language: String,
                        rights: String)

  implicit val vocabInfoWrites = new Writes[VocabInfo] {
    def writes(dsInfo: VocabInfo): JsValue = {
      val out = new StringWriter()
      RDFDataMgr.write(out, dsInfo.m, RDFFormat.JSONLD_FLAT)
      Json.parse(out.toString)
    }
  }

  def listVocabInfo(implicit ec: ExecutionContext, ts: TripleStore): Future[List[VocabInfo]] = {
    ts.query(listVocabInfoQ).map { list =>
      list.map(entry => new VocabInfo(spec = entry("spec").text))
    }
  }

  def getVocabInfoUri(spec: String) = s"$NX_URI_PREFIX/skos-info/${urlEncodeValue(spec)}"

  def getVocabDataUri(spec: String) = s"$NX_URI_PREFIX/skos/${urlEncodeValue(spec)}"

  def createVocabInfo(owner: NXActor, spec: String)(implicit ec: ExecutionContext, ts: TripleStore): Future[VocabInfo] = {
    val m = ModelFactory.createDefaultModel()
    val uri = m.getResource(getVocabInfoUri(spec))
    m.add(uri, m.getProperty(rdfType), m.getResource(skosCollection))
    m.add(uri, m.getProperty(skosSpec.uri), m.createLiteral(spec))
    m.add(uri, m.getProperty(actorOwner.uri), m.createResource(owner.uri))
    ts.up.dataPost(uri.getURI, m).map(ok => new VocabInfo(spec))
  }

  def freshVocabInfo(spec: String)(implicit ec: ExecutionContext, ts: TripleStore): Future[Option[VocabInfo]] = {
    val infoUri = getVocabInfoUri(spec)
    ts.ask(checkVocabQ(infoUri)).map(answer => if (answer) Some(new VocabInfo(spec)) else None)
  }

  def withVocabInfo[T](spec: String)(block: VocabInfo => T)(implicit ec: ExecutionContext, ts: TripleStore) = {
    val cacheName = getVocabInfoUri(spec)
    Cache.getAs[VocabInfo](cacheName) map { vocabInfo =>
      block(vocabInfo)
    } getOrElse {
      val vocabInfo = Await.result(freshVocabInfo(spec), patience).getOrElse {
        throw new RuntimeException(s"No skos info for $spec")
      }
      Cache.set(cacheName, vocabInfo, cacheTime)
      block(vocabInfo)
    }
  }
}

class VocabInfo(val spec: String)(implicit ec: ExecutionContext, ts: TripleStore) extends SkosGraph {

  import mapping.VocabInfo._

  def now: String = timeToString(new DateTime())

  val uri = getVocabInfoUri(spec)

  val skosified = false

  val dataUri = getVocabDataUri(spec)

  // could cache as well so that the get happens less
  lazy val futureModel = ts.dataGet(uri)
  futureModel.onFailure {
    case e: Throwable => Logger.warn(s"No data found for dataset $spec", e)
  }
  lazy val m: Model = Await.result(futureModel, 20.seconds)
  lazy val uriResource = m.getResource(uri)

  def getLiteralProp(prop: NXProp): Option[String] = {
    val objects = m.listObjectsOfProperty(uriResource, m.getProperty(prop.uri))
    if (objects.hasNext) Some(objects.next().asLiteral().getString) else None
  }

  def getTimeProp(prop: NXProp): Option[DateTime] = getLiteralProp(prop).map(stringToTime)

  def getBooleanProp(prop: NXProp) = getLiteralProp(prop).exists(_ == "true")

  def setSingularLiteralProps(propVal: (NXProp, String)*): Model = {
    val sparqlPerProp = propVal.map(pv => updatePropertyQ(uri, pv._1, pv._2))
    val sparql = sparqlPerProp.mkString(";\n")
    val futureUpdate = ts.up.sparqlUpdate(sparql).map { ok =>
      propVal.foreach { pv =>
        val prop = m.getProperty(pv._1.uri)
        m.removeAll(uriResource, prop, null)
        m.add(uriResource, prop, m.createLiteral(pv._2))
      }
    }
    Await.ready(futureUpdate, patience)
    m
  }

  def removeLiteralProp(prop: NXProp): Model = {
    val futureUpdate = ts.up.sparqlUpdate(removeLiteralPropertyQ(uri, prop)).map { ok =>
      m.removeAll(uriResource, m.getProperty(prop.uri), null)
    }
    Await.ready(futureUpdate, patience)
    m
  }

  def conceptCount = {
    for (
      cqList <- ts.query(getVocabStatisticsQ(dataUri));
      c = cqList.head("count")
    ) yield  c.text.toInt
  }

  def dropVocabulary = ts.up.sparqlUpdate(dropVocabularyQ(uri)).map(ok => true)

  lazy val vocabulary = new SkosVocabulary(spec, dataUri)

  override def toString = uri
}
