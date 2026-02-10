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

import play.api.libs.json.{JsObject, Json, Writes}
import scala.collection.mutable
import scala.util.Try

/**
 * Tracks value statistics during analysis for data quality reporting.
 * Includes length stats, word count, whitespace issues, encoding issues, and type-specific ranges.
 */
object ValueStats {

  // Maximum number of violation samples to collect per type
  private val MaxViolationSamples = 10

  /**
   * A sample of a value that triggered a violation.
   * Used to show users examples of problematic data.
   */
  case class ViolationSample(value: String, violationType: String)

  object ViolationSample {
    implicit val writes: Writes[ViolationSample] = Json.writes[ViolationSample]
  }

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

  // ===== Encoding Issues Detection =====

  // Mojibake patterns - UTF-8 interpreted as Latin-1/Windows-1252
  // These are common garbled character sequences
  private val MojibakePatterns = Seq(
    """Ã©""".r,    // é
    """Ã¨""".r,    // è
    """Ã«""".r,    // ë
    """Ã¯""".r,    // ï
    """Ã¢""".r,    // â
    """Ã´""".r,    // ô
    """Ã¼""".r,    // ü
    """Ã¶""".r,    // ö
    """Ã¤""".r,    // ä
    """Ã±""".r,    // ñ
    """Ã§""".r,    // ç
    """Ã¡""".r,    // á
    """Ã­""".r,    // í
    """Ã³""".r,    // ó
    """Ãº""".r,    // ú
    """â€""".r,   // Various punctuation mojibake (—, ", ", etc.)
    """â€œ""".r,  // "
    """â€""".r, // " (right double quote)
    """â€˜""".r,  // '
    """â€™""".r,  // '
    """Ââ€""".r,  // Common double-encoding pattern
    """Ã¿""".r,   // ÿ
    """Â""".r     // Stray  character (often from encoding issues)
  )

  // HTML entities that should have been decoded
  private val HtmlEntityPattern = """&(amp|lt|gt|quot|apos|nbsp|#\d{1,5}|#x[0-9a-fA-F]{1,4});""".r

  // Escaped characters that shouldn't appear in displayed text
  private val EscapedCharsPattern = """\\[nrtbf"'\\]""".r

  // Control characters (excluding tab, newline, carriage return which might be intentional)
  private val ControlCharsPattern = """[\x00-\x08\x0B\x0C\x0E-\x1F\x7F]""".r

  // Replacement character (indicates encoding failure)
  private val ReplacementCharPattern = "\uFFFD".r

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
    dateCount: Int,

    // Encoding issues
    mojibakeCount: Int = 0,
    htmlEntitiesCount: Int = 0,
    escapedCharsCount: Int = 0,
    controlCharsCount: Int = 0,
    replacementCharCount: Int = 0,

    // Outlier detection (Phase 13)
    futureDateCount: Int = 0,      // Dates in the future
    ancientDateCount: Int = 0,     // Dates before 1800
    suspiciousYearCount: Int = 0,  // Years outside 1000-2100 range
    negativeNumberCount: Int = 0,  // Negative numbers (may be suspicious in some contexts)
    zeroCount: Int = 0,            // Zero values
    extremeNumericCount: Int = 0,  // Values that are statistically extreme

