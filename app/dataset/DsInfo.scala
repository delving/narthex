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

package dataset

import java.io.StringWriter

import com.hp.hpl.jena.rdf.model._
import harvest.Harvesting.{HarvestCron, HarvestType}
import org.OrgActor.DatasetMessage
import org.apache.jena.riot.{RDFDataMgr, RDFFormat}
import org.joda.time.DateTime
import play.api.libs.json.{Json, Writes}
import services.NarthexConfig._
import services.StringHandling.urlEncodeValue
import services.Temporal._
import triplestore.TripleStore
import triplestore.TripleStore._

import scala.collection.JavaConversions._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent._
import scala.concurrent.duration._

object DsInfo {

  case class DICharacter(name: String) {
    val uri = s"$NX_NAMESPACE$name"
  }

  val characterMapped = DICharacter("characterMapped")
  val characterSkos = DICharacter("characterSkos")
  val characterSkosified = DICharacter("characterSkosified")

  case class DIProp(name: String, dataType: PropType = stringProp) {
    val uri = s"$NX_NAMESPACE$name"
  }

  val datasetActor = DIProp("datasetActor")
  val datasetCharacter = DIProp("datasetCharacter")

  val datasetSpec = DIProp("datasetSpec")
  val datasetName = DIProp("datasetName")
  val datasetDescription = DIProp("datasetDescription")
  val datasetOwner = DIProp("datasetOwner")
  val datasetLanguage = DIProp("datasetLanguage")
  val datasetRights = DIProp("datasetRights")

  val datasetMapToPrefix = DIProp("datasetMapTo")

  val datasetRecordCount = DIProp("datasetRecordCount", intProp)
  val datasetErrorTime = DIProp("datasetErrorTime")
  val datasetErrorMessage = DIProp("datasetErrorMessage")

  val skosField = DIProp("skosField", uriProp)

  val stateRaw = DIProp("stateRaw", timeProp)
  val stateRawAnalyzed = DIProp("stateRawAnalyzed", timeProp)
  val stateSource = DIProp("stateSource", timeProp)
  val stateMappable = DIProp("stateMappable", timeProp)
  val stateProcessable = DIProp("stateProcessable", timeProp)
  val stateProcessed = DIProp("stateProcessed", timeProp)
  val stateAnalyzed = DIProp("stateAnalyzed", timeProp)
  val stateSaved = DIProp("stateSaved", timeProp)

  val harvestType = DIProp("harvestType")
  val harvestURL = DIProp("harvestURL")
  val harvestDataset = DIProp("harvestDataset")
  val harvestPrefix = DIProp("harvestPrefix")
  val harvestSearch = DIProp("harvestSearch")
  val harvestPreviousTime = DIProp("harvestPreviousTime", timeProp)
  val harvestDelay = DIProp("harvestDelay")
  val harvestDelayUnit = DIProp("harvestDelayUnit")

  val processedValid = DIProp("processedValid", intProp)
  val processedInvalid = DIProp("processedInvalid", intProp)

  val publishOAIPMH = DIProp("publishOAIPMH", booleanProp)
  val publishIndex = DIProp("publishIndex", booleanProp)
  val publishLOD = DIProp("publishLOD", booleanProp)
  val categoriesInclude = DIProp("categoriesInclude", booleanProp)

  case class DsState(prop: DIProp) {
    override def toString = prop.name
  }

  object DsState {
    val RAW = DsState(stateRaw)
    val RAW_ANALYZED = DsState(stateRawAnalyzed)
    val SOURCED = DsState(stateSource)
    val MAPPABLE = DsState(stateMappable)
    val PROCESSABLE = DsState(stateProcessable)
    val PROCESSED = DsState(stateProcessed)
    val ANALYZED = DsState(stateAnalyzed)
    val SAVED = DsState(stateSaved)
  }

  case class DsMetadata(name: String,
                        description: String,
                        owner: String,
                        language: String,
                        rights: String)

  implicit val dsInfoWrites = new Writes[DsInfo] {
    def writes(dsInfo: DsInfo) = {
      val out = new StringWriter()
      RDFDataMgr.write(out, dsInfo.m, RDFFormat.JSONLD_FLAT)
      Json.parse(out.toString)
    }
  }

  def listDsInfo(ts: TripleStore): Future[List[DsInfo]] = {
    val q =
      s"""
         |SELECT ?spec
         |WHERE {
         |  GRAPH ?g {
         |    ?s <${datasetSpec.uri}> ?spec
         |  }
         |}
         |ORDER BY ?spec
       """.stripMargin
    ts.query(q).map{ list =>
      list.map { entry =>
        val spec = entry("spec")
        new DsInfo(spec, ts)
      }
    }
  }
}

class DsInfo(val spec: String, ts: TripleStore) {

  import dataset.DsInfo._

  def now: String = timeToString(new DateTime())

  val datasetUri = s"$NX_URI_PREFIX/dataset/${urlEncodeValue(spec)}"

  // could cache as well so that the get happens less
  val futureModel = ts.dataGet(datasetUri).fallbackTo {
    val m = ModelFactory.createDefaultModel()
    val uri = m.getResource(datasetUri)
    val propUri = m.getProperty(datasetSpec.uri)
    m.add(uri, propUri, m.createLiteral(spec))
    ts.dataPost(datasetUri, m).map(ok => m)
  }

  val m: Model = Await.result(futureModel, 20.seconds)

  def create(datasetCharacterValue: DICharacter): Future[Model] = {
    val datasetResource = m.getResource(datasetUri)
    val characterResource = m.getResource(datasetCharacterValue.uri)
    m.add(datasetResource, m.getProperty(datasetCharacter.uri), characterResource)
    ts.dataPost(datasetUri, m).map(ok => m)
  }

