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

package dataset

import harvest.Harvesting.{HarvestCron, HarvestType}
import org.OrgDb
import org.basex.server.ClientSession
import org.joda.time.DateTime
import play.api.Logger
import play.api.libs.json.{JsObject, JsString}
import services.BaseX._
import services.Temporal._

import scala.xml.{Elem, NodeSeq, XML}


object DatasetDb {
  def toJsObjectEntryOption(datasetInfo: Elem, tag: String) = {
    val fields: Seq[(String, JsString)] = (datasetInfo \ tag).headOption.map(element =>
      element.child.filter(_.isInstanceOf[Elem]).map(n => n.label -> JsString(n.text))
    ) getOrElse Seq.empty
    if (fields.nonEmpty) Some(tag -> JsObject(fields)) else None
  }

  def prefixOptFromInfo(info: Elem) = Option((info \ "character" \ "prefix").text).find(_.trim.nonEmpty)

  val DATASET_PROPERTY_LISTS = List(
    "character",
    "metadata",
    "error",
    "recordCount",
    "processedRecordCounts",
    "publication",
    "categories",
    "namespaces",
    "harvest",
    "harvestCron",
    "sipFacts",
    "sipHints"
  ) ++ DatasetState.ALL_STATES.map(_.name)

}

case class ProgressType(name: String) {
  override def toString = name

  def matches(otherName: String) = name == otherName
}

object ProgressType {
  val TYPE_IDLE = ProgressType("progress-idle")
  val BUSY = ProgressType("progress-busy")
  val PERCENT = ProgressType("progress-percent")
  val WORKERS = ProgressType("progress-workers")
  val PAGES = ProgressType("progress-pages")

  val ALL_PROGRESS_TYPES = List(TYPE_IDLE, BUSY, PERCENT, WORKERS, PAGES)

  def progressTypeFromString(string: String): Option[ProgressType] = ALL_PROGRESS_TYPES.find(s => s.matches(string))

  def progressTypeFromInfo(datasetInfo: NodeSeq) = progressTypeFromString((datasetInfo \ "progress" \ "type").text)
}

case class ProgressState(name: String) {
  override def toString = name

  def matches(otherName: String) = name == otherName
}

object ProgressState {
  val STATE_IDLE = ProgressState("state-idle")
  val HARVESTING = ProgressState("state-harvesting")
  val COLLECTING = ProgressState("state-collecting")
  val ADOPTING = ProgressState("state-adopting")
  val GENERATING = ProgressState("state-generating")
  val SPLITTING = ProgressState("state-splitting")
  val COLLATING = ProgressState("state-collating")
  val CATEGORIZING = ProgressState("state-categorizing")
  val PROCESSING = ProgressState("state-processing")
  val ERROR = ProgressState("state-error")

  val ALL_STATES = List(STATE_IDLE, HARVESTING, COLLECTING, ADOPTING, GENERATING, SPLITTING, COLLATING, CATEGORIZING, PROCESSING, ERROR)

  def progressStateFromString(string: String): Option[ProgressState] = ALL_STATES.find(s => s.matches(string))

  def progressStateFromInfo(datasetInfo: NodeSeq) = progressStateFromString((datasetInfo \ "progress" \ "state").text)
}


case class DatasetState(name: String) {
  override def toString = name

  def matches(otherName: String) = name == otherName
}

object DatasetState {
  val RAW = DatasetState("rawState")
  val RAW_ANALYZED = DatasetState("rawAnalyzedState")
  val SOURCED = DatasetState("sourcedState")
  val MAPPABLE = DatasetState("mappableState")
  val PROCESSABLE = DatasetState("processableState")
  val PROCESSED = DatasetState("processedState")
  val ANALYZED = DatasetState("analyzedState")
  val SAVED = DatasetState("savedState")

  val ALL_STATES = List(RAW, RAW_ANALYZED, SOURCED, MAPPABLE, PROCESSABLE, PROCESSED, ANALYZED, SAVED)
}

class DatasetDb(repoDb: OrgDb, datasetName: String) {

  def now: String = timeToString(new DateTime())

