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
import java.net.URI

/**
 * Detects data types from string values for data quality analysis.
 */
object TypeDetector {

  /** Detected data types */
  object DataType extends Enumeration {
    type DataType = Value
    val TEXT, INTEGER, DECIMAL, DATE, URL, EMAIL, IDENTIFIER, BOOLEAN = Value
  }
  import DataType._

  /** Specific pattern types for identifiers and URIs */
  object PatternType extends Enumeration {
    type PatternType = Value
    val UUID, URN, PREFIX_ID, HTTP_URI, HTTPS_URI, FTP_URI, OTHER_URI, CUSTOM, NONE = Value
  }
  import PatternType.PatternType

  // Regex patterns for type detection
  private val IntegerPattern = """^-?\d+$""".r
  private val DecimalPattern = """^-?\d+[.,]\d+$""".r
  private val UrlPattern = """^(https?|ftp)://[^\s/$.?#].[^\s]*$""".r
  private val EmailPattern = """^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}$""".r
  private val BooleanPattern = """^(true|false|yes|no|0|1)$""".r

  // Date patterns - common formats
  private val DatePatterns = Seq(
    """^\d{4}-\d{2}-\d{2}$""".r,                    // ISO: 2024-01-15
    """^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}.*$""".r, // ISO DateTime
    """^\d{2}/\d{2}/\d{4}$""".r,                    // DD/MM/YYYY or MM/DD/YYYY
    """^\d{2}-\d{2}-\d{4}$""".r,                    // DD-MM-YYYY
    """^\d{4}/\d{2}/\d{2}$""".r,                    // YYYY/MM/DD
    """^\d{8}$""".r,                                // YYYYMMDD
    """^\d{4}$""".r                                 // Year only
  )

  // Specific identifier patterns with names
  private val UuidPattern = """^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$""".r
  private val UrnPattern = """^urn:[a-z0-9][a-z0-9-]*:[^\s]+$""".r
  private val PrefixIdPattern = """^[A-Z]{2,5}[-_]\d+$""".r
  private val HttpUriPattern = """^http://[^\s]+$""".r
  private val HttpsUriPattern = """^https://[^\s]+$""".r
  private val FtpUriPattern = """^ftp://[^\s]+$""".r

  // Identifier patterns - UUIDs, URIs, specific formats
  private val IdentifierPatterns = Seq(
    """^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$""".r, // UUID
    """^urn:[a-z0-9][a-z0-9-]*:[^\s]+$""".r,        // URN
    """^[A-Z]{2,5}[-_]\d+$""".r,                    // PREFIX-123 style IDs
    """^https?://[^\s]+/[^\s]+$""".r                // URI with path (likely identifier)
  )

  /**
   * Detect the type of a single value.
   */
  def detectType(value: String): DataType = {
    val trimmed = value.trim
    if (trimmed.isEmpty) return TEXT

    // Check in order of specificity
    if (BooleanPattern.findFirstIn(trimmed.toLowerCase).isDefined) BOOLEAN
    else if (IntegerPattern.findFirstIn(trimmed).isDefined) INTEGER
    else if (DecimalPattern.findFirstIn(trimmed).isDefined) DECIMAL
    else if (EmailPattern.findFirstIn(trimmed).isDefined) EMAIL
    else if (UrlPattern.findFirstIn(trimmed).isDefined) URL
    else if (DatePatterns.exists(_.findFirstIn(trimmed).isDefined)) DATE
    else if (IdentifierPatterns.exists(_.findFirstIn(trimmed.toLowerCase).isDefined)) IDENTIFIER
    else TEXT
  }

  /**
   * Result of type analysis on a collection of values.
   */
  case class TypeAnalysis(
    dominantType: DataType,
    typeDistribution: Map[DataType, Int],
    totalValues: Int
  ) {
    def consistency: Double = {
      if (totalValues == 0) 100.0
      else (typeDistribution.getOrElse(dominantType, 0).toDouble / totalValues) * 100
    }

    def isMixed: Boolean = typeDistribution.size > 1 && consistency < 95.0

    def toJson: JsObject = {
      val distribution = typeDistribution.map { case (t, count) =>
        t.toString.toLowerCase -> Json.obj(
          "count" -> count,
          "percentage" -> BigDecimal((count.toDouble / totalValues) * 100).setScale(1, BigDecimal.RoundingMode.HALF_UP)
        )
      }
      Json.obj(
        "dominantType" -> dominantType.toString.toLowerCase,
        "consistency" -> BigDecimal(consistency).setScale(1, BigDecimal.RoundingMode.HALF_UP),
        "isMixed" -> isMixed,
        "distribution" -> distribution
      )
    }
  }

  /**
   * Tracks type counts during streaming analysis.
   */
  class TypeCounter {
    private val counts = scala.collection.mutable.Map.empty[DataType, Int]
    private var total = 0

    def record(value: String): Unit = {
      val detectedType = detectType(value)
      counts(detectedType) = counts.getOrElse(detectedType, 0) + 1
      total += 1
    }

    def getAnalysis: TypeAnalysis = {
      if (total == 0) {
        TypeAnalysis(TEXT, Map.empty, 0)
      } else {
        val distribution = counts.toMap
        val dominant = distribution.maxBy(_._2)._1
        TypeAnalysis(dominant, distribution, total)
      }
    }

    def getTotal: Int = total
  }

  /**
   * Analyze a sequence of values and return type analysis.
   * Use for batch analysis of smaller datasets.
   */
  def analyze(values: Seq[String]): TypeAnalysis = {
    val counter = new TypeCounter()
    values.foreach(counter.record)
    counter.getAnalysis
  }

