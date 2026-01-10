//===========================================================================
//    Copyright 2026 Delving B.V.
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

package analysis

import play.api.libs.json.{JsObject, Json}
import scala.util.Try

/**
 * Tracks value statistics during analysis for data quality reporting.
 * Includes length stats, word count, whitespace issues, and type-specific ranges.
 */
object ValueStats {

  // Word splitting pattern - splits on whitespace
  private val WordPattern = """\s+""".r

  // Whitespace issue patterns
  private val LeadingWhitespace = """^\s+""".r
  private val TrailingWhitespace = """\s+$""".r
  private val MultipleSpaces = """\s{2,}""".r

  // Date extraction patterns (extract year for range)
  private val YearPatterns = Seq(
    """^(\d{4})-\d{2}-\d{2}""".r,           // ISO: 2024-01-15
    """^(\d{4})-\d{2}-\d{2}T""".r,          // ISO DateTime
    """\d{2}/\d{2}/(\d{4})$""".r,           // DD/MM/YYYY or MM/DD/YYYY
    """\d{2}-\d{2}-(\d{4})$""".r,           // DD-MM-YYYY
    """^(\d{4})/\d{2}/\d{2}$""".r,          // YYYY/MM/DD
    """^(\d{4})\d{4}$""".r,                 // YYYYMMDD
    """^(\d{4})$""".r                       // Year only
  )

  /**
   * Result of value statistics analysis.
   */
  case class ValueStatistics(
    // Length statistics
    minLength: Int,
    maxLength: Int,
    totalLength: Long,
    valueCount: Int,

    // Word count statistics
    minWords: Int,
    maxWords: Int,
    totalWords: Long,

    // Whitespace issues
    leadingWhitespaceCount: Int,
    trailingWhitespaceCount: Int,
    multipleSpacesCount: Int,

    // Numeric range (if applicable)
    minNumeric: Option[Double],
    maxNumeric: Option[Double],
    numericCount: Int,

    // Date/year range (if applicable)
    minYear: Option[Int],
    maxYear: Option[Int],
    dateCount: Int
  ) {
    def avgLength: Double = if (valueCount > 0) totalLength.toDouble / valueCount else 0.0
    def avgWords: Double = if (valueCount > 0) totalWords.toDouble / valueCount else 0.0

    def hasWhitespaceIssues: Boolean =
      leadingWhitespaceCount > 0 || trailingWhitespaceCount > 0 || multipleSpacesCount > 0

    def whitespaceIssueCount: Int =
      leadingWhitespaceCount + trailingWhitespaceCount + multipleSpacesCount

    def toJson: JsObject = {
      var json = Json.obj(
        "length" -> Json.obj(
          "min" -> minLength,
          "max" -> maxLength,
          "avg" -> BigDecimal(avgLength).setScale(1, BigDecimal.RoundingMode.HALF_UP)
        ),
        "wordCount" -> Json.obj(
          "min" -> minWords,
          "max" -> maxWords,
          "avg" -> BigDecimal(avgWords).setScale(1, BigDecimal.RoundingMode.HALF_UP)
        )
      )

      // Add whitespace issues if present
      if (hasWhitespaceIssues) {
        json = json + ("whitespaceIssues" -> Json.obj(
          "leadingWhitespace" -> leadingWhitespaceCount,
          "trailingWhitespace" -> trailingWhitespaceCount,
          "multipleSpaces" -> multipleSpacesCount,
          "total" -> whitespaceIssueCount
        ))
      }

      // Add numeric range if we found numeric values
      if (numericCount > 0 && minNumeric.isDefined && maxNumeric.isDefined) {
        json = json + ("numericRange" -> Json.obj(
          "min" -> minNumeric.get,
          "max" -> maxNumeric.get,
          "count" -> numericCount
        ))
      }

      // Add year range if we found dates
      if (dateCount > 0 && minYear.isDefined && maxYear.isDefined) {
        json = json + ("dateRange" -> Json.obj(
          "minYear" -> minYear.get,
          "maxYear" -> maxYear.get,
          "count" -> dateCount
        ))
      }

      json
    }
  }

  /**
   * Tracks statistics during streaming analysis.
   */
  class StatsTracker {
    private var minLen = Int.MaxValue
    private var maxLen = 0
    private var totalLen = 0L
    private var count = 0

    private var minWordCount = Int.MaxValue
    private var maxWordCount = 0
    private var totalWordCount = 0L

    private var leadingWs = 0
    private var trailingWs = 0
    private var multipleWs = 0

    private var minNum: Option[Double] = None
    private var maxNum: Option[Double] = None
    private var numCount = 0

    private var minYr: Option[Int] = None
    private var maxYr: Option[Int] = None
    private var dtCount = 0

    def record(value: String): Unit = {
      if (value.isEmpty) return

      count += 1
      val len = value.length
      totalLen += len
      if (len < minLen) minLen = len
      if (len > maxLen) maxLen = len

      // Word count
      val words = WordPattern.split(value.trim).filter(_.nonEmpty).length
      totalWordCount += words
      if (words < minWordCount) minWordCount = words
      if (words > maxWordCount) maxWordCount = words

      // Whitespace issues
      if (LeadingWhitespace.findFirstIn(value).isDefined) leadingWs += 1
      if (TrailingWhitespace.findFirstIn(value).isDefined) trailingWs += 1
      if (MultipleSpaces.findFirstIn(value).isDefined) multipleWs += 1

      // Try to parse as numeric
      Try(value.trim.replace(",", ".").toDouble).toOption.foreach { num =>
        numCount += 1
        minNum = Some(minNum.map(m => Math.min(m, num)).getOrElse(num))
        maxNum = Some(maxNum.map(m => Math.max(m, num)).getOrElse(num))
      }

      // Try to extract year from date-like values
      extractYear(value).foreach { year =>
        if (year >= 1000 && year <= 2100) { // Reasonable year range
          dtCount += 1
          minYr = Some(minYr.map(m => Math.min(m, year)).getOrElse(year))
          maxYr = Some(maxYr.map(m => Math.max(m, year)).getOrElse(year))
        }
      }
    }

    private def extractYear(value: String): Option[Int] = {
      val trimmed = value.trim
      YearPatterns.foreach { pattern =>
        pattern.findFirstMatchIn(trimmed).foreach { m =>
          Try(m.group(1).toInt).toOption.foreach { year =>
            return Some(year)
          }
        }
      }
      None
    }

    def getStatistics: ValueStatistics = {
      ValueStatistics(
        minLength = if (count > 0) minLen else 0,
        maxLength = maxLen,
        totalLength = totalLen,
        valueCount = count,
        minWords = if (count > 0) minWordCount else 0,
        maxWords = maxWordCount,
        totalWords = totalWordCount,
        leadingWhitespaceCount = leadingWs,
        trailingWhitespaceCount = trailingWs,
        multipleSpacesCount = multipleWs,
        minNumeric = minNum,
        maxNumeric = maxNum,
        numericCount = numCount,
        minYear = minYr,
        maxYear = maxYr,
        dateCount = dtCount
      )
    }
  }
}
