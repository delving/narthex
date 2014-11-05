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

package mapping

import org.basex.server.ClientSession
import play.api.libs.json.{Json, Writes}
import services.BaseX._

import scala.xml.XML

/**
 * @author Gerald de Jong <gerald@delving.eu
 */

object TermDb {

  implicit val mappingWrites = new Writes[TermMapping] {
    def writes(mapping: TermMapping) = Json.obj(
      "source" -> mapping.source,
      "target" -> mapping.target,
      "vocabulary" -> mapping.vocabulary,
      "prefLabel" -> mapping.prefLabel
    )
  }

  case class TermMapping(source: String, target: String, vocabulary: String, prefLabel: String)

}

class TermDb(dbBaseName: String) {

  import mapping.TermDb._
  val name = "term-mappings"
  val termDb = s"${dbBaseName}_terms"
  val dbDoc = s"$termDb/$termDb.xml"
  val dbPath = s"doc('$dbDoc')/$name"

  def withTermDb[T](block: ClientSession => T): T = withDbSession[T](termDb, Some(name))(block)

  def addMapping(mapping: TermMapping) = withTermDb { session =>
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
      | let $$termMapping := $dbPath/term-mapping[source=${quote(mapping.source)}]
      |
      | return
      |   if (exists($$termMapping))
      |   then replace node $$termMapping with $$freshMapping
      |   else insert node $$freshMapping into $dbPath
      |
      """.stripMargin
    session.query(upsert).execute()
  }

  def removeMapping(sourceUri: String) = withTermDb { session =>
    val upsert = s"""
      |
      | let $$termMapping := $dbPath/term-mapping[source=${quote(sourceUri)}]
      |
      | return
      |   if (exists($$termMapping))
      |   then delete node $$termMapping
      |   else ()
      |
      """.stripMargin
    session.query(upsert).execute()
  }

  def getSourcePaths: Seq[String] = withTermDb[Seq[String]] { session =>
    val q = s"""
      |
      | let $$sources := $dbPath/term-mapping/source
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

  def getMappings: Seq[TermMapping] = withTermDb[Seq[TermMapping]] { session =>
    val mappings = session.query(dbPath).execute()
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
