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

import scala.xml.{Elem, XML}

class DatasetDb(repoDb: RepoDb, fileName: String) extends BaseXTools {

  def db[T](block: ClientSession => T): T = repoDb.db(block)

  def datasetElement = s"${repoDb.allDatasets}/dataset[@name=${quote(fileName)}]"

  def createDataset(state: DatasetState) = db {
    session =>
      val update = s"""
          |
          | let $$dataset :=
          |   <dataset name="$fileName">
          |     <status><state>$state</state></status>
          |     <delimit/>
          |     <namespaces/>
          |     <harvest/>
          |   </dataset>
          | return
          |   if (exists($datasetElement))
          |   then replace value of node $datasetElement/status/state with ${quote(state.toString)}
          |   else insert node $$dataset into ${repoDb.allDatasets}
          |
          """.stripMargin.trim
      println("updating:\n" + update)
      session.query(update).execute()
  }

  def getDatasetInfoOption: Option[Elem] = db {
    session =>
      val answer = session.query(datasetElement).execute()
      if (answer.nonEmpty) Some(XML.loadString(answer)) else None
  }

  def removeDataset() = db {
    session =>
      val update = s"return delete node $datasetElement"
      println("updating:\n" + update)
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
          | let $$block := $datasetElement/$listName
          | let $$replacement :=
          | $replacement
          | return replace node $$block with $$replacement
          |
          """.stripMargin.trim
      println("updating:\n" + update)
      session.query(update).execute()
  }

  def setStatus(state: DatasetState, percent: Int = 0, workers: Int = 0, error: String = "") = setProperties(
    "status",
    "state" -> state,
    "percent" -> percent,
    "workers" -> workers,
    "error" -> error
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
    "url" -> url,
    "dataset" -> dataset,
    "prefix" -> prefix
  )

}