  def exists: Boolean = getLiteralProp(datasetName).isDefined

  def getLiteralProp(prop: DIProp): Option[String] = {
    val uri = m.getResource(datasetUri)
    val propUri = m.getProperty(prop.uri)
    val objects = m.listObjectsOfProperty(uri, propUri)
    if (objects.hasNext) Some(objects.next().asLiteral().getString) else None
  }

  def getTimeProp(prop: DIProp): Option[DateTime] = getLiteralProp(prop).map(stringToTime)

  def getBooleanProp(prop: DIProp) = getLiteralProp(prop).exists(_ == "true")

  def setLiteralProps(tuples: (DIProp, String)*): Future[Model] = {
    val uri = m.getResource(datasetUri)
    val propVal = tuples.map(t => (m.getProperty(t._1.uri), t._2))
    val sparqlPerProp = propVal.map { pv =>
      val propUri = pv._1
      s"""
         |WITH <$datasetUri>
         |DELETE { 
         |   <$uri> <$propUri> ?o 
         |}
         |INSERT { 
         |   <$uri> <$propUri> "${pv._2}" 
         |}
         |WHERE { 
         |   OPTIONAL {
         |      <$uri> <$propUri> ?o
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

  def removeLiteralProp(prop: DIProp): Future[Model] = {
    val uri = m.getResource(datasetUri)
    val propUri = m.getProperty(prop.uri)
    val sparql =
      s"""
         |WITH <$datasetUri>
         |DELETE {
         |   <$uri> <$propUri> ?o
         |}
         |WHERE {
         |   <$uri> <$propUri> ?o
         |}
       """.stripMargin
    ts.update(sparql).map { ok =>
      m.removeAll(uri, propUri, null)
      m
    }
  }

  def getUriProps(prop: DIProp): List[String] = {
    val uri = m.getResource(datasetUri)
    val propUri = m.getProperty(prop.uri)
    m.listObjectsOfProperty(uri, propUri).map(node => node.asResource().toString).toList
  }

  def setUriProp(prop: DIProp, uriValue: String): Future[Model] = {
    val uri = m.getResource(datasetUri)
    val propUri = m.getProperty(prop.uri)
    val uriValueUri = m.getResource(uriValue)
    val sparql = s"""
         |INSERT DATA {
         |   GRAPH <$datasetUri> {
         |      <$uri> <$propUri> <$uriValueUri>
         |   }
         |}
       """.stripMargin.trim
    ts.update(sparql).map { ok =>
      m.add(uri, propUri, uriValueUri)
      m
    }
  }

  def removeUriProp(prop: DIProp, uriValue: String): Future[Model] = futureModel.flatMap { m =>
    val uri = m.getResource(datasetUri)
    val propUri = m.getProperty(prop.uri)
    val uriValueUri = m.getProperty(uriValue)
    val sparql =
      s"""
         |DELETE DATA FROM <$datasetUri> {
         |   <$uri> <$propUri> <$uriValueUri>
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
         |   GRAPH <$datasetUri> {
         |      <$datasetUri> ?p ?o
         |   }
         |}
         |WHERE {
         |   GRAPH <$datasetUri> {
         |      <$datasetUri> ?p ?o
         |   }
         |}
       """.stripMargin
    ts.update(sparql).map { ok =>
      true
    }
  }

  // from the old datasetdb

  def setState(state: DsState) = setLiteralProps(state.prop -> now)

  def removeState(state: DsState) = removeLiteralProp(state.prop)

  def setError(message: String) = setLiteralProps(
    datasetErrorMessage -> message,
    datasetErrorTime -> now
  )

  def setRecordCount(count: Int) = setLiteralProps(datasetRecordCount -> count.toString)

  def setProcessedRecordCounts(validCount: Int, invalidCount: Int) = setLiteralProps(
    processedValid -> validCount.toString,
    processedInvalid -> invalidCount.toString
  )

  def setHarvestInfo(harvestTypeEnum: HarvestType, url: String, dataset: String, prefix: String) = setLiteralProps(
    harvestType -> harvestTypeEnum.name,
    harvestURL -> url,
    harvestDataset -> dataset,
    harvestPrefix -> prefix
  )

  def setHarvestCron(harvestCron: HarvestCron) = setLiteralProps(
    harvestPreviousTime -> timeToString(harvestCron.previous),
    harvestDelay -> harvestCron.delay.toString,
    harvestDelayUnit -> harvestCron.unit.toString
  )

  def setPublication(publishOaiPmhString: String, publishIndexString: String, publishLoDString: String) = setLiteralProps(
    publishOAIPMH -> publishOaiPmhString,
    publishIndex -> publishIndexString,
    publishLOD -> publishLoDString
  )

  def setCategories(included: String) = setLiteralProps(categoriesInclude -> included)

  def setMetadata(metadata: DsMetadata) = setLiteralProps(
    datasetName -> metadata.name,
    datasetDescription -> metadata.description,
    datasetOwner -> metadata.owner,
    datasetLanguage -> metadata.language,
    datasetRights -> metadata.rights
  )

  def harvestCron = {
    (getLiteralProp(harvestPreviousTime), getLiteralProp(harvestDelay), getLiteralProp(harvestDelayUnit)) match {
      case (Some(previousString), Some(delayString), Some(unitString)) =>
        HarvestCron(
          previous = stringToTime(previousString),
          delay = delayString.toInt,
          unit = DelayUnit.fromString(unitString).getOrElse(DelayUnit.WEEKS)
        )
      case _ =>
        HarvestCron(new DateTime(), 1, DelayUnit.WEEKS)
    }
  }

  // for actors

  def createMessage(payload: AnyRef, question: Boolean = false) = DatasetMessage(spec, payload, question)

  override def toString = spec
}
