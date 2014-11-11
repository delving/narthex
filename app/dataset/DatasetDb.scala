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

import dataset.ProgressState._
import dataset.ProgressType._
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
}

case class DatasetOrigin(name: String) {
  override def toString = name

  def matches(otherName: String) = name == otherName
}

object DatasetOrigin {
  val DROP = DatasetOrigin("origin-drop")
  val HARVEST = DatasetOrigin("origin-harvest")
  val SIP = DatasetOrigin("origin-sip")

  val ALL_ORIGINS = List(DROP, HARVEST, SIP)

  def fromString(string: String): Option[DatasetOrigin] = ALL_ORIGINS.find(s => s.matches(string))
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

  def fromString(string: String): Option[ProgressType] = ALL_PROGRESS_TYPES.find(s => s.matches(string))

  def fromDatasetInfo(datasetInfo: NodeSeq) = fromString((datasetInfo \ "progress" \ "type").text)
}

case class ProgressState(name: String) {
  override def toString = name

  def matches(otherName: String) = name == otherName
}

object ProgressState {
  val STATE_IDLE = ProgressState("state-idle")
  val HARVESTING = ProgressState("state-harvesting")
  val COLLECTING = ProgressState("state-collecting")
  val GENERATING = ProgressState("state-generating")
  val SPLITTING = ProgressState("state-splitting")
  val COLLATING = ProgressState("state-collating")
  val CATEGORIZING = ProgressState("state-categorizing")
  val SAVING = ProgressState("state-saving")
  val UPDATING = ProgressState("state-updating")
  val ERROR = ProgressState("state-error")

  val ALL_STATES = List(STATE_IDLE, HARVESTING, COLLECTING, GENERATING, SPLITTING, COLLATING, CATEGORIZING, SAVING, ERROR)

  def fromString(string: String): Option[ProgressState] = ALL_STATES.find(s => s.matches(string))

  def fromDatasetInfo(datasetInfo: NodeSeq) = fromString((datasetInfo \ "progress" \ "state").text)
}


case class DatasetState(name: String) {
  override def toString = name

  def matches(otherName: String) = name == otherName
}

object DatasetState {
  val DELETED = DatasetState("state-deleted")
  val EMPTY = DatasetState("state-empty")
  val SOURCED = DatasetState("state-sourced")

  val ALL_STATES = List(DELETED, EMPTY, SOURCED)

  def fromString(string: String): Option[DatasetState] = ALL_STATES.find(s => s.matches(string))

  def fromDatasetInfo(datasetInfo: NodeSeq) = fromString((datasetInfo \ "status" \ "state").text)
}

class DatasetDb(repoDb: OrgDb, datasetName: String) {

  def now: String = timeToString(new DateTime())

  def db[T](block: ClientSession => T): T = repoDb.db(block)

  def datasetElement = s"${repoDb.allDatasets}/dataset[@name=${quote(datasetName)}]"

  def createDataset(state: DatasetState) = db {
    session =>
      val update = s"""
          |
          | let $$status :=
          |   <status>
          |     <state>$state</state>
          |     <time>$now</time>
          |   </status>
          | let $$dataset :=
          |   <dataset name="$datasetName">{ $$status }</dataset>
          | return
          |   if (exists($datasetElement))
          |   then replace node $datasetElement/status with $$status
          |   else insert node $$dataset into ${repoDb.allDatasets}
          |
          """.stripMargin.trim
      Logger.info(s"create dataset:\n$update")
      session.query(update).execute()
  }

  def infoOption: Option[Elem] = db {
    session =>
      val answer = session.query(datasetElement).execute()
      if (answer.nonEmpty) Some(XML.loadString(answer)) else None
  }

  def removeDataset() = db {
    session =>
      val update = s"delete node $datasetElement"
      session.query(update).execute()
  }

  def setProperties(listName: String, entries: (String, Any)*): Unit = db {
    session =>
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

  def setStatus(state: DatasetState) = setProperties(
    "status",
    "state" -> state,
    "time" -> now
  )

  def setTree(ready: Boolean) = setProperties(
    "tree",
    "time" -> (if (ready) now else "")
  )

  def setRecords(ready: Boolean) = setProperties(
    "records",
    "time" -> (if (ready) now else "")
  )

  def setOrigin(origin: DatasetOrigin) = setProperties(
    "origin",
    "type" -> origin,
    "time" -> timeToString(new DateTime())
  )

  def startProgress(progressState: ProgressState) = setProgress(progressState, BUSY, 0)

  def endProgress(error: Option[String] = None) = setProgress(if (error.isDefined) ERROR else STATE_IDLE, TYPE_IDLE, 0, error)

  def setProgress(progressState: ProgressState, progressType: ProgressType, count: Int, error: Option[String] = None) = setProperties(
    "progress",
    "state" -> progressState,
    "type" -> progressType,
    "count" -> count,
    "error" -> error.getOrElse("")
  )

  def setRecordDelimiter(recordRoot: String = "", uniqueId: String = "", recordCount: Int = 0) = setProperties(
    "delimit",
    "recordRoot" -> recordRoot,
    "uniqueId" -> uniqueId,
    "recordCount" -> recordCount
  )

  def isDelimited(existingInfo: Option[NodeSeq]): Boolean = {
    def check(info: NodeSeq) = {
      val delimit = info \ "delimit"
      val recordRoot = delimit \ "recordRoot"
      recordRoot.nonEmpty
    }
    existingInfo.map(check).getOrElse(infoOption.exists(check))
  }

  def setNamespaceMap(namespaceMap: Map[String, String]) = setProperties(
    "namespaces", namespaceMap.toSeq: _*
  )

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

  def setSipFacts(facts: Map[String, String]) = setProperties(
    "sipFacts", facts.toSeq: _*
  )

  def setSipHints(hints: Map[String, String]) = setProperties(
    "sipHints", hints.toSeq: _*
  )

  def setMetadata(metadata: Map[String, String]) = setProperties(
    "metadata", metadata.toSeq: _*
  )

  def setPublication(oaipmhPrefixes: String, publishIndex: String, publishLoD: String) = setProperties(
    "publication",
    "oaipmhPrefixes" -> oaipmhPrefixes,
    "index" -> publishIndex,
    "lod" -> publishLoD
  )

  def setCategories(included: String) = setProperties(
    "categories",
    "included" -> included
  )

}
