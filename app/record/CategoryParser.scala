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

package record

import java.util.Date

import mapping.CategoryDb.CategoryMapping
import org.apache.poi.hssf.util.HSSFColor
import org.apache.poi.ss.usermodel._
import org.apache.poi.xssf.usermodel._
import play.Logger
import play.api.libs.json.{Json, Writes}
import record.CategoryParser.{CategoryCount, Counter}
import services.{FileHandling, NarthexEventReader, ProgressReporter}

import scala.collection.mutable
import scala.io.Source
import scala.xml.pull._
import scala.xml.{MetaData, NamespaceBinding}

object CategoryParser {

  case class Counter(var count: Int)

  implicit val countWrites = new Writes[CategoryCount] {
    def writes(count: CategoryCount) = Json.obj(
      "category" -> count.category,
      "count" -> count.count,
      "dataset" -> count.dataset
    )
  }

  case class CategoryCount(category: String, count: Int, dataset: String)

  case class CategoryCountCollection(list: List[CategoryCount]) {
    val categories = list.map(_.category).distinct.sorted.zipWithIndex
    val datasets = list.map(_.dataset).distinct.sorted.zipWithIndex

    private def prep = {
      val workbook = new XSSFWorkbook
      val helper = workbook.getCreationHelper
      val boldColor = HSSFColor.GREY_25_PERCENT.index
      val extraBoldColor = HSSFColor.GREY_40_PERCENT.index
      val fillPattern = CellStyle.SOLID_FOREGROUND
      val borderMedium = CellStyle.BORDER_MEDIUM
      val borderThin = CellStyle.BORDER_THIN

      val titleFont = workbook.createFont()
      titleFont.setFamily(FontFamily.MODERN)
      titleFont.setBold(true)
      titleFont.setColor(HSSFColor.DARK_BLUE.index)

      val cornerStyle = workbook.createCellStyle()
      cornerStyle.setFont(titleFont)
      cornerStyle.setAlignment(CellStyle.ALIGN_LEFT)
      cornerStyle.setFillForegroundColor(extraBoldColor)
      cornerStyle.setFillPattern(fillPattern)
      cornerStyle.setBorderBottom(borderMedium)
      cornerStyle.setBorderRight(borderMedium)
      cornerStyle.setBorderTop(borderMedium)
      cornerStyle.setDataFormat(helper.createDataFormat().getFormat("yyyy-mm-dd hh:mm:ss"))

      val categoryStyle = workbook.createCellStyle()
      categoryStyle.setAlignment(CellStyle.ALIGN_CENTER)
      categoryStyle.setFillForegroundColor(boldColor)
      categoryStyle.setFillPattern(fillPattern)
      categoryStyle.setBorderBottom(borderMedium)
      categoryStyle.setBorderTop(borderMedium)
      categoryStyle.setBorderRight(borderThin)
      categoryStyle.setFont(titleFont)

      val sumStyle = workbook.createCellStyle()
      sumStyle.setAlignment(CellStyle.ALIGN_CENTER)
      sumStyle.setFillForegroundColor(boldColor)
      sumStyle.setFillPattern(fillPattern)
      sumStyle.setBorderTop(borderMedium)
      sumStyle.setBorderRight(borderThin)
      sumStyle.setBorderBottom(borderMedium)
      sumStyle.setFont(titleFont)

      val totalStyle = workbook.createCellStyle()
      totalStyle.setFillForegroundColor(extraBoldColor)
      totalStyle.setFillPattern(fillPattern)
      totalStyle.setAlignment(CellStyle.ALIGN_RIGHT)
      totalStyle.setBorderTop(borderMedium)
      totalStyle.setBorderRight(borderMedium)
//      totalStyle.setBorderLeft(borderMedium)
      totalStyle.setBorderBottom(borderMedium)
      totalStyle.setFont(titleFont)

      val datasetStyle = workbook.createCellStyle()
      datasetStyle.setFillForegroundColor(boldColor)
      datasetStyle.setFillPattern(fillPattern)
      datasetStyle.setAlignment(CellStyle.ALIGN_RIGHT)
      datasetStyle.setBorderRight(borderMedium)
//      datasetStyle.setBorderLeft(borderMedium)
      datasetStyle.setFont(titleFont)

      val numberStyle = workbook.createCellStyle()
      numberStyle.setAlignment(CellStyle.ALIGN_CENTER)

      (workbook, cornerStyle, categoryStyle, datasetStyle, numberStyle, totalStyle, sumStyle)
    }

    def categoriesPerDataset: XSSFWorkbook = {
      val (workbook, cornerStyle, categoryStyle, datasetStyle, numberStyle, totalStyle, sumStyle) = prep
      val sheet = workbook.createSheet("Categories per Dataset")
      val row = sheet.createRow(0)
      val corner = row.createCell(0)
      corner.setCellValue(new Date())
      corner.setCellStyle(cornerStyle)
      categories.foreach { categoryI =>
        val col = categoryI._2 + 1
        val colTitle = row.createCell(col)
        colTitle.setCellValue(categoryI._1.toUpperCase.split("-").mkString("\n"))
        colTitle.setCellStyle(categoryStyle)
        sheet.autoSizeColumn(col)
      }
      datasets.foreach { datasetI =>
        val row = sheet.createRow(datasetI._2 + 1)
        val rowTitle = row.createCell(0)
        rowTitle.setCellValue(datasetI._1)
        rowTitle.setCellStyle(datasetStyle)
        categories.foreach { categoryI =>
          val countOpt = list.find(count => count.category == categoryI._1 && count.dataset == datasetI._1)
          countOpt.foreach { count =>
            val cell = row.createCell(categoryI._2 + 1)
            cell.setCellValue(count.count.toDouble)
            cell.setCellStyle(numberStyle)
          }
        }
      }
      val sumRow = sheet.createRow(datasets.last._2 + 2)
      val totalCell = sumRow.createCell(0)
      totalCell.setCellStyle(totalStyle)
      totalCell.setCellValue("Total:")
      categories.foreach { categoryI =>
        val col = categoryI._2 + 1
        val sumCell = sumRow.createCell(col)
        def char(c: Int) = ('A' + c).toChar
        val colName = if (col < 26) s"${char(col)}" else s"${char(col / 26)}${char(col % 26)}"
        val topRow = 2
        val bottomRow = datasets.last._2 + 2
        val formula = s"SUM($colName$topRow:$colName$bottomRow)"
        sumCell.setCellStyle(sumStyle)
        sumCell.setCellFormula(formula)
      }
      sheet.autoSizeColumn(0)
      sheet.createFreezePane(1, 1)
      workbook
    }
  }

}

