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

import org.basex.core.BaseXException
import org.basex.server.ClientSession

import scala.xml.{Elem, XML}

/**
 * @author Gerald de Jong <gerald@delving.eu
 */

class DatasetDb(dbName: String) {

  def db[T](block: ClientSession => T): T = {
    try {
      Repo.baseX.withDbSession[T](dbName)(block)
    }
    catch {
      case be: BaseXException =>
        if (be.getMessage.contains("not found")) {
          Repo.baseX.createDatabase(dbName,
            """
              | <narthex-dataset>
              |   <status/>
              |   <delimit/>
              |   <namespaces/>
              | </narthex-dataset>
              | """.stripMargin
          )
          Repo.baseX.withDbSession[T](dbName)(block)
        }
        else {
          throw be
        }
    }
  }

  def getDatasetInfo: Elem = {
    db {
      session =>
        // try the following without doc() sometime, since the db is open
        val statusQuery = s"doc('$dbName/$dbName.xml')/narthex-dataset"
        val answer = session.query(statusQuery).execute()
        XML.loadString(answer)
    }
  }

  def setStatus(state: String, percent: Int = 0, workers: Int = 0) = {
    db {
      session =>
        val update =
          s"""
             |
             | let $$statusBlock := doc('$dbName/$dbName.xml')/narthex-dataset/status
             | let $$replacement :=
             |   <status>
             |     <state>$state</state>
             |     <percent>$percent</percent>
             |     <workers>$workers</workers>
             |   </status>
             | return replace node $$statusBlock with $$replacement
             |
           """.stripMargin.trim
        //        println("updating:\n" + update)
        session.query(update).execute()
    }
  }

  def setRecordDelimiter(recordRoot: String = "", uniqueId: String = "", recordCount: Int = 0) = {
    db {
      session =>
        val update =
          s"""
             |
             | let $$delimitBlock := doc('$dbName/$dbName.xml')/narthex-dataset/delimit
             | let $$replacement :=
             |   <delimit>
             |     <recordRoot>$recordRoot</recordRoot>
             |     <uniqueId>$uniqueId</uniqueId>
             |     <recordCount>$recordCount</recordCount>
             |   </delimit>
             | return replace node $$delimitBlock with $$replacement
             |
           """.stripMargin.trim
        //        println("updating:\n" + update)
        session.query(update).execute()
    }
  }

  def setNamespaceMap(namespaceMap: Map[String, String]) = {
    db {
      session =>
        val namespaces = namespaceMap.map {
          entry =>
            s"    <namespace><prefix>${entry._1}</prefix><uri>${entry._2}</uri></namespace>"
        }.mkString("\n")
        val update =
          s"""
             |
             | let $$namespacesBlock := doc('$dbName/$dbName.xml')/narthex-dataset/namespaces
             | let $$replacement :=
             |   <namespaces>
             |$namespaces
             |   </namespaces>
             | return replace node $$namespacesBlock with $$replacement
             |
           """.stripMargin.trim
        println("updating:\n" + update)
        session.query(update).execute()
    }
  }
}
