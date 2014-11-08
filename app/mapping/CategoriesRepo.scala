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

import record.CategoryParser.CategoryCountCollection
import services.{NarthexConfig, Temporal}

class CategoriesRepo(root: File) {
  root.mkdirs()
  val MARKDOWN = ".md"
  val SPREADSHEET = ".xlsx"
  val sheets = new File(root, "sheets")
  sheets.mkdir()

  def listSheets = {
    val sheetFiles = sheets.listFiles.toList.sortBy(_.getName).reverse
    val (show, kill) = sheetFiles.splitAt(10)
    kill.foreach(_.delete())
    show.map(file => file.getName)
  }

  def sheet(name: String) = new File(sheets, name)

  def createSheet(counts: CategoryCountCollection): File = {
    val name = Temporal.nowFileName(NarthexConfig.ORG_ID, SPREADSHEET)
    val file = new File(sheets, name)
    val fos = new FileOutputStream(file)
    counts.categoriesPerDataset.write(fos)
    fos.close()
    file
  }

  def categoryMarkdown(name: String) = new File(root, name.replaceAll(" ", "_") + MARKDOWN)

  lazy val categoryListOption: Option[CategoryList] = {
    val file = new File(root, "categories.md")
    if (file.exists()) Some(CategoryList.load(file)) else None
  }
}
