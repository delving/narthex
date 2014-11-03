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

import org.basex.core.BaseXException
import org.basex.server.ClientSession
import play.api.libs.json.{Json, Writes}
import services.BaseX._

import scala.xml.XML

/**
 * @author Gerald de Jong <gerald@delving.eu
 */

object CategoryDb {

  implicit val mappingWrites = new Writes[CategoryMapping] {
    def writes(mapping: CategoryMapping) = Json.obj(
      "source" -> mapping.source,
      "target" -> mapping.target
    )
  }

  case class CategoryMapping(source: String, target: String)

}

class CategoryDb(dbName: String) {
  import mapping.CategoryDb._
  val categoryDb = s"${dbName}_categories"

  def db[T](block: ClientSession => T): T = {
    try {
      withDbSession[T](categoryDb)(block)
    }
    catch {
      case be: BaseXException =>
        if (be.getMessage.contains("not found")) {
          createDatabase(categoryDb, "<category-mappings/>")
          withDbSession[T](categoryDb)(block)
        }
        else {
          throw be
        }
    }
  }

  def addMapping(mapping: CategoryMapping) = db {
    session =>
      val upsert = s"""
      |
      | let $$freshMapping :=
      |   <category-mapping>
      |     <source>${mapping.source}</source>
      |     <target>${mapping.target}</target>
      |   </category-mapping>
      |
      | let $$categoryMappings := doc('$categoryDb/$categoryDb.xml')/category-mappings
      |
      | let $$categoryMapping := $$categoryMappings/category-mapping[source=${quote(mapping.source)}]
      |
      | return
      |   if (exists($$categoryMapping))
      |   then replace node $$categoryMapping with $$freshMapping
      |   else insert node $$freshMapping into $$categoryMappings
      |
      """.stripMargin
      session.query(upsert).execute()
  }

  def removeMapping(sourceUri: String) = db {
    session =>
      val upsert = s"""
      |
      | let $$categoryMappings := doc('$categoryDb/$categoryDb.xml')/category-mappings
      | let $$categoryMapping := $$categoryMappings/category-mapping[source=${quote(sourceUri)}]
      |
      | return
      |   if (exists($$categoryMapping))
      |   then delete node $$categoryMapping
      |   else ()
      |
      """.stripMargin
      session.query(upsert).execute()
  }

  def getSourcePaths: Seq[String] = db[Seq[String]] {
    session =>
      val q = s"""
      |
      | let $$sources := doc('$categoryDb/$categoryDb.xml')/category-mappings/category-mapping/source
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

  def getMappings: Seq[CategoryMapping] = db[Seq[CategoryMapping]] {
    session =>
      val mappings = session.query(s"doc('$categoryDb/$categoryDb.xml')/category-mappings").execute()
      val xml = XML.loadString(mappings)
      (xml \ "category-mapping").map { node =>
        CategoryMapping(
          (node \ "source").text,
          (node \ "target").text
        )
      }
  }

}
