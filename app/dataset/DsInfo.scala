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
import mapping.SkosVocabulary
import org.ActorStore.NXActor
import org.OrgActor.DatasetMessage
import org.OrgContext._
import org.apache.jena.riot.{RDFDataMgr, RDFFormat}
import org.joda.time.DateTime
import play.api.Logger
import play.api.libs.json.{JsValue, Json, Writes}
import services.StringHandling.urlEncodeValue
import services.Temporal._
import triplestore.GraphProperties._
import triplestore.{SkosGraph, Sparql, TripleStore}

import scala.collection.JavaConversions._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent._
import scala.concurrent.duration._

object DsInfo extends Sparql {

  case class Character(name: String)

  val CharacterMapped = Character("character-mapped")

  def getCharacter(characterString: String) = List(CharacterMapped).find(_.name == characterString)

  case class DsState(prop: NXProp) {
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
                        aggregator: String,
                        owner: String,
                        language: String,
                        rights: String)

  implicit val dsInfoWrites = new Writes[DsInfo] {
    def writes(dsInfo: DsInfo): JsValue = {
      val out = new StringWriter()
      RDFDataMgr.write(out, dsInfo.m, RDFFormat.JSONLD_FLAT)
      Json.parse(out.toString)
    }
  }

  def listDsInfo(ts: TripleStore): Future[List[DsInfo]] = {
    ts.query(selectDatasetSpecsQ).map { list =>
      list.map { entry =>
        val spec = entry("spec").text
        new DsInfo(spec, ts)
      }
    }
  }

  def getDsUri(spec: String) = s"$NX_URI_PREFIX/dataset/${urlEncodeValue(spec)}"

  def getSkosUri(datasetUri: String) = s"$datasetUri/skos"

  def create(owner: NXActor, spec: String, character: Character, mapToPrefix: String, ts: TripleStore): Future[DsInfo] = {
    val m = ModelFactory.createDefaultModel()
    val uri = m.getResource(getDsUri(spec))
    m.add(uri, m.getProperty(rdfType), m.getResource(datasetEntity))
    m.add(uri, m.getProperty(datasetSpec.uri), m.createLiteral(spec))
    m.add(uri, m.getProperty(datasetCharacter.uri), m.createLiteral(character.name))
    m.add(uri, m.getProperty(actorOwner.uri), m.createResource(owner.uri))
    if (mapToPrefix != "-") m.add(uri, m.getProperty(datasetMapToPrefix.uri), m.createLiteral(mapToPrefix))
    ts.dataPost(uri.getURI, m).map(ok => new DsInfo(spec, ts))
  }

  def check(spec: String, ts: TripleStore): Future[Option[DsInfo]] = {
    // todo: cache these
    ts.ask(askIfDatasetExistsQ(getDsUri(spec))).map(answer =>
      if (answer)
        Some(new DsInfo(spec, ts))
      else
        None
    )
  }
}

class DsInfo(val spec: String, ts: TripleStore) extends SkosGraph {

  import dataset.DsInfo._

  def now: String = timeToString(new DateTime())

  val uri = getDsUri(spec)

  val skosified = true

  val skosUri = getSkosUri(uri)

  // could cache as well so that the get happens less
  lazy val futureModel = ts.dataGet(uri)
  futureModel.onFailure {
    case e: Throwable => Logger.warn(s"No data found for dataset $spec", e)
  }
  lazy val m: Model = Await.result(futureModel, 20.seconds)
  lazy val res = m.getResource(uri)

  def getLiteralProp(prop: NXProp): Option[String] = {
    val objects = m.listObjectsOfProperty(res, m.getProperty(prop.uri))
    if (objects.hasNext) Some(objects.next().asLiteral().getString) else None
  }

  def getTimeProp(prop: NXProp): Option[DateTime] = getLiteralProp(prop).map(stringToTime)

  def getBooleanProp(prop: NXProp) = getLiteralProp(prop).exists(_ == "true")

  def setSingularLiteralProps(propVals: (NXProp, String)*): Future[Model] = {
    val sparqlPerProp = propVals.map(pv => updatePropertyQ(uri, pv._1, pv._2))
    val sparql = sparqlPerProp.mkString(";\n")
    ts.update(sparql).map { ok =>
      propVals.foreach { pv =>
        val prop = m.getProperty(pv._1.uri)
        m.removeAll(res, prop, null)
        m.add(res, prop, m.createLiteral(pv._2))
      }
      m
    }
  }

  def removeLiteralProp(prop: NXProp): Future[Model] = {
    ts.update(removeLiteralPropertyQ(uri, prop)).map { ok =>
      m.removeAll(res, m.getProperty(prop.uri), null)
      m
    }
  }

  def getUriPropValueList(prop: NXProp): List[String] = {
    m.listObjectsOfProperty(res, m.getProperty(prop.uri)).map(node => 
      node.asLiteral().toString
    ).toList
  }

  def addUriProp(prop: NXProp, uriValueString: String): Future[Model] = {
    ts.update(addUriPropertyQ(uri, prop, uriValueString)).map { ok =>
      m.add(res, m.getProperty(prop.uri), m.createLiteral(uriValueString))
    }
  }

  def removeUriProp(prop: NXProp, uriValueString: String): Future[Model] = futureModel.flatMap { m =>
    ts.update(deleteUriPropertyQ(uri, prop, uriValueString)).map { ok =>
      m.remove(res, m.getProperty(prop.uri), m.createLiteral(uriValueString))
    }
  }

  def dropDataset = {
    ts.update(deleteDatasetQ(uri, skosUri)).map(ok => true)
  }

  // from the old datasetdb

  def setState(state: DsState) = setSingularLiteralProps(state.prop -> now)

  def removeState(state: DsState) = removeLiteralProp(state.prop)

  def setError(message: String) = {
    if (message.isEmpty) {
      removeLiteralProp(datasetErrorMessage)
    }
    else {
      setSingularLiteralProps(
        datasetErrorMessage -> message,
        datasetErrorTime -> now
      )
    }
  }

  def setRecordCount(count: Int) = setSingularLiteralProps(datasetRecordCount -> count.toString)

  def setProcessedRecordCounts(validCount: Int, invalidCount: Int) = setSingularLiteralProps(
    processedValid -> validCount.toString,
    processedInvalid -> invalidCount.toString
  )

  def setHarvestInfo(harvestTypeEnum: HarvestType, url: String, dataset: String, prefix: String) = setSingularLiteralProps(
    harvestType -> harvestTypeEnum.name,
    harvestURL -> url,
    harvestDataset -> dataset,
    harvestPrefix -> prefix
  )

  def setHarvestCron(harvestCron: HarvestCron) = setSingularLiteralProps(
    harvestPreviousTime -> timeToString(harvestCron.previous),
    harvestDelay -> harvestCron.delay.toString,
    harvestDelayUnit -> harvestCron.unit.toString
  )

  def setMetadata(metadata: DsMetadata) = setSingularLiteralProps(
    datasetName -> metadata.name,
    datasetDescription -> metadata.description,
    datasetAggregator -> metadata.aggregator,
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

  def toTurtle = {
    val sw = new StringWriter()
    m.write(sw, "TURTLE")
    sw.toString
  }

  lazy val vocabulary = new SkosVocabulary(spec, skosUri, ts)

  override def toString = spec
}
