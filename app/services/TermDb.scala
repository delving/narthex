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
import services.BaseX._
import services.TermDb.TermMapping

import scala.xml.XML

/**
 * @author Gerald de Jong <gerald@delving.eu
 */

object TermDb {

  case class TermMapping(source: String, target: String, vocabulary: String, prefLabel: String)

}

class TermDb(dbName: String) {

  val termDb = s"${dbName}_terminology"

  def db[T](block: ClientSession => T): T = {
    try {
      withDbSession[T](termDb)(block)
    }
    catch {
      case be: BaseXException =>
        if (be.getMessage.contains("not found")) {
          createDatabase(termDb, "<term-mappings/>")
          withDbSession[T](termDb)(block)
        }
        else {
          throw be
        }
    }
  }

  def addMapping(mapping: TermMapping) = db {
    session =>
      val upsert = s"""
      |
      | let $$freshMapping :=
      |   <term-mapping>
      |     <source>${mapping.source}</source>
      |     <target>${mapping.target}</target>
      |     <vocabulary>${mapping.vocabulary}</vocabulary>
      |     <prefLabel>${mapping.prefLabel}</prefLabel>
      |   </term-mapping>
      |
      | let $$termMappings := doc('$termDb/$termDb.xml')/term-mappings
      |
      | let $$termMapping := $$termMappings/term-mapping[source=${quote(mapping.source)}]
      |
      | return
      |   if (exists($$termMapping))
      |   then replace node $$termMapping with $$freshMapping
      |   else insert node $$freshMapping into $$termMappings
      |
      """.stripMargin
      session.query(upsert).execute()
  }

  def removeMapping(sourceUri: String) = db {
    session =>
      val upsert = s"""
      |
      | let $$termMappings := doc('$termDb/$termDb.xml')/term-mappings
      | let $$termMapping := $$termMappings/term-mapping[source=${quote(sourceUri)}]
      |
      | return
      |   if (exists($$termMapping))
      |   then delete node $$termMapping
      |   else ()
      |
      """.stripMargin
      session.query(upsert).execute()
  }

  def getSourcePaths: Seq[String] = db[Seq[String]] {
    session =>
      val q = s"""
      |
      | let $$sources := doc('$termDb/$termDb.xml')/term-mappings/term-mapping/source
      |
      | return
      |   <sources>{
      |     for $$s in $$sources
      |       return <source>{$$s/text()}</source>
      |   }</sources>
      |
      | """.stripMargin
      val result = session.query(q).execute()
      val xml = XML.loadString(result)
      (xml \ "source").map(n => n.text.substring(0, n.text.lastIndexOf("/"))).distinct
  }

  def getMappings: Seq[TermMapping] = db[Seq[TermMapping]] {
    session =>
      val mappings = session.query(s"doc('$termDb/$termDb.xml')/term-mappings").execute()
      val xml = XML.loadString(mappings)
      (xml \ "term-mapping").map { node =>
        TermMapping(
          (node \ "source").text,
          (node \ "target").text,
          (node \ "vocabulary").text,
          (node \ "prefLabel").text
        )
      }
  }

}
