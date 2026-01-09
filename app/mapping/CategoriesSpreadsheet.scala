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

import java.util.Date

import mapping.CategoriesSpreadsheet.CategoryCount
import org.apache.poi.hssf.util.HSSFColor.HSSFColorPredefined
import org.apache.poi.ss.usermodel.{FillPatternType, BorderStyle, FontFamily, HorizontalAlignment}
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import play.api.libs.json.{Json, Writes}

object CategoriesSpreadsheet {

  implicit val countWrites = new Writes[CategoryCount] {
    def writes(count: CategoryCount) = Json.obj(
      "category" -> count.category,
      "count" -> count.count,
      "spec" -> count.spec
    )
  }

  case class CategoryCount(category: String, count: Int, spec: String)


//  case class CategoryMapping(source: String, categories: Seq[String])
//
//  case class Counter(var count: Int)
//

}

class CategoriesSpreadsheet(list: List[CategoryCount]) {

  import mapping.CategoriesSpreadsheet._

  val workbook = new XSSFWorkbook
  val helper = workbook.getCreationHelper
  val boldColor = HSSFColorPredefined.GREY_25_PERCENT.getIndex()
  val extraBoldColor = HSSFColorPredefined.GREY_40_PERCENT.getIndex()
  val fillPattern = FillPatternType.SOLID_FOREGROUND
  val borderMedium = BorderStyle.MEDIUM
  val borderThin = BorderStyle.THIN

  val titleFont = workbook.createFont()
  titleFont.setFamily(FontFamily.MODERN)
  titleFont.setBold(true)
  titleFont.setColor(HSSFColorPredefined.DARK_BLUE.getIndex())

  val cornerStyle = workbook.createCellStyle()
  cornerStyle.setFont(titleFont)
  cornerStyle.setAlignment(HorizontalAlignment.LEFT)
  cornerStyle.setFillForegroundColor(extraBoldColor)
  cornerStyle.setFillPattern(fillPattern)
  cornerStyle.setBorderBottom(borderMedium)
  cornerStyle.setBorderRight(borderMedium)
  cornerStyle.setBorderTop(borderMedium)
  cornerStyle.setDataFormat(helper.createDataFormat().getFormat("yyyy-mm-dd hh:mm:ss"))

  val categoryStyle = workbook.createCellStyle()
  categoryStyle.setAlignment(HorizontalAlignment.CENTER)
  categoryStyle.setFillForegroundColor(boldColor)
  categoryStyle.setFillPattern(fillPattern)
  categoryStyle.setBorderBottom(borderMedium)
  categoryStyle.setBorderTop(borderMedium)
  categoryStyle.setBorderRight(borderThin)
  categoryStyle.setFont(titleFont)

  val totalStyle = workbook.createCellStyle()
  totalStyle.setFillForegroundColor(extraBoldColor)
  totalStyle.setFillPattern(fillPattern)
  totalStyle.setAlignment(HorizontalAlignment.RIGHT)
  totalStyle.setBorderTop(borderMedium)
  totalStyle.setBorderRight(borderMedium)
  totalStyle.setBorderBottom(borderMedium)
  totalStyle.setFont(titleFont)

  val datasetStyle = workbook.createCellStyle()
  datasetStyle.setFillForegroundColor(boldColor)
  datasetStyle.setFillPattern(fillPattern)
  datasetStyle.setAlignment(HorizontalAlignment.RIGHT)
  datasetStyle.setBorderRight(borderMedium)
  datasetStyle.setFont(titleFont)

  val countStyle = workbook.createCellStyle()
  countStyle.setAlignment(HorizontalAlignment.CENTER)

  val countPercentStyle = workbook.createCellStyle()
  countPercentStyle.setAlignment(HorizontalAlignment.CENTER)
  countPercentStyle.setDataFormat(workbook.createDataFormat().getFormat("0.00%"))

  val countSumStyle = workbook.createCellStyle()
  countSumStyle.setAlignment(HorizontalAlignment.CENTER)
  countSumStyle.setFillForegroundColor(boldColor)
  countSumStyle.setFillPattern(fillPattern)
  countSumStyle.setBorderTop(borderMedium)
  countSumStyle.setBorderRight(borderThin)
  countSumStyle.setBorderBottom(borderMedium)
  countSumStyle.setFont(titleFont)

  private def generateSheet(title: String, list: List[CategoryCount], recordCountMap: Map[String, Int], percentages: Boolean): Unit = {
    val categories = list.map(_.category).filter(!_.contains("NULL")).distinct.sorted.zipWithIndex
    val datasets = list.map(_.spec).distinct.sorted.zipWithIndex
    val sheet = workbook.createSheet(title)
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
      val recordCount: Double = recordCountMap.getOrElse(datasetI._1, 1).toDouble
      rowTitle.setCellValue(s"${datasetI._1} ($recordCount)")
      rowTitle.setCellStyle(datasetStyle)
      categories.foreach { categoryI =>
        val countOpt = list.find(count => count.category == categoryI._1 && count.spec == datasetI._1)
        countOpt.foreach { count =>
          val cell = row.createCell(categoryI._2 + 1)
          if (percentages) {
            cell.setCellValue(count.count.toDouble / recordCountMap.getOrElse(datasetI._1, 1))
            cell.setCellStyle(countPercentStyle)
          } else {
            cell.setCellValue(count.count.toDouble)
            cell.setCellStyle(countStyle)
          }
        }
      }
    }
    if (!percentages) {
      datasets.lastOption.foreach { lastDataset =>
        val sumRow = sheet.createRow(lastDataset._2 + 2)
        val totalCell = sumRow.createCell(0)
        totalCell.setCellStyle(totalStyle)
        totalCell.setCellValue("Total:")
        categories.foreach { categoryI =>
          val col = categoryI._2 + 1
          val sumCell = sumRow.createCell(col)
          def char(c: Int) = ('A' + c).toChar
          val colName = if (col < 26) s"${char(col)}" else s"${char(col / 26)}${char(col % 26)}"
          val topRow = 2
          val bottomRow = lastDataset._2 + 2
          val formula = s"SUM($colName$topRow:$colName$bottomRow)"
          sumCell.setCellStyle(countSumStyle)
          sumCell.setCellFormula(formula)
        }
      }
    }
    sheet.autoSizeColumn(0)
    sheet.createFreezePane(1, 1)
  }

  val (multiple, single) = list.partition(cc => cc.category.contains("-"))
  val (triple, double) = multiple.partition(cc => cc.category.indexOf('-') < cc.category.lastIndexOf('-'))
  val recordCountMap = list.filter(_.category == "NULL").map(counter => counter.spec -> counter.count).toMap
  generateSheet("Single Category Counts", single, recordCountMap, percentages = false)
  generateSheet("Single Category Percentages", single, recordCountMap, percentages = true)
  generateSheet("Double Category Counts", double, recordCountMap, percentages = false)
  generateSheet("Double Category Percentages", double, recordCountMap, percentages = true)
  generateSheet("Triple Category Counts", triple, recordCountMap, percentages = false)
  generateSheet("Triple Category Percentages", triple, recordCountMap, percentages = true)
}