  def db[T](block: ClientSession => T): T = repoDb.db(block)

  def datasetElement = s"${repoDb.allDatasets}/dataset[@name=${quote(datasetName)}]"

  def createDataset(prefix: String) = db {
    session =>
      val update = s"""
          |
          | let $$character :=
          |   <character>
          |     <prefix>$prefix</prefix>
          |   </character>
          | let $$dataset :=
          |   <dataset name="$datasetName">{ $$character }</dataset>
          | return
          |   if (exists($datasetElement))
          |   then replace node $datasetElement with $$dataset
          |   else insert node $$dataset into ${repoDb.allDatasets}
          |
          """.stripMargin.trim
      Logger.info(s"create dataset:\n$update")
      session.query(update).execute()
  }

  def dropDataset() = db { session =>
    val update = s"delete node $datasetElement"
    session.query(update).execute()
  }

  def infoOpt: Option[Elem] = db { session =>
    val answer = session.query(datasetElement).execute()
    Option(answer).filter(_.trim.nonEmpty).map(XML.loadString)
  }

  def prefixOpt: Option[String] = infoOpt.flatMap(DatasetDb.prefixOptFromInfo)

  def setProperties(listName: String, entries: (String, Any)*): Unit = db { session =>
    val replacementLines = List(
      List(s"  <$listName>"),
      entries.filter(_._2.toString.nonEmpty).map(pair => s"    <${pair._1}>${pair._2}</${pair._1}>"),
      List(s"  </$listName>")
    ).flatten
    val replacement = replacementLines.mkString("\n")
    val update = s"""
          |
          | let $$dataset := $datasetElement
          | let $$block := $$dataset/$listName
          | let $$replacement :=
          | $replacement
          | return if (exists($$block))
          |    then replace node $$block with $$replacement
          |    else insert node $$replacement into $$dataset
          |
          """.stripMargin.trim
    Logger.info(s"$datasetName set $listName: ${entries.toMap}")
    session.query(update).execute()
  }

  def removeProperties(listName: String): Unit = db { session =>
    val update = s"""
          |
          | let $$block := $datasetElement/$listName
          | return delete node $$block
          |
          """.stripMargin.trim
    Logger.info(s"$datasetName remove $listName")
    session.query(update).execute()
  }

  def setState(state: DatasetState) = setProperties(state.name, "time" -> now)

  def removeState(state: DatasetState) = removeProperties(state.name)

  def setError(message: String) = setProperties("error", "message" -> message, "time" -> now)

  def setRecordCount(count: Int) = setProperties("recordCount", "count" -> count, "time" -> now)

  def setProcessedRecordCounts(validCount: Int, invalidCount: Int) = setProperties(
    "processedRecordCounts",
    "valid" -> validCount,
    "invalid" -> invalidCount,
    "time" -> now
  )

  def setNamespaceMap(namespaces: Map[String, String]) = setProperties("namespaces", namespaces.toSeq: _*)

  def setHarvestInfo(harvestType: HarvestType, url: String, dataset: String, prefix: String) = setProperties(
    "harvest",
    "harvestType" -> harvestType.name,
    "url" -> url,
    "dataset" -> dataset,
    "prefix" -> prefix
  )

  def setHarvestCron(harvestCron: HarvestCron) = setProperties(
    "harvestCron",
    "previous" -> timeToString(harvestCron.previous),
    "delay" -> harvestCron.delay,
    "unit" -> harvestCron.unit.toString
  )

  def setSipFacts(facts: Map[String, String]) = setProperties("sipFacts", facts.toSeq: _*)

  def setSipHints(hints: Map[String, String]) = setProperties("sipHints", hints.toSeq: _*)

  def setMetadata(metadata: Map[String, String]) = setProperties("metadata", metadata.toSeq: _*)

  def setPublication(publishOaiPmh: String, publishIndex: String, publishLoD: String) = setProperties(
    "publication",
    "oaipmh" -> publishOaiPmh,
    "index" -> publishIndex,
    "lod" -> publishLoD
  )

  def setCategories(included: String) = setProperties(
    "categories",
    "included" -> included
  )

}
