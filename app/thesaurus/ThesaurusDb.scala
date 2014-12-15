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

import scala.xml.{Elem, XML}

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
  def safeName(conceptSchemeName: String) = conceptSchemeName.replaceAll("[^\\w]", "_")
  val name = "thesaurus-mappings"
  val thesaurusDb = s"narthex_thesaurus__${safeName(conceptSchemeA)}__${safeName(conceptSchemeB)}"
  val dbDoc = s"$thesaurusDb/$thesaurusDb.xml"
  val dbPath = s"doc('$dbDoc')/$name"

  def withThesaurusDb[T](block: ClientSession => T): T = withDbSession[T](thesaurusDb, Some(name))(block)

  def toggleMapping(mapping: ThesaurusMapping) = withThesaurusDb { session =>
    val orQuery = s"$dbPath/thesaurus-mapping[uriA=${quote(mapping.uriA)} or uriB=${quote(mapping.uriB)}]"
    val existing = session.query(orQuery).execute().trim

    println(s"existing: $existing")

    if (existing.isEmpty) {
      val insert = s"""
      |
      | let $$freshMapping :=
      |   <thesaurus-mapping>
      |     <uriA>${mapping.uriA}</uriA>
      |     <uriB>${mapping.uriB}</uriB>
      |     <who>${mapping.who}</who>
      |     <when>${mapping.whenString}</when>
      |   </thesaurus-mapping>
      |
      | return insert node $$freshMapping into $dbPath
      |
      """.stripMargin
      session.query(insert).execute()
      true
    }
    else {
      val insert = s"""
      |
      | let $$existingMapping := $orQuery
      | return delete node $$existingMapping
      |
      """.stripMargin
      session.query(insert).execute()
      false
    }
  }

  def getMappings: Seq[ThesaurusMapping] = withThesaurusDb[Seq[ThesaurusMapping]] { session =>
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

  def getMappingsRDF: Elem = withThesaurusDb[Elem] { session =>
    val rdfQuery = s"""
      | declare namespace rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#";
      | declare namespace skos="http://www.w3.org/2004/02/skos/core#";
      | let $$mappings := $dbPath/thesaurus-mapping
      | return
      |   <rdf:RDF>{
      |     for $$m in $$mappings return
      |       <skos:Concept rdf:about="{$$m/uriA}">
      |         <skos:exactMatch rdf:resource="{$$m/uriB}"/>
      |         <skos:note>Mapped in Narthex by {$$m/who/text()} on {$$m/when/text()}</skos:note>
      |       </skos:Concept>
      |   }</rdf:RDF>
      |
      """.stripMargin

    val mappings = session.query(rdfQuery).execute()
    XML.loadString(mappings)
  }

}
