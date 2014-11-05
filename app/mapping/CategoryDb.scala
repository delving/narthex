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

object CategoryDb {

  implicit val mappingWrites = new Writes[CategoryMapping] {
    def writes(mapping: CategoryMapping) = Json.obj(
      "source" -> mapping.source,
      "categories" -> mapping.categories
    )
  }

  case class CategoryMapping(source: String, categories: Seq[String])

}

class CategoryDb(dbBaseName: String) {

  import mapping.CategoryDb._
  val name = "category-mappings"
  val categoryDb = s"${dbBaseName}_categories"
  val dbDoc = s"$categoryDb/$categoryDb.xml"
  val dbPath = s"doc('$dbDoc')/$name"

  def withCategoryDb[T](block: ClientSession => T): T = withDbSession[T](categoryDb, Some(name))(block)

  def setMapping(categoryMapping: CategoryMapping, member: Boolean) = withCategoryDb { session =>
    val category = categoryMapping.categories.head
    val source = categoryMapping.source
    val wrapped = s"<$category/>"
    val upsert = s"""
      |
      | let $$freshMapping :=
      |   <category-mapping>
      |     <source>$source</source>
      |     <categories>${if (member) wrapped else ""}</categories>
      |   </category-mapping>
      | let $$categoryMapping := $dbPath/category-mapping[source=${quote(source)}]
      | let $$categoryList := $$categoryMapping/categories
      |
      | return
      |   if (exists($$categoryMapping))
      |   then
      |      if (${!member}() and exists($$categoryList/$category))
      |      then
      |          delete node $$categoryList/$category
      |      else
      |          if ($member() and not(exists($$categoryList/$category)))
      |          then
      |              insert node $wrapped into $$categoryList
      |          else
      |              ()
      |   else
      |      insert node $$freshMapping into $dbPath
      |
      """.stripMargin.trim
    session.query(upsert).execute()
  }

  def getSourcePaths: Seq[String] = withCategoryDb[Seq[String]] { session =>
    val q = s"""
      |
      | let $$sources := $dbPath/category-mapping/source
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

  def getMappings: Seq[CategoryMapping] = withCategoryDb[Seq[CategoryMapping]] { session =>
    val mappings = session.query(dbPath).execute()
    val xml = XML.loadString(mappings)
    (xml \ "category-mapping").map { mappingNode =>
      CategoryMapping(
        (mappingNode \ "source").text,
        (mappingNode \ "categories" \ "_").map(_.label)
      )
    }
  }

}
