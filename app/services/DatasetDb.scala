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

package services

import org.basex.server.ClientSession
import org.joda.time.DateTime
import play.api.Logger
import play.api.libs.json.{JsObject, JsString}
import services.Harvesting.HarvestCron
import services.ProgressType._

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
  val IDLE = ProgressType("progress-idle")
  val BUSY = ProgressType("progress-busy")
  val PERCENT = ProgressType("progress-percent")
  val WORKERS = ProgressType("progress-workers")
  val PAGES = ProgressType("progress-pages")

  val ALL_PROGRESS_TYPES = List(IDLE, BUSY, PERCENT, WORKERS, PAGES)

  def fromString(string: String): Option[ProgressType] = ALL_PROGRESS_TYPES.find(s => s.matches(string))
}

case class DatasetState(name: String, busy: Boolean = false) {
  override def toString = name

  def matches(otherName: String) = name == otherName
}

object DatasetState {
  val DELETED = DatasetState("state-deleted")
  val EMPTY = DatasetState("state-empty")
  val HARVESTING = DatasetState("state-harvesting", busy = true)
  val READY = DatasetState("state-ready")
  val COLLECTING = DatasetState("state-collecting", busy = true)
  val SPLITTING = DatasetState("state-splitting", busy = true)
  val ANALYZING = DatasetState("state-analyzing", busy = true)
  val ANALYZED = DatasetState("state-analyzed")
  val SAVING = DatasetState("state-saving", busy = true)
  val SAVED = DatasetState("state-saved")

  val ALL_STATES = List(DELETED, EMPTY, COLLECTING, HARVESTING, READY, SPLITTING, ANALYZING, ANALYZED, SAVING, SAVED)

  def fromString(string: String): Option[DatasetState] = ALL_STATES.find(s => s.matches(string))

  def fromDatasetInfo(datasetInfo: NodeSeq) = fromString((datasetInfo \ "status" \ "state").text)
}

class DatasetDb(repoDb: RepoDb, fileName: String) extends BaseXTools {

  def db[T](block: ClientSession => T): T = repoDb.db(block)

  def datasetElement = s"${repoDb.allDatasets}/dataset[@name=${quote(fileName)}]"

  def createDataset(state: DatasetState) = db {
    session =>
      val now = System.currentTimeMillis()
      val update = s"""
          |
          | let $$dataset :=
          |   <dataset name="$fileName">
          |     <status>
          |       <state>$state</state>
          |       <time>$now</time>
          |       <error/>
          |     </status>
          |   </dataset>
          | return
          |   if (exists($datasetElement))
          |   then replace value of node $datasetElement/status/state with ${quote(state.toString)}
          |   else insert node $$dataset into ${repoDb.allDatasets}
          |
          """.stripMargin.trim
      Logger.info(s"create dataset:\n$update")
      session.query(update).execute()
  }

  def getDatasetInfoOption: Option[Elem] = db {
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
        entries.map(pair => s"    <${pair._1}>${pair._2}</${pair._1}>"),
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
      Logger.info(s"$fileName set $listName: ${entries.toMap}")
      session.query(update).execute()
  }

  def setOrigin(origin: DatasetOrigin) = setProperties(
    "origin",
    "type" -> origin,
    "time" -> toXSDString(new DateTime())
  )

  def setStatus(state: DatasetState, error: String = "") = {
    setProperties(
      "status",
      "state" -> state,
      "time" -> toXSDString(new DateTime()),
      "error" -> error
    )
    setProgress(if (state.busy && error.isEmpty) BUSY else IDLE, 0)
  }

  def setProgress(progressType: ProgressType, count: Int) = setProperties(
    "progress",
    "type" -> progressType,
    "count" -> count
  )

  def setRecordDelimiter(recordRoot: String = "", uniqueId: String = "", recordCount: Int = 0) = setProperties(
    "delimit",
    "recordRoot" -> recordRoot,
    "uniqueId" -> uniqueId,
    "recordCount" -> recordCount
  )

  def setNamespaceMap(namespaceMap: Map[String, String]) = setProperties(
    "namespaces", namespaceMap.toSeq: _*
  )

  def setHarvestInfo(harvestType: String, url: String, dataset: String, prefix: String) = setProperties(
    "harvest",
    "harvestType" -> harvestType,
    "url" -> url,
    "dataset" -> dataset,
    "prefix" -> prefix
  )

  def setHarvestCron(harvestCron: HarvestCron) = setProperties(
    "harvestCron",
    "previous" -> toBasicString(harvestCron.previous),
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

  def setPublication(publishOaiPmh: String, publishIndex: String, publishLoD: String) = setProperties(
    "publication",
    "oaipmh" -> publishOaiPmh,
    "index" -> publishIndex,
    "lod" -> publishLoD
  )

}
