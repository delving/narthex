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

import actors.{SaveRecords, Saver}
import org.basex.server.ClientSession
import org.joda.time.DateTime
import play.api.Play.current
import play.api.cache.Cache
import play.libs.Akka

import scala.concurrent.duration._
import scala.language.postfixOps
import scala.xml.{Elem, NodeSeq, XML}

/**
 * @author Gerald de Jong <gerald@delving.eu
 */

class RecordDb(fileRepo: FileRepo, dbName: String) {

  val recordDb = s"${dbName}_records"

  def freshDb[T](block: ClientSession => T) = {
    Repo.baseX.createDatabase(recordDb) // overwrites
    Repo.baseX.withDbSession(recordDb)(block)
  }

  def db[T](block: ClientSession => T) = {
    Repo.baseX.withDbSession(recordDb)(block)
  }

  def dropDatabase() = {
    Repo.baseX.dropDatabase(recordDb)
  }

  def saveRecords() = {
    val delim = fileRepo.datasetDb.getDatasetInfo \ "delimit"
    val recordRoot = (delim \ "recordRoot").text
    val uniqueId = (delim \ "uniqueId").text
    val recordCountText = (delim \ "recordCount").text
    val recordCount = if (recordCountText.isEmpty) 0 else recordCountText.toInt
    // set status now so it's done before the actor starts
    fileRepo.datasetDb.setStatus(Repo.State.SAVING, percent = 1)
    val saver = Akka.system.actorOf(Saver.props(fileRepo), fileRepo.name)
    saver ! SaveRecords(recordRoot, uniqueId, recordCount, fileRepo.name)
  }

  private def namespaceDeclarations(dataset: Elem) = {
    val namespaces = dataset \ "namespaces" \ "namespace"
    namespaces.flatMap {
      node =>
        val prefix = (node \ "prefix").text
        val uri = (node \ "uri").text
        if (prefix != "xml") {
          Some( s"""declare namespace $prefix = "$uri";""")
        }
        else
          None
    }.mkString("\n")
  }

  def recordsWithValue(path: String, value: String, start: Int = 1, max: Int = 10): String = {
    val datasetInfo = fileRepo.datasetDb.getDatasetInfo
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
          | let $$recordsWithValue := collection('$recordDb')[/narthex$queryPath/$field=${Repo.quote(value)}]
          | let $$selected := subsequence($$recordsWithValue, $start, $max)
          | return <records>{ for $$rec in $$selected return $$rec/narthex/* }</records>
          |
          """.stripMargin.trim
        println("asking:\n" + queryForRecords)
        session.query(queryForRecords).execute()
      // todo: ENRICH!
    }
  }

  def record(identifier: String): Elem = db {
    session =>
      val queryForRecord = s"""
        |
        | ${namespaceDeclarations(fileRepo.datasetDb.getDatasetInfo)}
        | let $$recordWithId := collection('$recordDb')[/narthex/@id=${Repo.quote(identifier)}]
        | return <record>{
        |   $$recordWithId
        | }</record>
        |
        """.stripMargin.trim
      println("asking:\n" + queryForRecord)
      XML.loadString(session.query(queryForRecord).execute())
  }

  def getIds(since: String): String = db {
    session =>
      val q = s"""
        |
        | let $$records := collection('$recordDb')/narthex
        | return
        |    <ids>{
        |       for $$narthex in $$records
        |       where $$narthex/@mod >= ${Repo.quote(since)}
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
      s"[@mod >= '${Repo.toXSDString(fromDate)}' and @mod < '${Repo.toXSDString(untilDate)}']"
    case (Some(fromDate), None) =>
      s"[@mod >= '${Repo.toXSDString(fromDate)}']"
    case (None, Some(untilDate)) =>
      s"[@mod < '${Repo.toXSDString(untilDate)}']"
    case (None, None) =>
      ""
  }

  def createHarvest(headersOnly: Boolean, from: Option[DateTime], until: Option[DateTime]): Option[String] = {
    val now = new DateTime()
    val name = fileRepo.name
    println(s"createHarvest: $name, $from, $until")
    val countString = db {
      session =>
        val queryForRecords = s"count(collection('$recordDb')/narthex${dateSelector(from, until)})"
        println("asking:\n" + queryForRecords)
        session.query(queryForRecords).execute()
    }
    val count = countString.toInt
    val pageSize = NarthexConfig.OAI_PMH_PAGE_SIZE
    val pages = if (count % pageSize == 0) count / pageSize else count / pageSize + 1
    val harvest = Harvest(name, headersOnly, from, until, pages)
    Cache.set(harvest.resumptionToken, harvest, 2 minutes)
    Some(harvest.resumptionToken)
  }

  def recordPmh(identifier: String): Elem = db {
    session =>
      // todo: the setSpec is missing here!
      val queryForRecord = s"""
        |
        | ${namespaceDeclarations(fileRepo.datasetDb.getDatasetInfo)}
        | let $$rec := collection('$recordDb')[/narthex/@id=${Repo.quote(identifier)}]
        | return
        |   <record>
        |     <header>
        |       <identifier>{$$rec/narthex/@id}</identifier>
        |       <datestamp>{$$rec/narthex/@mod}</datestamp>
        |       <setSpec>?</setSpec>
        |     </header>
        |     <metadata>
        |      {$$rec/narthex/*}
        |     </metadata>
        |   </record>
        |
        """.stripMargin.trim
      XML.loadString(session.query(queryForRecord).execute())
  }

  def recordsPmh(from: Option[DateTime], until: Option[DateTime], start: Int, pageSize: Int, headersOnly: Boolean = true): NodeSeq = db {
    session =>
      val metadata = if (headersOnly) "" else "<metadata>{$narthex/*}</metadata>"
      // todo: the setSpec is missing here!
      val query = s"""
        |
        | ${namespaceDeclarations(fileRepo.datasetDb.getDatasetInfo)}
        |
        | let $$selection := collection('$recordDb')/narthex${dateSelector(from, until)}
        |
        | let $$records :=
        |   for $$narthex in subsequence($$selection, $start, $pageSize)
        |   order by $$narthex/@mod descending
        |     return
        |       <record>
        |         <header>
        |           <identifier>{$$narthex/@id}</identifier>
        |           <datestamp>{$$narthex/@mod}</datestamp>
        |           <setSpec>?</setSpec>
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
      val wrappedRecords: Elem = XML.loadString(session.query(query).execute())
      wrappedRecords \ "record"
  }
}
