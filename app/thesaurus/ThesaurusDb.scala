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

package thesaurus

import org.basex.server.ClientSession
import org.joda.time.DateTime
import play.api.libs.json.{Json, Writes}
import services.BaseX._
import services.Temporal

import scala.xml.XML

object ThesaurusDb {

  implicit val mappingWrites = new Writes[ThesaurusMapping] {
    def writes(mapping: ThesaurusMapping) = Json.obj(
      "uriA" -> mapping.uriA,
      "uriB" -> mapping.uriB,
      "who" -> mapping.who,
      "when" -> mapping.whenString
    )
  }

  case class ThesaurusMapping(uriA: String, uriB: String, who: String, when: DateTime) {
    val whenString = Temporal.timeToString(when)
  }

}

class ThesaurusDb(conceptSchemeA: String, conceptSchemeB: String) {
  import thesaurus.ThesaurusDb._
  if (conceptSchemeA >= conceptSchemeB) throw new RuntimeException("Should be alphabetical")
  val name = "thesaurus-mappings"
  val thesaurusDb = "thesaurus"
  def safeName(conceptSchemeName: String) = conceptSchemeName.replaceAll("[^\\w]", "_")
  val dbDoc = s"thesaurus/${safeName(conceptSchemeA)}__${safeName(conceptSchemeB)}.xml"
  val dbPath = s"doc('$dbDoc')/$name"

  def withTermDb[T](block: ClientSession => T): T = withDbSession[T](thesaurusDb, Some(name))(block)

  def addMapping(mapping: ThesaurusMapping) = withTermDb { session =>
    val upsert = s"""
      |
      | let $$freshMapping :=
      |   <thesaurus-mapping>
      |     <uriA>${mapping.uriA}</uriA>
      |     <uriB>${mapping.uriB}</uriB>
      |     <who>${mapping.who}</who>
      |     <when>${mapping.whenString}</when>
      |   </thesaurus-mapping>
      |
      | let $$thesaurusMapping := $dbPath/thesaurus-mapping[uriA=${quote(mapping.uriA)}]
      |
      | return
      |   if (exists($$thesaurusMapping))
      |   then replace node $$thesaurusMapping with $$freshMapping
      |   else insert node $$freshMapping into $dbPath
      |
      """.stripMargin
    session.query(upsert).execute()
  }

  def removeMapping(uri: String) = withTermDb { session =>
    val upsert = s"""
      |
      | let $$thesaurusMapping := $dbPath/thesaurus-mapping[uriA=${quote(uri)} or uriB=${quote(uri)}]
      |
      | return
      |   if (exists($$thesaurusMapping))
      |   then delete node $$thesaurusMapping
      |   else ()
      |
      """.stripMargin
    session.query(upsert).execute()
  }

  def getMappings: Seq[ThesaurusMapping] = withTermDb[Seq[ThesaurusMapping]] { session =>
    val mappings = session.query(dbPath).execute()
    val xml = XML.loadString(mappings)
    (xml \ "thesaurus-mapping").map { node =>
      ThesaurusMapping(
        (node \ "uriA").text,
        (node \ "uriB").text,
        (node \ "who").text,
        Temporal.stringToTime((node \ "when").text)
      )
    }
  }

}
