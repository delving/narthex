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
package services

import javax.inject._
import play.api.libs.json._
import play.api.Logging
import dataset.DatasetContext
import dataset.SourceRepo.VERBATIM_FILTER
import record.PocketParser.Pocket
import analysis.ViolationIndex

/**
 * Service to find records containing specific values, enabling drill-down
 * from violation samples to source records.
 *
 * Uses the violations index for fast O(1) lookups when available,
 * falls back to scanning source records if index doesn't exist.
 */
@Singleton
class ViolationRecordService @Inject()() extends Logging {

  private val MaxContextLength = 300

  /**
   * A record that matches a searched value.
   */
  case class RecordMatch(
    recordId: String,
    fieldPath: String,
    matchedValue: String,
    context: String,      // XML snippet around match or field path
    matchCount: Int = 1   // How many times value appears in this record
  )

  object RecordMatch {
    implicit val writes: Writes[RecordMatch] = Json.writes[RecordMatch]
  }

  /**
   * Find records containing a specific value.
   * First checks the violations index for instant lookup,
   * falls back to scanning source records if needed.
   *
   * @param datasetContext The dataset to search
   * @param targetValue The value to search for
   * @param limit Maximum number of records to return
   * @param useSourceAnalysis Whether to use source analysis index (default true)
   * @return List of matching records with context
   */
  def findRecordsByValue(
    datasetContext: DatasetContext,
    targetValue: String,
    limit: Int = 100,
    useSourceAnalysis: Boolean = true
  ): List[RecordMatch] = {
    if (targetValue.isEmpty) return List.empty

    // Try the violations index first (fast path)
    val violationsFile = if (useSourceAnalysis) {
      datasetContext.sourceViolationsIndex
    } else {
      datasetContext.violationsIndex
    }

    if (violationsFile.exists()) {
      logger.debug(s"Using violations index for ${datasetContext.dsInfo.spec}")
      val indexMatches = ViolationIndex.findRecordsByValue(violationsFile, targetValue)
      if (indexMatches.nonEmpty) {
        return indexMatches.take(limit).map { entry =>
          RecordMatch(
            recordId = entry.recordId,
            fieldPath = entry.fieldPath,
            matchedValue = entry.value,
            context = s"Field: ${entry.fieldPath} (${entry.violationType})"
          )
        }
      }
    }

    // Fall back to scanning source records (slow path)
    logger.debug(s"Falling back to source scan for ${datasetContext.dsInfo.spec}")
    findRecordsByValueScan(datasetContext, targetValue, limit)
  }

  /**
   * Scan source records to find matches (fallback when index not available).
   */
  private def findRecordsByValueScan(
    datasetContext: DatasetContext,
    targetValue: String,
    limit: Int
  ): List[RecordMatch] = {
    val matches = scala.collection.mutable.ListBuffer[RecordMatch]()

    datasetContext.sourceRepoOpt match {
      case None =>
        logger.warn(s"No source repo for ${datasetContext.dsInfo.spec}")
        List.empty

      case Some(sourceRepo) =>
        val progress = ProgressReporter()

        sourceRepo.parsePockets({ pocket =>
          if (matches.size < limit) {
            findValueInPocket(pocket, targetValue).foreach { result =>
              matches += result
            }
          }
        }, VERBATIM_FILTER, progress)

        matches.toList
    }
  }

  /**
   * Find records containing any of the given values.
   * Useful for finding all records with violations of a specific type.
   *
   * @param datasetContext The dataset to search
   * @param targetValues Set of values to search for
   * @param limit Maximum number of records to return
   * @return List of matching records
   */
  def findRecordsByValues(
    datasetContext: DatasetContext,
    targetValues: Set[String],
    limit: Int = 100
  ): List[RecordMatch] = {
    if (targetValues.isEmpty) return List.empty

    val matches = scala.collection.mutable.ListBuffer[RecordMatch]()
    val seenRecords = scala.collection.mutable.Set[String]()

    datasetContext.sourceRepoOpt match {
      case None =>
        logger.warn(s"No source repo for ${datasetContext.dsInfo.spec}")
        List.empty

      case Some(sourceRepo) =>
        val progress = ProgressReporter()

        sourceRepo.parsePockets({ pocket =>
          if (matches.size < limit && !seenRecords.contains(pocket.id)) {
            // Quick text check before detailed search
            val matchingValues = targetValues.filter(v => pocket.text.contains(v))
            if (matchingValues.nonEmpty) {
              // Found at least one matching value
              val firstMatch = matchingValues.head
              findValueInPocket(pocket, firstMatch).foreach { result =>
                matches += result.copy(matchCount = matchingValues.size)
                seenRecords += pocket.id
              }
            }
          }
        }, VERBATIM_FILTER, progress)

        matches.toList
    }
  }

  /**
   * Search for a value within a pocket's XML and return match details.
   */
  private def findValueInPocket(pocket: Pocket, targetValue: String): Option[RecordMatch] = {
    val text = pocket.text

    // Quick contains check first
    if (!text.contains(targetValue)) {
      return None
    }

    // Extract context around the match
    val context = extractContext(text, targetValue)

    Some(RecordMatch(
      recordId = pocket.id,
      fieldPath = "",  // We don't track exact path in simple text search
      matchedValue = targetValue,
      context = context
    ))
  }