  /**
   * Quick check if a value looks like a date.
   */
  def isDate(value: String): Boolean = {
    DatePatterns.exists(_.findFirstIn(value.trim).isDefined)
  }

  /**
   * Quick check if a value looks like a URL.
   */
  def isUrl(value: String): Boolean = {
    UrlPattern.findFirstIn(value.trim).isDefined
  }

  /**
   * Quick check if a value looks numeric.
   */
  def isNumeric(value: String): Boolean = {
    val trimmed = value.trim
    IntegerPattern.findFirstIn(trimmed).isDefined || DecimalPattern.findFirstIn(trimmed).isDefined
  }

  /**
   * Detect the specific pattern type of a value.
   */
  def detectPattern(value: String): PatternType = {
    val trimmed = value.trim
    if (trimmed.isEmpty) return PatternType.NONE

    if (UuidPattern.findFirstIn(trimmed).isDefined) PatternType.UUID
    else if (UrnPattern.findFirstIn(trimmed.toLowerCase).isDefined) PatternType.URN
    else if (PrefixIdPattern.findFirstIn(trimmed).isDefined) PatternType.PREFIX_ID
    else if (HttpsUriPattern.findFirstIn(trimmed).isDefined) PatternType.HTTPS_URI
    else if (HttpUriPattern.findFirstIn(trimmed).isDefined) PatternType.HTTP_URI
    else if (FtpUriPattern.findFirstIn(trimmed).isDefined) PatternType.FTP_URI
    else PatternType.NONE
  }

  /**
   * Validate if a URI is well-formed.
   * Returns true if valid, false if malformed.
   */
  def isValidUri(value: String): Boolean = {
    val trimmed = value.trim
    if (trimmed.isEmpty) return false
    Try(new URI(trimmed)).map { uri =>
      // Check that it has a scheme (http, https, urn, etc.)
      uri.getScheme != null && uri.getScheme.nonEmpty
    }.getOrElse(false)
  }

  /**
   * Result of pattern analysis on a collection of values.
   */
  case class PatternAnalysis(
    patternDistribution: Map[PatternType, Int],
    totalValues: Int,
    validUriCount: Int,
    invalidUriCount: Int,
    uriLikeCount: Int  // Values that look like URIs (have scheme)
  ) {
    def dominantPattern: PatternType = {
      if (patternDistribution.isEmpty) PatternType.NONE
      else patternDistribution.maxBy(_._2)._1
    }

    def patternConsistency: Double = {
      val nonNonePatterns = patternDistribution.filterKeys(_ != PatternType.NONE)
      if (nonNonePatterns.isEmpty || totalValues == 0) 0.0
      else {
        val patternCount = nonNonePatterns.values.sum
        (patternCount.toDouble / totalValues) * 100
      }
    }

    def hasPatterns: Boolean = patternDistribution.exists(_._1 != PatternType.NONE)

    def hasUriValidation: Boolean = uriLikeCount > 0

    def uriValidRate: Double = {
      if (uriLikeCount == 0) 100.0
      else (validUriCount.toDouble / uriLikeCount) * 100
    }

    def toJson: JsObject = {
      val nonNoneDistribution = patternDistribution.filterKeys(_ != PatternType.NONE)
      if (nonNoneDistribution.isEmpty && uriLikeCount == 0) {
        Json.obj()  // Empty object if no patterns detected
      } else {
        var json = Json.obj()

        if (nonNoneDistribution.nonEmpty) {
          val distribution = nonNoneDistribution.map { case (p, count) =>
            p.toString.toLowerCase -> Json.obj(
              "count" -> count,
              "percentage" -> BigDecimal((count.toDouble / totalValues) * 100).setScale(1, BigDecimal.RoundingMode.HALF_UP)
            )
          }
          json = json ++ Json.obj(
            "dominantPattern" -> dominantPattern.toString.toLowerCase,
            "patternConsistency" -> BigDecimal(patternConsistency).setScale(1, BigDecimal.RoundingMode.HALF_UP),
            "patterns" -> distribution
          )
        }

        if (uriLikeCount > 0) {
          json = json ++ Json.obj(
            "uriValidation" -> Json.obj(
              "total" -> uriLikeCount,
              "valid" -> validUriCount,
              "invalid" -> invalidUriCount,
              "validRate" -> BigDecimal(uriValidRate).setScale(1, BigDecimal.RoundingMode.HALF_UP)
            )
          )
        }

        json
      }
    }
  }

  /**
   * Tracks pattern counts during streaming analysis.
   */
  class PatternTracker {
    private val patternCounts = scala.collection.mutable.Map.empty[PatternType, Int]
    private var total = 0
    private var validUri = 0
    private var invalidUri = 0
    private var uriLike = 0

    // Pattern to detect URI-like values (has a scheme)
    private val UriLikePattern = """^[a-zA-Z][a-zA-Z0-9+.-]*:""".r

    def record(value: String): Unit = {
      val trimmed = value.trim
      if (trimmed.isEmpty) return

      total += 1
      val pattern = detectPattern(trimmed)
      patternCounts(pattern) = patternCounts.getOrElse(pattern, 0) + 1

      // Check URI validation for URI-like values
      if (UriLikePattern.findFirstIn(trimmed).isDefined) {
        uriLike += 1
        if (isValidUri(trimmed)) validUri += 1
        else invalidUri += 1
      }
    }

    def getAnalysis: PatternAnalysis = {
      PatternAnalysis(
        patternDistribution = patternCounts.toMap,
        totalValues = total,
        validUriCount = validUri,
        invalidUriCount = invalidUri,
        uriLikeCount = uriLike
      )
    }
  }
}
