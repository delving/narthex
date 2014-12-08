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
import org.joda.time.DateTime
import play.api.libs.json.{Json, Writes}
import services.BaseX._
import services.Temporal

import scala.xml.XML

/**
 *
 * todo: turn this into RDF:
<term-mappings>
  <term-mapping>
    <source>dimcon/afrika_sip_drop/rdf:Description/dc:subject/maten%20en%20gewichten</source>
    <target>http://data.cultureelerfgoed.nl/semnet/1cf92d41-21eb-4a17-b132-25d407ff01a4</target>
    <conceptScheme>Erfgoed Thesaurus</conceptScheme>
    <prefLabel>weefgewichten</prefLabel>
  </term-mapping>
</term-mappings>
like so:
 <skos:Concept rdf:about="dimcon/afrika_sip_drop/rdf:Description/dc:subject/maten%20en%20gewichten">
     <skos:exactMatch rdf:resource="http://data.cultureelerfgoed.nl/semnet/1cf92d41-21eb-4a17-b132-25d407ff01a4"
     <skos:prefLabel>weefgewichten</skos:prefLabel>
     <skos:note>Mapped by {{ username }} on {{ date }}</skos:note>
     <skos:note>https://github.com/delving/narthex</skos:note>
 </skos:Concept>
 *
 * @author Gerald de Jong <gerald@delving.eu
 */

object TermDb {

  implicit val mappingWrites = new Writes[TermMapping] {
    def writes(mapping: TermMapping) = Json.obj(
      "sourceURI" -> mapping.sourceURI,
      "targetURI" -> mapping.targetURI,
      "conceptScheme" -> mapping.conceptScheme,
      "prefLabel" -> mapping.prefLabel,
      "who" -> mapping.who,
      "when" -> mapping.whenString
    )
  }

  case class TermMapping(sourceURI: String, targetURI: String, conceptScheme: String, prefLabel: String, who: String, when: DateTime) {
    val whenString = Temporal.timeToString(when)
  }

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
      |     <sourceURI>${mapping.sourceURI}</sourceURI>
      |     <targetURI>${mapping.targetURI}</targetURI>
      |     <conceptScheme>${mapping.conceptScheme}</conceptScheme>
      |     <prefLabel>${mapping.prefLabel}</prefLabel>
      |     <who>${mapping.who}</who>
      |     <when>${mapping.whenString}</when>
      |   </term-mapping>
      |
      | let $$termMapping := $dbPath/term-mapping[sourceURI=${quote(mapping.sourceURI)}]
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
      | let $$termMapping := $dbPath/term-mapping[sourceURI=${quote(sourceUri)}]
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
      | let $$sources := $dbPath/term-mapping/sourceURI
      |
      | return
      |   <sourceURIs>{
      |     for $$s in $$sources
      |       return <sourceURI>{$$s/text()}</sourceURI>
      |   }</sourceURIs>
      |
      | """.stripMargin
    val result = session.query(q).execute()
    val xml = XML.loadString(result)
    (xml \ "sourceURI").map(n => n.text.substring(0, n.text.lastIndexOf("/"))).distinct
  }

  def getMappings: Seq[TermMapping] = withTermDb[Seq[TermMapping]] { session =>
    val mappings = session.query(dbPath).execute()
    val xml = XML.loadString(mappings)
    (xml \ "term-mapping").map { node =>
      TermMapping(
        (node \ "sourceURI").text,
        (node \ "targetURI").text,
        (node \ "conceptScheme").text,
        (node \ "prefLabel").text,
        (node \ "who").text,
        Temporal.stringToTime((node \ "when").text)
      )
    }
  }

}
