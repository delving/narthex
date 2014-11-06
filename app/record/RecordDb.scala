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
package record

import dataset.{DatasetOrigin, DatasetRepo}
import harvest.Harvesting.{Harvest, PMHResumptionToken}
import org.OrgRepo
import org.basex.server.ClientSession
import org.joda.time.DateTime
import play.api.Play.current
import play.api.cache.Cache
import record.PocketParser._
import record.RecordDb.FoundRecord
import services.BaseX._
import services.Temporal._
import services._

import scala.concurrent.duration._
import scala.language.postfixOps
import scala.xml.{Elem, NodeSeq, XML}

/**
 * @author Gerald de Jong <gerald@delving.eu
 */

object RecordDb {

  case class FoundRecord(id: String, path: String, hash: String)


}

class RecordDb(datasetRepo: DatasetRepo, dbName: String) {

  val recordDb = s"${dbName}_records"

  def createDb() = createCleanDatabase(recordDb)

  def dropDb() = dropDatabase(recordDb)

  def getDatasetInfo = datasetRepo.datasetDb.infoOption.getOrElse(throw new RuntimeException(s"Not found: $datasetRepo"))

  def withRecordDb[T](block: ClientSession => T) = withDbSession(recordDb)(block)

  def childrenAsMap(nodeSeq: NodeSeq) = nodeSeq flatMap {
    case e: Elem => e.child
    case _ => NodeSeq.Empty
  }

  private def namespaceDeclarations(dataset: Elem) = {
    val declarations = (dataset \ "namespaces" \ "_").flatMap {
      node =>
        if (node.label.trim.nonEmpty && node.label != "xml") {
          val prefix = node.label
          val uri = node.text
          Some( s"""declare namespace $prefix="$uri";""")
        }
        else {
          None
        }
    }
    declarations.mkString("\n")
  }

  def findRecord(id: String, session: ClientSession): Option[FoundRecord] = {
    val queryForPath =
      s"""
         |
         | let $$pocket := collection('$recordDb')/$POCKET[@id=${quote(id)}]
         |
         | return
         |    if (exists($$pocket)) then
         |      <result><path>{ db:path($$pocket) }</path><hash>{ string($$pocket/@hash) }</hash></result>
         |    else
         |      <result/>
         |
       """.stripMargin
    val resultString = session.query(queryForPath).execute()
    val result: Elem = XML.loadString(resultString)
    val path = (result \ "path").text
    val hash = (result \ "hash").text
    if (path.nonEmpty) Some(FoundRecord(id, path, hash)) else None
  }

  def getRecordCount: Int = withRecordDb { session =>
    val queryCount = s"""
          |
          | let $$boxes := collection('$recordDb')/$POCKET
          | return count($$boxes)
          |
          |""".stripMargin.trim
    session.query(queryCount).execute().toInt
  }

  def recordsWithValue(path: String, value: String, start: Int = 1, max: Int = 10): String = withRecordDb { session =>
    val datasetInfo = getDatasetInfo
    // fetching the recordRoot here because we need to chop the path string.  can that be avoided?
    val recordContainer = if (DatasetOrigin.HARVEST.matches((datasetInfo \ "origin" \ "type").text)) {
      s"/$POCKET_LIST/$POCKET"
    }
    else {
      val recordRoot = (datasetInfo \ "delimit" \ "recordRoot").text
      recordRoot.substring(0, recordRoot.lastIndexOf("/"))
    }
    if (!path.startsWith(recordContainer)) throw new RuntimeException(s"$path must start with $recordContainer!")
    val queryPathField = path.substring(recordContainer.length)
    val field = queryPathField.substring(queryPathField.lastIndexOf("/") + 1)
    val queryPath = queryPathField.substring(0, queryPathField.lastIndexOf("/"))
    val queryForRecords = s"""
          |
          | ${namespaceDeclarations(datasetInfo)}
          | let $$boxes := collection('$recordDb')[/$POCKET$queryPath/$field=${quote(value)}]
          | let $$selected := subsequence($$boxes, $start, $max)
          | return <records>{ for $$box in $$selected return $$box/* }</records>
          |
          """.stripMargin.trim
    println("asking:\n" + queryForRecords)
    session.query(queryForRecords).execute()
  }

  def record(identifier: String): String = withRecordDb { session =>
    val queryForRecord = s"""
        |
        | ${namespaceDeclarations(getDatasetInfo)}
        | let $$box := collection('$recordDb')[/$POCKET/@id=${quote(identifier)}]
        | return <record>{ $$box }</record>
        |
        """.stripMargin.trim
    println("asking:\n" + queryForRecord)
    session.query(queryForRecord).execute()
  }

