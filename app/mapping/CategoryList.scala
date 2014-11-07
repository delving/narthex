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

import play.api.libs.functional.syntax._
import play.api.libs.json._

import scala.io.Source
import scala.xml.NodeSeq

object CategoryList {

  implicit val catWrites: Writes[Category] = (
    (JsPath \ "code").write[String] and
      (JsPath \ "details").write[String]
    )(unlift(Category.unapply))

  implicit val catListWrites: Writes[CategoryList] = (
    (JsPath \ "name").write[String] and
      (JsPath \ "categories").write[Seq[Category]]
    )(unlift(CategoryList.unapply))

  def load(file: File): CategoryList = {
    val markdownChunks = Source.fromFile(file).mkString.split("##").toList
    val title = markdownChunks(0).substring(1).trim
    val categories = markdownChunks.tail.map { chunk =>
      val lines = chunk.split('\n').toList
      val code = lines.head.trim
      val details = lines.tail.mkString(" ").replaceAll("\\s+", " ").trim
      Category(code, details)
    }
    CategoryList(title, categories)
  }

}

case class CategoryList(name: String, categories: Seq[Category])

object Category {
  def apply(node: NodeSeq): Category = {
    Category(
      code = (node \ "@code").text,
      details = (node \ "details").text.trim.replaceAll("\\s+", " ")
    )
  }
}

case class Category(code: String, details: String)

