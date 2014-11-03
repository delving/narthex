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

import java.io.File

import org.OrgRepo
import play.api.libs.functional.syntax._
import play.api.libs.json._

import scala.xml.{NodeSeq, XML}

object CategoryList {

  implicit val catWrites: Writes[Category] = (
    (JsPath \ "code").write[String] and
      (JsPath \ "name").write[String] and
      (JsPath \ "details").write[String]
    )(unlift(Category.unapply))

  implicit val catListWrites: Writes[CategoryList] = (
    (JsPath \ "name").write[String] and
      (JsPath \ "categories").write[Seq[Category]]
    )(unlift(CategoryList.unapply))

  def load(file: File): CategoryList = {
    val xml = XML.loadFile(file)
    CategoryList((xml \ "@name").text, (xml \ "category").map(c => Category(c)))
  }

  lazy val listOption: Option[CategoryList] = {
    val file = new File(OrgRepo.repo.categoriesDir, "categories.xml")
    if (file.exists()) {
      Some(CategoryList.load(file))
    } 
    else None
  }

}

case class CategoryList(name: String, categories: Seq[Category])

object Category {
  def apply(node: NodeSeq): Category = {
    Category(
      code = (node \ "@code").text,
      name = (node \ "name").text.trim,
      details = (node \ "details").text.trim.replaceAll("\\s+", " ")
    )
  }
}

case class Category(code: String, name: String, details: String)