  def getIds(since: String): String = withRecordDb { session =>
    val q = s"""
        |
        | let $$boxes := collection('$recordDb')/$POCKET
        | return
        |    <ids>{
        |       for $$box in $$boxes
        |       where $$box/@mod >= ${quote(since)}
        |       order by $$box/@mod descending
        |       return
        |          <id>{$$box/@mod}{string($$box/@id)}</id>
        |    }</ids>
        |
        |""".stripMargin.trim
    session.query(q).execute()
  }

  def dateSelector(from: Option[DateTime], until: Option[DateTime]) = (from, until) match {
    case (Some(fromDate), Some(untilDate)) =>
      s"[@mod >= '${timeToString(fromDate)}' and @mod < '${timeToString(untilDate)}']"
    case (Some(fromDate), None) =>
      s"[@mod >= '${timeToString(fromDate)}']"
    case (None, Some(untilDate)) =>
      s"[@mod < '${timeToString(untilDate)}']"
    case (None, None) =>
      ""
  }

  def createHarvest(headersOnly: Boolean, from: Option[DateTime], until: Option[DateTime]): Option[PMHResumptionToken] = withRecordDb { session =>
    val now = new DateTime()
    val name = datasetRepo.name
    println(s"createHarvest: $name, $from, $until")
    val datasetInfo = getDatasetInfo
    if (!OrgRepo.isPublishedOaiPmh(datasetInfo)) return None
    val queryForRecords = s"count(collection('$recordDb')/$POCKET${dateSelector(from, until)})"
    println("asking:\n" + queryForRecords)
    val countString = session.query(queryForRecords).execute()
    val count = countString.toInt
    val pageSize = NarthexConfig.OAI_PMH_PAGE_SIZE
    val pages = if (count % pageSize == 0) count / pageSize else count / pageSize + 1
    val harvest = Harvest(
      repoName = name,
      headersOnly = headersOnly,
      from = from,
      until = until,
      totalPages = pages,
      totalRecords = count,
      pageSize = pageSize
    )
    Cache.set(harvest.resumptionToken.value, harvest, 2 minutes)
    Some(harvest.resumptionToken)
  }

  def recordHarvest(from: Option[DateTime], until: Option[DateTime], start: Int, pageSize: Int): String = withRecordDb { session =>
    val query = s"""
        |
        | ${namespaceDeclarations(getDatasetInfo)}
        |
        | let $$selection := collection('$recordDb')/$POCKET${dateSelector(from, until)}
        |
        | return
        |   <records>
        |     {
        |       for $$box in subsequence($$selection, $start, $pageSize)
        |          order by $$box/@mod descending
        |              return $$box
        |     }
        |   </records>
        |
        """.stripMargin.trim
    session.query(query).execute()
  }

  def recordPmh(identifier: String): Option[Elem] = withRecordDb { session =>
    val datasetInfo = getDatasetInfo
    if (!OrgRepo.isPublishedOaiPmh(datasetInfo)) return None
    val queryForRecord = s"""
        |
        | ${namespaceDeclarations(getDatasetInfo)}
        | let $$box := collection('$recordDb')[/$POCKET/@id=${quote(identifier)}]
        | return
        |   <record>
        |     <header>
        |       <identifier>{data($$rec/$POCKET/@id)}</identifier>
        |       <datestamp>{data($$rec/$POCKET/@mod)}</datestamp>
        |       <setSpec>${datasetRepo.name}</setSpec>
        |     </header>
        |     <metadata>
        |      {$$box/$POCKET/*}
        |     </metadata>
        |   </record>
        |
        """.stripMargin.trim
    Some(XML.loadString(session.query(queryForRecord).execute()))
  }

  def recordsPmh(from: Option[DateTime], until: Option[DateTime], start: Int, pageSize: Int, headersOnly: Boolean): Seq[String] = withRecordDb { session =>
    val metadata = if (headersOnly) "" else "<metadata>{$box/*}</metadata>"
    val query = s"""
        |
        | ${namespaceDeclarations(getDatasetInfo)}
        |
        | let $$selection := collection('$recordDb')/$POCKET${dateSelector(from, until)}
        |
        | let $$boxes :=
        |   for $$box in subsequence($$selection, $start, $pageSize)
        |   order by $$box/@mod descending
        |     return
        |       <record>
        |         <header>
        |           <identifier>{data($$box/@id)}</identifier>
        |           <datestamp>{data($$box/@mod)}</datestamp>
        |           <setSpec>${datasetRepo.name}</setSpec>
        |         </header>
        |         $metadata
        |       </record>
        |
        | return
        |   <records start="$start" pageSize="$pageSize">
        |     {$$boxes}
        |   </records>
        |
        """.stripMargin.trim
    // todo: look at doing this with just string instead of via XML
    val wrappedRecords: Elem = XML.loadString(session.query(query).execute())
    (wrappedRecords \ "record").map(_.toString())
  }
}