  /**
   * Extract a context snippet around the matched value.
   */
  private def extractContext(fullText: String, matchedValue: String): String = {
    val idx = fullText.indexOf(matchedValue)
    if (idx < 0) return matchedValue

    val contextPadding = 100
    val start = Math.max(0, idx - contextPadding)
    val end = Math.min(fullText.length, idx + matchedValue.length + contextPadding)

    var context = fullText.substring(start, end)

    // Clean up: try to start/end at tag boundaries for readability
    if (start > 0) {
      val tagStart = context.indexOf('<')
      if (tagStart > 0 && tagStart < 20) {
        context = context.substring(tagStart)
      } else {
        context = "..." + context
      }
    }

    if (end < fullText.length) {
      val tagEnd = context.lastIndexOf('>')
      if (tagEnd > context.length - 20 && tagEnd > 0) {
        context = context.substring(0, tagEnd + 1)
      } else {
        context = context + "..."
      }
    }

    // Truncate if still too long
    if (context.length > MaxContextLength) {
      context = context.take(MaxContextLength) + "..."
    }

    context
  }

  /**
   * Count how many records contain a specific value (without returning all matches).
   * Faster than findRecordsByValue when you only need the count.
   */
  def countRecordsWithValue(
    datasetContext: DatasetContext,
    targetValue: String
  ): Int = {
    if (targetValue.isEmpty) return 0

    var count = 0

    datasetContext.sourceRepoOpt.foreach { sourceRepo =>
      val progress = ProgressReporter()

      sourceRepo.parsePockets({ pocket =>
        if (pocket.text.contains(targetValue)) {
          count += 1
        }
      }, VERBATIM_FILTER, progress)
    }

    count
  }

  /**
   * Export result for problem records.
   */
  case class ProblemRecordExport(
    spec: String,
    searchValue: String,
    violationType: String,
    totalRecords: Int,
    recordIds: List[String]
  )

  object ProblemRecordExport {
    implicit val writes: Writes[ProblemRecordExport] = Json.writes[ProblemRecordExport]
  }

  /**
   * Get all record IDs containing a specific value (for export).
   * Uses the violations index when available for fast lookup.
   *
   * @param datasetContext The dataset to search
   * @param targetValue The value to search for
   * @param violationType Optional violation type for metadata
   * @param useSourceAnalysis Whether to use source analysis index (default true)
   * @return Export result with all matching record IDs
   */
  def exportProblemRecordIds(
    datasetContext: DatasetContext,
    targetValue: String,
    violationType: String = "",
    useSourceAnalysis: Boolean = true
  ): ProblemRecordExport = {
    if (targetValue.isEmpty) {
      return ProblemRecordExport(
        spec = datasetContext.dsInfo.spec,
        searchValue = targetValue,
        violationType = violationType,
        totalRecords = 0,
        recordIds = List.empty
      )
    }

    // Try the violations index first (fast path)
    val violationsFile = if (useSourceAnalysis) {
      datasetContext.sourceViolationsIndex
    } else {
      datasetContext.violationsIndex
    }

    if (violationsFile.exists()) {
      logger.debug(s"Using violations index for export: ${datasetContext.dsInfo.spec}")
      val indexMatches = ViolationIndex.findRecordsByValue(violationsFile, targetValue)
      if (indexMatches.nonEmpty) {
        val recordIds = indexMatches.map(_.recordId).distinct
        return ProblemRecordExport(
          spec = datasetContext.dsInfo.spec,
          searchValue = targetValue,
          violationType = violationType,
          totalRecords = recordIds.size,
          recordIds = recordIds
        )
      }
    }

    // Fall back to scanning source records (slow path)
    logger.debug(s"Falling back to source scan for export: ${datasetContext.dsInfo.spec}")
    val recordIds = scala.collection.mutable.ListBuffer[String]()

    datasetContext.sourceRepoOpt.foreach { sourceRepo =>
      val progress = ProgressReporter()

      sourceRepo.parsePockets({ pocket =>
        if (pocket.text.contains(targetValue)) {
          recordIds += pocket.id
        }
      }, VERBATIM_FILTER, progress)
    }

    ProblemRecordExport(
      spec = datasetContext.dsInfo.spec,
      searchValue = targetValue,
      violationType = violationType,
      totalRecords = recordIds.size,
      recordIds = recordIds.toList
    )
  }

  /**
   * Get all record IDs containing any of the given values (for batch export).
   *
   * @param datasetContext The dataset to search
   * @param targetValues Set of values to search for
   * @param violationType Optional violation type for metadata
   * @return Export result with all matching record IDs (deduplicated)
   */
  def exportProblemRecordIdsByValues(
    datasetContext: DatasetContext,
    targetValues: Set[String],
    violationType: String = ""
  ): ProblemRecordExport = {
    if (targetValues.isEmpty) {
      return ProblemRecordExport(
        spec = datasetContext.dsInfo.spec,
        searchValue = targetValues.mkString(", "),
        violationType = violationType,
        totalRecords = 0,
        recordIds = List.empty
      )
    }

    val recordIds = scala.collection.mutable.LinkedHashSet[String]()

    datasetContext.sourceRepoOpt.foreach { sourceRepo =>
      val progress = ProgressReporter()

      sourceRepo.parsePockets({ pocket =>
        // Check if pocket contains any of the target values
        if (targetValues.exists(v => pocket.text.contains(v))) {
          recordIds += pocket.id
        }
      }, VERBATIM_FILTER, progress)
    }

    ProblemRecordExport(
      spec = datasetContext.dsInfo.spec,
      searchValue = s"${targetValues.size} values",
      violationType = violationType,
      totalRecords = recordIds.size,
      recordIds = recordIds.toList
    )
  }

  /**
   * Format export as CSV string.
   */
  def formatAsCsv(problemExport: ProblemRecordExport): String = {
    val sb = new StringBuilder
    sb.append("record_id\n")
    problemExport.recordIds.foreach { id =>
      sb.append(id).append("\n")
    }
    sb.toString()
  }
}
