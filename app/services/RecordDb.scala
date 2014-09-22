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
import play.api.Play.current
import play.api.cache.Cache
import services.DatasetState._

import scala.concurrent.duration._
import scala.language.postfixOps
import scala.xml.{Elem, NodeSeq, XML}

/**
 * @author Gerald de Jong <gerald@delving.eu
 */

class RecordDb(datasetRepo: DatasetRepo, dbName: String) extends BaseXTools {

  val recordDb = s"${dbName}_records"

  def createDb = BaseX.createDatabase(recordDb)

  def dropDb() = BaseX.dropDatabase(recordDb)

  def getDatasetInfo = datasetRepo.datasetDb.getDatasetInfoOption.getOrElse(throw new RuntimeException(s"Not found: $datasetRepo"))

  def db[T](block: ClientSession => T) = BaseX.withDbSession(recordDb)(block)

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

  def recordsWithValue(path: String, value: String, start: Int = 1, max: Int = 10): String = {
    val datasetInfo = getDatasetInfo
    // fetching the recordRoot here because we need to chop the path string.  can that be avoided?
    val delim = datasetInfo \ "delimit"
    val recordRoot = (delim \ "recordRoot").text
    val prefix = recordRoot.substring(0, recordRoot.lastIndexOf("/"))
    if (!path.startsWith(prefix)) throw new RuntimeException(s"$path must start with $prefix!")
    val queryPathField = path.substring(prefix.length)
    val field = queryPathField.substring(queryPathField.lastIndexOf("/") + 1)
    val queryPath = queryPathField.substring(0, queryPathField.lastIndexOf("/"))
    db {
      session =>
        val queryForRecords = s"""
          |
          | ${namespaceDeclarations(datasetInfo)}
          | let $$recordsWithValue := collection('$recordDb')[/narthex$queryPath/$field=${quote(value)}]
          | let $$selected := subsequence($$recordsWithValue, $start, $max)
          | return <records>{ for $$rec in $$selected return $$rec/* }</records>
          |
          """.stripMargin.trim
        println("asking:\n" + queryForRecords)
        session.query(queryForRecords).execute()
    }
  }

  def record(identifier: String): String = db {
    session =>
      val queryForRecord = s"""
        |
        | ${namespaceDeclarations(getDatasetInfo)}
        | let $$recordWithId := collection('$recordDb')[/narthex/@id=${quote(identifier)}]
        | return <record>{
        |   $$recordWithId
        | }</record>
        |
        """.stripMargin.trim
      println("asking:\n" + queryForRecord)
      session.query(queryForRecord).execute()
  }

  def getIds(since: String): String = db {
    session =>
      val q = s"""
        |
        | let $$records := collection('$recordDb')/narthex
        | return
        |    <ids>{
        |       for $$narthex in $$records
        |       where $$narthex/@mod >= ${quote(since)}
        |       order by $$narthex/@mod descending
        |       return
        |          <id>{$$narthex/@mod}{string($$narthex/@id)}</id>
        |    }</ids>
        |
        |""".stripMargin.trim
      session.query(q).execute()
  }

  def dateSelector(from: Option[DateTime], until: Option[DateTime]) = (from, until) match {
    case (Some(fromDate), Some(untilDate)) =>
      s"[@mod >= '${toXSDString(fromDate)}' and @mod < '${toXSDString(untilDate)}']"
    case (Some(fromDate), None) =>
      s"[@mod >= '${toXSDString(fromDate)}']"
    case (None, Some(untilDate)) =>
      s"[@mod < '${toXSDString(untilDate)}']"
    case (None, None) =>
      ""
  }

  def createHarvest(headersOnly: Boolean, from: Option[DateTime], until: Option[DateTime]): Option[PMHResumptionToken] = {
    val now = new DateTime()
    val name = datasetRepo.name
    println(s"createHarvest: $name, $from, $until")
    val datasetInfo = getDatasetInfo
    val state = (datasetInfo \ "status" \ "state").text
    if (!PUBLISHED.matches(state)) return None
    val countString = db {
      session =>
        val queryForRecords = s"count(collection('$recordDb')/narthex${dateSelector(from, until)})"
        println("asking:\n" + queryForRecords)
        session.query(queryForRecords).execute()
    }
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

  def recordHarvest(from: Option[DateTime], until: Option[DateTime], start: Int, pageSize: Int): String = db {
    session =>
      val query = s"""
        |
        | ${namespaceDeclarations(getDatasetInfo)}
        |
        | let $$selection := collection('$recordDb')/narthex${dateSelector(from, until)}
        |
        | return
        |   <records>
        |     {
        |       for $$narthex in subsequence($$selection, $start, $pageSize)
        |          order by $$narthex/@mod descending
        |              return $$narthex
        |     }
        |   </records>
        |
        """.stripMargin.trim
      session.query(query).execute()
  }

  def recordPmh(identifier: String): Option[Elem] = db {
    session =>
      val datasetInfo = getDatasetInfo
      val state = (datasetInfo \ "status" \ "state").text
      if (!PUBLISHED.matches(state)) return None
      val queryForRecord = s"""
        |
        | ${namespaceDeclarations(getDatasetInfo)}
        | let $$rec := collection('$recordDb')[/narthex/@id=${quote(identifier)}]
        | return
        |   <record>
        |     <header>
        |       <identifier>{data($$rec/narthex/@id)}</identifier>
        |       <datestamp>{data($$rec/narthex/@mod)}</datestamp>
        |       <setSpec>${datasetRepo.name}</setSpec>
        |     </header>
        |     <metadata>
        |      {$$rec/narthex/*}
        |     </metadata>
        |   </record>
        |
        """.stripMargin.trim
      Some(XML.loadString(session.query(queryForRecord).execute()))
  }

  def recordsPmh(from: Option[DateTime], until: Option[DateTime], start: Int, pageSize: Int, headersOnly: Boolean): Seq[String] = db {
    session =>
      val metadata = if (headersOnly) "" else "<metadata>{$narthex/*}</metadata>"
      val query = s"""
        |
        | ${namespaceDeclarations(getDatasetInfo)}
        |
        | let $$selection := collection('$recordDb')/narthex${dateSelector(from, until)}
        |
        | let $$records :=
        |   for $$narthex in subsequence($$selection, $start, $pageSize)
        |   order by $$narthex/@mod descending
        |     return
        |       <record>
        |         <header>
        |           <identifier>{data($$narthex/@id)}</identifier>
        |           <datestamp>{data($$narthex/@mod)}</datestamp>
        |           <setSpec>${datasetRepo.name}</setSpec>
        |         </header>
        |         $metadata
        |       </record>
        |
        | return
        |   <records start="$start" pageSize="$pageSize">
        |     {$$records}
        |   </records>
        |
        """.stripMargin.trim
      // todo: look at doing this with just string instead of via XML
      val wrappedRecords: Elem = XML.loadString(session.query(query).execute())
      (wrappedRecords \ "record").map(_.toString())
  }
}
