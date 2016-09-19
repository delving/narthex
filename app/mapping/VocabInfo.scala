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

import org.apache.jena.rdf.model._
import org.apache.jena.riot.{RDFDataMgr, RDFFormat}
import org.joda.time.DateTime
import org.{OrgContext, User}
import play.api.Logger
import play.api.libs.json.{JsValue, Json, Writes}
import services.StringHandling.{createGraphName, urlEncodeValue}
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
      RDFDataMgr.write(out, dsInfo.model, RDFFormat.JSONLD_FLAT)
      Json.parse(out.toString)
    }
  }

  def listVocabInfo(orgContext: OrgContext)(implicit ec: ExecutionContext, ts: TripleStore): Future[List[VocabInfo]] = {
    ts.query(listVocabInfoQ).map { list =>
      list.map(entry => new VocabInfo(spec = entry("spec").text, orgContext))
    }
  }

  def getVocabInfoUri(spec: String, orgContext: OrgContext) = s"${orgContext.appConfig.nxUriPrefix}/skos-info/${urlEncodeValue(spec)}"

  def getGraphName(spec: String, orgContext: OrgContext) = createGraphName(getVocabInfoUri(spec, orgContext))

  def getVocabGraphName(spec: String, orgContext: OrgContext) = createGraphName(s"${orgContext.appConfig.nxUriPrefix}/skos/${urlEncodeValue(spec)}")

  def createVocabInfo(owner: User, spec: String, orgContext: OrgContext)(implicit ec: ExecutionContext, ts: TripleStore): Future[VocabInfo] = {
    val m = ModelFactory.createDefaultModel()
    val subject = m.getResource(getVocabInfoUri(spec, orgContext))
    m.add(subject, m.getProperty(rdfType), m.getResource(skosCollection))
    m.add(subject, m.getProperty(skosSpec.uri), m.createLiteral(spec))
    m.add(subject, m.getProperty(actorOwner.uri), m.createResource(owner.uri(orgContext.appConfig.nxUriPrefix)))
    ts.up.dataPost(getGraphName(spec, orgContext), m).map(ok => new VocabInfo(spec, orgContext))
  }

  def freshVocabInfo(spec: String, orgContext: OrgContext)(implicit ec: ExecutionContext, ts: TripleStore): Future[Option[VocabInfo]] = {
    val infoUri = getVocabInfoUri(spec, orgContext)
    ts.ask(checkVocabQ(infoUri)).map(answer => if (answer) Some(new VocabInfo(spec, orgContext)) else None)
  }

  def withVocabInfo[T](spec: String, orgContext: OrgContext)(block: VocabInfo => T)(implicit ec: ExecutionContext, ts: TripleStore) = {
    val cacheName = getVocabInfoUri(spec, orgContext)
    orgContext.cacheApi.get[VocabInfo](cacheName) map { vocabInfo =>
      block(vocabInfo)
    } getOrElse {
      val vocabInfo = Await.result(freshVocabInfo(spec, orgContext), patience).getOrElse {
        throw new RuntimeException(s"No skos info for $spec")
      }
      orgContext.cacheApi.set(cacheName, vocabInfo, cacheTime)
      block(vocabInfo)
    }
  }
}

class VocabInfo(val spec: String, val orgContext: OrgContext)(implicit ec: ExecutionContext, ts: TripleStore) extends SkosGraph {

  import mapping.VocabInfo._

  def now: String = timeToString(new DateTime())

  val uri = getVocabInfoUri(spec, orgContext)

  val graphName = getGraphName(spec, orgContext)

  val skosified = false

  val skosGraphName = getVocabGraphName(spec, orgContext)

  // could cache as well so that the get happens less
  def futureModel = ts.dataGet(graphName)
  futureModel.onFailure {
    case e: Throwable => Logger.warn(s"No data found for dataset $spec", e)
  }

  def model = Await.result(futureModel, patience)

  def getLiteralProp(prop: NXProp): Option[String] = {
    val m = model
    val res = m.getResource(uri)
    val objects = m.listObjectsOfProperty(res, m.getProperty(prop.uri))
    if (objects.hasNext) Some(objects.next().asLiteral().getString) else None
  }

  def getTimeProp(prop: NXProp): Option[DateTime] = getLiteralProp(prop).map(stringToTime)

  def getBooleanProp(prop: NXProp) = getLiteralProp(prop).contains("true")

  def setSingularLiteralProps(propVal: (NXProp, String)*): Unit = {
    val sparqlPerProp = propVal.map(pv => updatePropertyQ(graphName, uri, pv._1, pv._2))
    val sparql = sparqlPerProp.mkString(";\n")
    val futureUpdate = ts.up.sparqlUpdate(sparql)
    Await.ready(futureUpdate, patience)
  }

  def removeLiteralProp(prop: NXProp): Unit = {
    val futureUpdate = ts.up.sparqlUpdate(removeLiteralPropertyQ(graphName, uri, prop))
    Await.ready(futureUpdate, patience)
  }

  def conceptCount = {
    for (
      cqList <- ts.query(getVocabStatisticsQ(skosGraphName));
      c = cqList.head("count")
    ) yield  c.text.toInt
  }

  def dropVocabulary = {
    ts.up.sparqlUpdate(dropVocabularyQ(graphName, skosGraphName, uri)).map(ok => true)
  }

  lazy val vocabulary = new SkosVocabulary(spec, skosGraphName)

  override def toString = uri
}
