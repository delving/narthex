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

import java.io.{File, FileOutputStream}

import com.hp.hpl.jena.rdf.model.ModelFactory
import mapping.CategoriesRepo.Category
import mapping.CategoriesSpreadsheet.CategoryCount
import org.OrgContext._
import play.api.libs.functional.syntax._
import play.api.libs.json.{JsPath, Json, Writes}
import services.FileHandling.writer
import services.{FileHandling, Temporal}
import triplestore.GraphProperties._

import scala.collection.JavaConversions._

object CategoriesRepo {

  implicit val catWrites: Writes[Category] = (
    (JsPath \ "code").write[String] and
      (JsPath \ "details").write[String]
    )(unlift(Category.unapply))

  case class Category(code: String, details: String)

}

class CategoriesRepo(root: File) {
  root.mkdirs()
  val SPREADSHEET = ".xlsx"
  val JSON = ".json"
  val sheets = new File(root, "sheets")
  sheets.mkdir()
  val data = new File(root, "data")
  data.mkdir()

  def listSheets = {
    val sheetFiles = sheets.listFiles.toList.sortBy(_.getName).reverse
    val (show, kill) = sheetFiles.splitAt(10)
    kill.foreach(_.delete())
    show.map(file => file.getName)
  }

  def sheet(name: String) = new File(sheets, name)

  def createSheet(counts: List[CategoryCount]) = {
    val jsonName = Temporal.nowFileName(ORG_ID, JSON)
    val jsonFile = new File(data, jsonName)
    val jsonList = Json.arr(counts)
    val fw = writer(jsonFile)
    fw.write(Json.prettyPrint(jsonList))
    fw.close()
    val sheetName = Temporal.nowFileName(ORG_ID, SPREADSHEET)
    val sheetFile = new File(sheets, sheetName)
    val fos = new FileOutputStream(sheetFile)
    val spreadsheet = new CategoriesSpreadsheet(counts)
    spreadsheet.workbook.write(fos)
    fos.close()
  }

  lazy val categoryListOption: Option[List[Category]] = {
    val file = new File(root, "Categories.xml")
    if (!file.exists) None
    else {
      val model = ModelFactory.createDefaultModel()
      val reader = FileHandling.reader(file)
      model.read(reader, null)
      reader.close()
      val typeProperty = model.getProperty(rdfType)
      val conceptResource = model.getResource(s"${SKOS}Concept")
      val prefLabel = model.getProperty(SKOS, "prefLabel")
      val definitionLabel = model.getProperty(SKOS, "definition")
      val subjects = model.listSubjectsWithProperty(typeProperty, conceptResource).toSeq
      val categoryList = subjects.map(subject => Category(
        code = model.listObjectsOfProperty(subject, prefLabel).map(_.asLiteral().getString).next(),
        details = model.listObjectsOfProperty(subject, definitionLabel).map(_.asLiteral().getString).next()
      )).toList
      Some(categoryList)
    }
  }
}