    // Violation samples (Phase 14) - for drill-down to records
    encodingSamples: List[ViolationSample] = List.empty,
    whitespaceSamples: List[ViolationSample] = List.empty,
    outlierSamples: List[ViolationSample] = List.empty
  ) {
    def avgLength: Double = if (valueCount > 0) totalLength.toDouble / valueCount else 0.0
    def avgWords: Double = if (valueCount > 0) totalWords.toDouble / valueCount else 0.0

    def hasWhitespaceIssues: Boolean =
      leadingWhitespaceCount > 0 || trailingWhitespaceCount > 0 || multipleSpacesCount > 0

    def whitespaceIssueCount: Int =
      leadingWhitespaceCount + trailingWhitespaceCount + multipleSpacesCount

    def hasEncodingIssues: Boolean =
      mojibakeCount > 0 || htmlEntitiesCount > 0 || escapedCharsCount > 0 ||
      controlCharsCount > 0 || replacementCharCount > 0

    def encodingIssueCount: Int =
      mojibakeCount + htmlEntitiesCount + escapedCharsCount + controlCharsCount + replacementCharCount

    def hasOutliers: Boolean =
      futureDateCount > 0 || ancientDateCount > 0 || suspiciousYearCount > 0

    def outlierCount: Int =
      futureDateCount + ancientDateCount + suspiciousYearCount

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
        var wsJson = Json.obj(
          "leadingCount" -> leadingWhitespaceCount,
          "trailingCount" -> trailingWhitespaceCount,
          "multipleSpacesCount" -> multipleSpacesCount,
          "total" -> whitespaceIssueCount
        )
        if (whitespaceSamples.nonEmpty) {
          wsJson = wsJson + ("samples" -> Json.toJson(whitespaceSamples))
        }
        json = json + ("whitespace" -> wsJson)
      }

      // Add encoding issues if present
      if (hasEncodingIssues) {
        var encJson = Json.obj(
          "mojibake" -> mojibakeCount,
          "htmlEntities" -> htmlEntitiesCount,
          "escapedChars" -> escapedCharsCount,
          "controlChars" -> controlCharsCount,
          "replacementChars" -> replacementCharCount,
          "total" -> encodingIssueCount
        )
        if (encodingSamples.nonEmpty) {
          encJson = encJson + ("samples" -> Json.toJson(encodingSamples))
        }
        json = json + ("encodingIssues" -> encJson)
      }

      // Add numeric range if we found numeric values (guard against non-finite doubles)
      if (numericCount > 0 && minNumeric.isDefined && maxNumeric.isDefined
        && minNumeric.get.isFinite && maxNumeric.get.isFinite) {
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

      // Add outlier information if present
      if (hasOutliers || negativeNumberCount > 0 || zeroCount > 0) {
        var outlierJson = Json.obj()
        if (futureDateCount > 0) outlierJson = outlierJson + ("futureDates" -> Json.toJson(futureDateCount))
        if (ancientDateCount > 0) outlierJson = outlierJson + ("ancientDates" -> Json.toJson(ancientDateCount))
        if (suspiciousYearCount > 0) outlierJson = outlierJson + ("suspiciousYears" -> Json.toJson(suspiciousYearCount))
        if (negativeNumberCount > 0) outlierJson = outlierJson + ("negativeNumbers" -> Json.toJson(negativeNumberCount))
        if (zeroCount > 0) outlierJson = outlierJson + ("zeros" -> Json.toJson(zeroCount))
        if (extremeNumericCount > 0) outlierJson = outlierJson + ("extremeValues" -> Json.toJson(extremeNumericCount))
        outlierJson = outlierJson + ("total" -> Json.toJson(outlierCount))
        if (outlierSamples.nonEmpty) {
          outlierJson = outlierJson + ("samples" -> Json.toJson(outlierSamples))
        }
        json = json + ("outliers" -> outlierJson)
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

    // Encoding issues counters
    private var mojibake = 0
    private var htmlEntities = 0
    private var escapedChars = 0
    private var controlChars = 0
    private var replacementChars = 0

    // Outlier counters
    private var futureDates = 0
    private var ancientDates = 0
    private var suspiciousYears = 0
    private var negativeNums = 0
    private var zeros = 0
    private var extremeNums = 0

    // Current year for future date detection
    private val currentYear = java.time.Year.now().getValue

    // Violation sample collectors (bounded to MaxViolationSamples)
    private val encodingSampleList = mutable.ListBuffer[ViolationSample]()
    private val whitespaceSampleList = mutable.ListBuffer[ViolationSample]()
    private val outlierSampleList = mutable.ListBuffer[ViolationSample]()

    private def addEncodingSample(value: String, violationType: String): Unit = {
      if (encodingSampleList.size < MaxViolationSamples) {
        encodingSampleList += ViolationSample(value.take(500), violationType)  // Truncate long values
      }
    }

    private def addWhitespaceSample(value: String, violationType: String): Unit = {
      if (whitespaceSampleList.size < MaxViolationSamples) {
        whitespaceSampleList += ViolationSample(value.take(500), violationType)
      }
    }

    private def addOutlierSample(value: String, violationType: String): Unit = {
      if (outlierSampleList.size < MaxViolationSamples) {
        outlierSampleList += ViolationSample(value.take(500), violationType)
      }
    }

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

      // Whitespace issues (with sample collection)
      if (LeadingWhitespace.findFirstIn(value).isDefined) {
        leadingWs += 1
        addWhitespaceSample(value, "leading")
      }
      if (TrailingWhitespace.findFirstIn(value).isDefined) {
        trailingWs += 1
        addWhitespaceSample(value, "trailing")
      }
      if (MultipleSpaces.findFirstIn(value).isDefined) {
        multipleWs += 1
        addWhitespaceSample(value, "multipleSpaces")
      }

      // Encoding issues detection (with sample collection)
      if (hasMojibake(value)) {
        mojibake += 1
        addEncodingSample(value, "mojibake")
      }
      if (HtmlEntityPattern.findFirstIn(value).isDefined) {
        htmlEntities += 1
        addEncodingSample(value, "htmlEntities")
      }
      if (EscapedCharsPattern.findFirstIn(value).isDefined) {
        escapedChars += 1
        addEncodingSample(value, "escapedChars")
      }
      if (ControlCharsPattern.findFirstIn(value).isDefined) {
        controlChars += 1
        addEncodingSample(value, "controlChars")
      }
      if (ReplacementCharPattern.findFirstIn(value).isDefined) {
        replacementChars += 1
        addEncodingSample(value, "replacementChars")
      }

      // Try to parse as numeric (filter out Infinity/NaN which can't be serialized to JSON)
      Try(value.trim.replace(",", ".").toDouble).toOption.filter(d => d.isFinite).foreach { num =>
        numCount += 1
        minNum = Some(minNum.map(m => Math.min(m, num)).getOrElse(num))
        maxNum = Some(maxNum.map(m => Math.max(m, num)).getOrElse(num))

        // Outlier detection for numbers
        if (num < 0) negativeNums += 1
        if (num == 0) zeros += 1
      }

      // Try to extract year from date-like values
      extractYear(value).foreach { year =>
        // Outlier detection for dates (with sample collection)
        if (year > currentYear) {
          futureDates += 1
          addOutlierSample(value, "futureDates")
        } else if (year < 1800) {
          ancientDates += 1
          addOutlierSample(value, "ancientDates")
        }

        if (year < 1000 || year > 2100) {
          suspiciousYears += 1
          addOutlierSample(value, "suspiciousYears")
        } else {
          // Only count reasonable years in the date range
          dtCount += 1
          minYr = Some(minYr.map(m => Math.min(m, year)).getOrElse(year))
          maxYr = Some(maxYr.map(m => Math.max(m, year)).getOrElse(year))
        }
      }
    }

    private def hasMojibake(value: String): Boolean = {
      MojibakePatterns.exists(_.findFirstIn(value).isDefined)
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
        dateCount = dtCount,
        mojibakeCount = mojibake,
        htmlEntitiesCount = htmlEntities,
        escapedCharsCount = escapedChars,
        controlCharsCount = controlChars,
        replacementCharCount = replacementChars,
        futureDateCount = futureDates,
        ancientDateCount = ancientDates,
        suspiciousYearCount = suspiciousYears,
        negativeNumberCount = negativeNums,
        zeroCount = zeros,
        extremeNumericCount = extremeNums,
        // Violation samples for drill-down
        encodingSamples = encodingSampleList.toList,
        whitespaceSamples = whitespaceSampleList.toList,
        outlierSamples = outlierSampleList.toList
      )
    }
  }
}
