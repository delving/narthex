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

import org.joda.time.DateTime
import play.api.libs.json.{Json, Writes}
import services.Temporal

import scala.xml.Elem

/**
 * @author Gerald de Jong <gerald@delving.eu
 */

object TermDb {

  implicit val mappingWrites = new Writes[TermMapping] {
    def writes(mapping: TermMapping) = Json.obj(
      "sourceURI" -> mapping.sourceURI,
      "targetURI" -> mapping.targetURI,
      "conceptScheme" -> mapping.conceptScheme,
      "attributionName" -> mapping.attributionName,
      "prefLabel" -> mapping.prefLabel,
      "who" -> mapping.who,
      "when" -> mapping.whenString
    )
  }

  case class TermMapping(sourceURI: String, targetURI: String, conceptScheme: String, attributionName: String, prefLabel: String, who: String, when: DateTime) {
    val whenString = Temporal.timeToString(when)
  }

}

class TermDb(dbBaseName: String) {

  import mapping.TermDb._
  val name = "term-mappings"
  val termDb = s"${dbBaseName}_terms"
  val dbDoc = s"$termDb/$termDb.xml"
  val dbPath = s"doc('$dbDoc')/$name"

  def addMapping(mapping: TermMapping) = {}
//  withTermDb { session =>
//    val upsert = s"""
//      |
//      | let $$freshMapping :=
//      |   <term-mapping>
//      |     <sourceURI>${mapping.sourceURI}</sourceURI>
//      |     <targetURI>${mapping.targetURI}</targetURI>
//      |     <conceptScheme>${mapping.conceptScheme}</conceptScheme>
//      |     <attributionName>${mapping.attributionName}</attributionName>
//      |     <prefLabel>${mapping.prefLabel}</prefLabel>
//      |     <who>${mapping.who}</who>
//      |     <when>${mapping.whenString}</when>
//      |   </term-mapping>
//      |
//      | let $$termMapping := $dbPath/term-mapping[sourceURI=${quote(mapping.sourceURI)}]
//      |
//      | return
//      |   if (exists($$termMapping))
//      |   then replace node $$termMapping with $$freshMapping
//      |   else insert node $$freshMapping into $dbPath
//      |
//      """.stripMargin
//    session.query(upsert).execute()
//  }

  def removeMapping(sourceUri: String) = {}
//  withTermDb { session =>
//    val upsert = s"""
//      |
//      | let $$termMapping := $dbPath/term-mapping[sourceURI=${quote(sourceUri)}]
//      |
//      | return
//      |   if (exists($$termMapping))
//      |   then delete node $$termMapping
//      |   else ()
//      |
//      """.stripMargin
//    session.query(upsert).execute()
//  }

  def getSourcePaths: Seq[String] = List("these are not", "source paths")
//    withTermDb[Seq[String]] { session =>
//    val q = s"""
//      |
//      | let $$sources := $dbPath/term-mapping/sourceURI
//      |
//      | return
//      |   <sourceURIs>{
//      |     for $$s in $$sources
//      |       return <sourceURI>{$$s/text()}</sourceURI>
//      |   }</sourceURIs>
//      |
//      | """.stripMargin
//    val result = session.query(q).execute()
//    val xml = XML.loadString(result)
//    (xml \ "sourceURI").map(n => n.text.substring(0, n.text.lastIndexOf("/"))).distinct
//  }

  def getMappings: Seq[TermMapping] = Seq.empty
//    withTermDb[Seq[TermMapping]] { session =>
//    val mappings = session.query(dbPath).execute()
//    val xml = XML.loadString(mappings)
//    (xml \ "term-mapping").map { node =>
//      TermMapping(
//        (node \ "sourceURI").text,
//        (node \ "targetURI").text,
//        (node \ "conceptScheme").text,
//        (node \ "attributionName").text,
//        (node \ "prefLabel").text,
//        (node \ "who").text,
//        Temporal.stringToTime((node \ "when").text)
//      )
//    }
//  }

  def getMappingsRDF: Elem = <not-rdf/>
//    withTermDb[Elem] { session =>
//    val rdfQuery = s"""
//      | declare namespace rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#";
//      | declare namespace skos="http://www.w3.org/2004/02/skos/core#";
//      | let $$mappings := $dbPath/term-mapping
//      | return
//      |   <rdf:RDF>{
//      |     for $$m in $$mappings return
//      |       <skos:Concept rdf:about="{$$m/sourceURI}">
//      |         <skos:exactMatch rdf:resource="{$$m/targetURI}"/>
//      |         <skos:note>Mapped in Narthex by {$$m/who/text()} on {$$m/when/text()}</skos:note>
//      |       </skos:Concept>
//      |   }</rdf:RDF>
//      |
//      """.stripMargin
//
//    val mappings = session.query(rdfQuery).execute()
//    XML.loadString(mappings)
//  }

}