class CategoryParser(pathPrefix: String, recordRootPath: String, uniqueIdPath: String, deepRecordContainer: Option[String] = None, categoryMappings: Map[String, CategoryMapping]) {
  var countMap = new collection.mutable.HashMap[String, Counter]()
  var percentWas = -1
  var lastProgress = 0l
  var recordCount = 0

  def increment(key: String): Unit = countMap.getOrElseUpdate(key, new Counter(1)).count += 1

  def output(recordCategories: List[String]) = {
    for (
      a <- recordCategories
    ) yield increment(a)
    for (
      a <- recordCategories;
      b <- recordCategories if b > a
    ) yield increment(s"$a-$b")
    for (
      a <- recordCategories;
      b <- recordCategories if b > a;
      c <- recordCategories if c > b
    ) yield increment(s"$a-$b-$c")
    recordCount += 1
  }

  def parse(source: Source, avoidIds: Set[String], progressReporter: ProgressReporter) = {
    val path = new mutable.Stack[(String, StringBuilder)]
    val events = new NarthexEventReader(source)
    var depth = 0
    var recordCategories = new mutable.TreeSet[String]()
    var startTag = false
    var uniqueId: Option[String] = None
    var running = true

    def pathString = path.reverse.map(_._1).mkString("/", "/", "")

    def recordPathString = pathString.substring(pathContainer(recordRootPath).length)

    def pathContainer(string: String) = string.substring(0, string.lastIndexOf("/"))

    def generateUri(value: String) = s"$pathPrefix$recordPathString/${FileHandling.urlEncodeValue(value)}"

    def push(tag: String, attrs: MetaData, scope: NamespaceBinding) = {
      def categoriesFromAttrs() = {
        attrs.foreach { attr =>
          path.push((s"@${attr.prefixedKey}", new StringBuilder()))
          val uri = generateUri(attr.value.toString())
          categoryMappings.get(uri).map(_.categories.foreach(recordCategories += _))
          path.pop()
        }
      }
      def findUniqueId(attrs: MetaData) = attrs.foreach { attr =>
        path.push((s"@${attr.prefixedKey}", new StringBuilder()))
        if (pathString == uniqueIdPath) uniqueId = Some(attr.value.toString())
        path.pop()
      }
      path.push((tag, new StringBuilder()))
      val string = pathString
      if (depth > 0) {
        depth += 1
        startTag = true
        categoriesFromAttrs()
        findUniqueId(attrs)
      }
      else if (string == recordRootPath) {
        if (deepRecordContainer.isEmpty) {
          depth = 1
          startTag = true
          recordCategories.clear()
          categoriesFromAttrs()
        }
        findUniqueId(attrs)
      }
      else deepRecordContainer.foreach { recordContainer =>
        if (pathContainer(string) == recordContainer) {
          depth = 1
          recordCategories.clear()
          startTag = true
          categoriesFromAttrs()
        }
      }
    }

    def addFieldText(text: String) = path.headOption.foreach(_._2.append(text))

    def pop(tag: String) = {
      val string = pathString
      val fieldText = path.head._2
      val text = FileHandling.crunchWhitespace(fieldText.toString())
      fieldText.clear()
      if (depth > 0) {
        // deep record means check container instead
        val hitRecordRoot = deepRecordContainer.map(pathContainer(string) == _).getOrElse(string == recordRootPath)
        if (hitRecordRoot) {
          val categorySet = uniqueId.map { id =>
            if (id.isEmpty) throw new RuntimeException("Empty unique id!")
            if (avoidIds.contains(id)) None else Some(recordCategories.toList.sorted)
          } getOrElse {
            throw new RuntimeException("Missing id!")
          }
          categorySet.foreach(output)
          recordCategories.clear()
          depth = 0
        }
        else {
          if (string == uniqueIdPath) uniqueId = Some(text)
          depth -= 1
          if (startTag && text.nonEmpty) {
            val uri = generateUri(text)
            categoryMappings.get(uri).map { mapping =>
              mapping.categories.foreach { category =>
                recordCategories += category
              }
            }
          }
        }
      }
      else if (deepRecordContainer.isDefined && string == uniqueIdPath) {
        // if there is a deep record, we find the unique idea outside of it
        uniqueId = Some(text)
      }
      path.pop()
    }

    while (events.hasNext && progressReporter.keepReading(recordCount)) {
      events.next() match {
        case EvElemStart(pre, label, attrs, scope) => push(FileHandling.tag(pre, label), attrs, scope)
        case EvText(text) => addFieldText(text)
        case EvEntityRef(entity) => addFieldText(s"&$entity;")
        case EvElemEnd(pre, label) => pop(FileHandling.tag(pre, label))
        case EvComment(text) => FileHandling.stupidParser(text, entity => addFieldText(s"&$entity;"))
        case x => Logger.error("EVENT? " + x)
      }
    }
    progressReporter.keepWorking
  }

  def categoryCounts: List[CategoryCount] = countMap.map(
    count => CategoryCount(count._1, count._2.count, pathPrefix)
  ).toList
}
