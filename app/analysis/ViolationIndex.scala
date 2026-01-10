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

import java.io._
import play.api.libs.json._
import scala.collection.mutable

/**
 * Indexes violations with their record IDs during analysis.
 * Writes violations to a JSONL file for fast lookup later.
 */
class ViolationIndex(violationsFile: File) {

  private val MaxSamplesPerType = 100  // Limit samples per violation type
  private val violationCounts = mutable.Map[String, Int]()
  private var writer: Option[BufferedWriter] = None

  // Violation detection patterns (matching ValueStats)
  private val MojibakePatterns = Seq(
    """Ã©""", """Ã¨""", """Ã«""", """Ã¯""", """Ã¢""", """Ã´""",  // French vowels
    """Ã¼""", """Ã¶""", """Ã¤""",  // German umlauts
    """Ã±""",  // Spanish ñ
    """Ã""", """Â"""  // Common mojibake prefixes
  )

  private val HtmlEntityPattern = "&(?:nbsp|amp|lt|gt|quot|apos|#\\d{1,5}|#x[0-9a-fA-F]{1,4});".r
  private val EscapedPattern = "\\\\[nrt\"'\\\\]".r
  private val ControlCharPattern = "[\u0000-\u0008\u000B\u000C\u000E-\u001F]".r
  private val ReplacementChar = '\uFFFD'

  // Date validation patterns
  private val YearPattern = "\\b(1[0-9]{3}|20[0-9]{2})\\b".r
  private val CurrentYear = java.time.Year.now().getValue

  /**
   * Record a violation to the index.
   */
  def recordViolation(recordId: String, fieldPath: String, value: String, violationType: String): Unit = {
    val typeKey = s"$fieldPath:$violationType"
    val count = violationCounts.getOrElse(typeKey, 0)

    // Only write if under sample limit for this type
    if (count < MaxSamplesPerType) {
      ensureWriter()
      writer.foreach { w =>
        val json = Json.obj(
          "recordId" -> recordId,
          "fieldPath" -> fieldPath,
          "value" -> value.take(500),  // Truncate long values
          "violationType" -> violationType
        )
        w.write(Json.stringify(json))
        w.newLine()
      }
      violationCounts(typeKey) = count + 1
    }
  }

  /**
   * Check a value for violations and record them.
   * Returns true if any violation was found.
   */
  def checkAndRecordViolations(recordId: String, fieldPath: String, value: String): Boolean = {
    if (recordId.isEmpty || value.isEmpty) return false

    var foundViolation = false

    // Check for mojibake
    if (MojibakePatterns.exists(value.contains)) {
      recordViolation(recordId, fieldPath, value, "mojibake")
      foundViolation = true
    }

    // Check for HTML entities
    if (HtmlEntityPattern.findFirstIn(value).isDefined) {
      recordViolation(recordId, fieldPath, value, "html_entities")
      foundViolation = true
    }

    // Check for escaped characters
    if (EscapedPattern.findFirstIn(value).isDefined) {
      recordViolation(recordId, fieldPath, value, "escaped_chars")
      foundViolation = true
    }

    // Check for control characters
    if (ControlCharPattern.findFirstIn(value).isDefined) {
      recordViolation(recordId, fieldPath, value, "control_chars")
      foundViolation = true
    }

    // Check for replacement character
    if (value.contains(ReplacementChar)) {
      recordViolation(recordId, fieldPath, value, "replacement_chars")
      foundViolation = true
    }

    // Check for whitespace issues
    if (value.startsWith(" ") || value.startsWith("\t")) {
      recordViolation(recordId, fieldPath, value, "leading_whitespace")
      foundViolation = true
    }
    if (value.endsWith(" ") || value.endsWith("\t")) {
      recordViolation(recordId, fieldPath, value, "trailing_whitespace")
      foundViolation = true
    }
    if (value.contains("  ")) {
      recordViolation(recordId, fieldPath, value, "multiple_spaces")
      foundViolation = true
    }

    // Check for date outliers (only for values that look like years/dates)
    YearPattern.findFirstIn(value).foreach { yearStr =>
      val year = yearStr.toInt
      if (year > CurrentYear + 1) {
        recordViolation(recordId, fieldPath, value, "future_date")
        foundViolation = true
      } else if (year < 1800 && year > 0) {
        recordViolation(recordId, fieldPath, value, "ancient_date")
        foundViolation = true
      }
    }

    foundViolation
  }

  private def ensureWriter(): Unit = {
    if (writer.isEmpty) {
      violationsFile.getParentFile.mkdirs()
      writer = Some(new BufferedWriter(new FileWriter(violationsFile, false)))
    }
  }

  /**
   * Close the writer and finalize the index.
   */
  def close(): Unit = {
    writer.foreach { w =>
      w.flush()
      w.close()
    }
    writer = None
  }

  /**
   * Get the total number of violations recorded.
   */
  def totalViolations: Int = violationCounts.values.sum
}

object ViolationIndex {

  /**
   * Violation entry read from the index.
   */
  case class ViolationEntry(
    recordId: String,
    fieldPath: String,
    value: String,
    violationType: String
  )

  object ViolationEntry {
    implicit val reads: Reads[ViolationEntry] = Json.reads[ViolationEntry]
    implicit val writes: Writes[ViolationEntry] = Json.writes[ViolationEntry]
  }

  /**
   * Read violations from an index file.
   */
  def readViolations(violationsFile: File): List[ViolationEntry] = {
    if (!violationsFile.exists()) return List.empty

    val source = scala.io.Source.fromFile(violationsFile, "UTF-8")
    try {
      source.getLines().flatMap { line =>
        Json.parse(line).asOpt[ViolationEntry]
      }.toList
    } finally {
      source.close()
    }
  }

  /**
   * Find record IDs for a specific value.
   */
  def findRecordsByValue(violationsFile: File, targetValue: String): List[ViolationEntry] = {
    readViolations(violationsFile).filter(_.value == targetValue)
  }

  /**
   * Find record IDs for a specific violation type.
   */
  def findRecordsByViolationType(violationsFile: File, violationType: String): List[ViolationEntry] = {
    readViolations(violationsFile).filter(_.violationType == violationType)
  }

  /**
   * Get all unique record IDs with violations.
   */
  def getRecordIdsWithViolations(violationsFile: File): Set[String] = {
    readViolations(violationsFile).map(_.recordId).toSet
  }
}
