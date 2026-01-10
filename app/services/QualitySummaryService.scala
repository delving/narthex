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
package services

import java.io.File
import javax.inject._
import play.api.libs.json._
import play.api.Logging
import org.apache.commons.io.FileUtils
import organization.OrgContext

/**
 * Field quality information with aggregated issues
 */
case class FieldQuality(
  path: String,
  tag: String,
  completeness: Double,
  emptyRate: Double,
  recordsWithValue: Int,
  totalRecords: Int,
  avgPerRecord: Double,
  totalCount: Int,  // Total number of values
  uniqueCount: Int,  // Number of unique values
  uniqueness: Double,  // Percentage of unique values (uniqueCount / totalCount * 100)
  issues: List[String],
  issueCount: Int,
  typeInfo: Option[JsObject] = None,
  patternInfo: Option[JsObject] = None,
  valueStats: Option[JsObject] = None
)

object FieldQuality {
  implicit val writes: Writes[FieldQuality] = Json.writes[FieldQuality]
}

/**
 * Simplified field info for the "fields in every record" list
 */
case class FieldInEveryRecord(
  path: String,
  tag: String,
  avgPerRecord: Double
)

object FieldInEveryRecord {
  implicit val writes: Writes[FieldInEveryRecord] = Json.writes[FieldInEveryRecord]
}

/**
 * Simplified field info for identifier fields (100% unique values)
 */
case class IdentifierField(
  path: String,
  tag: String,
  uniqueCount: Int,
  totalCount: Int
)

object IdentifierField {
  implicit val writes: Writes[IdentifierField] = Json.writes[IdentifierField]
}

/**
 * Quality summary statistics for a dataset
 */
case class QualitySummary(
  totalFields: Int,
  leafFields: Int,  // Fields with actual values
  totalRecords: Int,
  fieldsWithIssues: Int,
  fieldsInEveryRecord: Int,  // Fields with 100% completeness
  fieldsInEveryRecordList: List[FieldInEveryRecord],  // List of fields with 100% completeness
  identifierFieldsList: List[IdentifierField],  // List of fields with 100% uniqueness
  avgFieldsPerRecord: Double,  // Average number of field values per record
  avgUniqueFieldsPerRecord: Double,  // Average number of unique fields per record
  avgUniqueness: Double,  // Average uniqueness percentage across all fields
  uniquenessDistribution: Map[String, Int],  // Distribution: identifier (100%), high (>80%), medium (20-80%), low (<20%)
  issuesByType: Map[String, Int],
  overallScore: Double,
  completenessDistribution: Map[String, Int],
  problematicFields: List[FieldQuality]  // All fields with issues, sorted by severity
)

object QualitySummary {
  implicit val writes: Writes[QualitySummary] = Json.writes[QualitySummary]
}

/**
 * Service for computing quality summaries from analysis data
 */
@Singleton
class QualitySummaryService @Inject()(
  orgContext: OrgContext
) extends Logging {

  private val LOW_COMPLETENESS_THRESHOLD = 50.0
  private val HIGH_EMPTY_RATE_THRESHOLD = 10.0
  private val TYPE_CONSISTENCY_THRESHOLD = 95.0
  private val URI_VALIDATION_THRESHOLD = 95.0

  /**
   * Get quality summary for a dataset's tree analysis
   */
  def getQualitySummary(spec: String, isSource: Boolean = false): Option[QualitySummary] = {
    val ctx = orgContext.datasetContext(spec)
    val indexFile = if (isSource) ctx.sourceIndex else ctx.index

    if (!indexFile.exists()) {
      logger.debug(s"Index file not found for $spec (source=$isSource)")
      return None
    }

    try {
      val indexJson = Json.parse(FileUtils.readFileToString(indexFile, "UTF-8"))
      val allFields = collectFields(indexJson, ctx, isSource)

      // Filter to leaf fields (fields with values, not containers)
      val leafFields = allFields.filter(f => f.recordsWithValue > 0 || f.completeness > 0)

      val totalRecords = allFields.headOption.map(_.totalRecords).getOrElse(0)
      val fieldsWithIssues = leafFields.filter(_.issueCount > 0)

      // Group issues by type
      val issuesByType = leafFields.flatMap(_.issues).groupBy(identity).map {
        case (issue, occurrences) => issue -> occurrences.size
      }

      // Completeness distribution
      val completenessDistribution = Map(
        "excellent" -> leafFields.count(f => f.completeness >= 90),
        "good" -> leafFields.count(f => f.completeness >= 70 && f.completeness < 90),
        "fair" -> leafFields.count(f => f.completeness >= 50 && f.completeness < 70),
        "poor" -> leafFields.count(f => f.completeness < 50)
      )

      // Fields present in every record (100% completeness)
      val fieldsInEveryRecordList = leafFields
        .filter(f => f.completeness >= 100)
        .sortBy(_.path)
        .map(f => FieldInEveryRecord(f.path, f.tag, f.avgPerRecord))
        .toList
      val fieldsInEveryRecord = fieldsInEveryRecordList.size

      // Average fields per record (sum of avgPerRecord across all leaf fields)
      // This represents how many field values a typical record has
      val avgFieldsPerRecord = if (leafFields.nonEmpty) {
        Math.round(leafFields.map(_.avgPerRecord).sum * 100) / 100.0
      } else 0.0

      // Average unique fields per record
      // This counts how many different fields are typically present (regardless of cardinality)
      // A field contributes 1 if present (completeness > 0), weighted by completeness percentage
      val avgUniqueFieldsPerRecord = if (leafFields.nonEmpty && totalRecords > 0) {
        val totalFieldPresence = leafFields.map(f => f.recordsWithValue.toDouble).sum
        Math.round((totalFieldPresence / totalRecords) * 100) / 100.0
      } else 0.0

      // Uniqueness statistics
      val fieldsWithValues = leafFields.filter(_.totalCount > 0)
      val avgUniqueness = if (fieldsWithValues.nonEmpty) {
        Math.round(fieldsWithValues.map(_.uniqueness).sum / fieldsWithValues.size * 100) / 100.0
      } else 0.0

      // Uniqueness distribution:
      // - identifier: 100% unique (likely identifiers)
      // - high: >80% unique
      // - medium: 20-80% unique
      // - low: <20% unique (controlled vocabularies, repeated values)
      val uniquenessDistribution = Map(
        "identifier" -> fieldsWithValues.count(f => f.uniqueness >= 100),
        "high" -> fieldsWithValues.count(f => f.uniqueness >= 80 && f.uniqueness < 100),
        "medium" -> fieldsWithValues.count(f => f.uniqueness >= 20 && f.uniqueness < 80),
        "low" -> fieldsWithValues.count(f => f.uniqueness < 20)
      )

      // Identifier fields list (100% unique values)
      val identifierFieldsList = fieldsWithValues
        .filter(f => f.uniqueness >= 100)
        .sortBy(_.path)
        .map(f => IdentifierField(f.path, f.tag, f.uniqueCount, f.totalCount))
        .toList

      // Calculate overall score
      val overallScore = calculateOverallScore(leafFields)

      // All problematic fields (sorted by issue count desc, then by completeness asc)
      val allProblematic = fieldsWithIssues
        .sortBy(f => (-f.issueCount, f.completeness))
        .toList

      Some(QualitySummary(
        totalFields = allFields.size,
        leafFields = leafFields.size,
        totalRecords = totalRecords,
        fieldsWithIssues = fieldsWithIssues.size,
        fieldsInEveryRecord = fieldsInEveryRecord,
        fieldsInEveryRecordList = fieldsInEveryRecordList,
        identifierFieldsList = identifierFieldsList,
        avgFieldsPerRecord = avgFieldsPerRecord,
        avgUniqueFieldsPerRecord = avgUniqueFieldsPerRecord,
        avgUniqueness = avgUniqueness,
        uniquenessDistribution = uniquenessDistribution,
        issuesByType = issuesByType,
        overallScore = overallScore,
        completenessDistribution = completenessDistribution,
        problematicFields = allProblematic
      ))
    } catch {
      case e: Exception =>
        logger.error(s"Failed to compute quality summary for $spec: ${e.getMessage}", e)
        None
    }
  }

  /**
   * Recursively collect all fields from the index tree
   */
  private def collectFields(node: JsValue, ctx: dataset.DatasetContext, isSource: Boolean): Seq[FieldQuality] = {
    val path = (node \ "path").asOpt[String].getOrElse("")
    val tag = (node \ "tag").asOpt[String].getOrElse("")
    val quality = (node \ "quality").asOpt[JsObject]
    val kids = (node \ "kids").asOpt[JsArray].getOrElse(Json.arr())
    val lengths = (node \ "lengths").asOpt[JsArray].getOrElse(Json.arr())
    val totalCount = (node \ "count").asOpt[Int].getOrElse(0)  // Total values for this field

    // Only process nodes with quality info
    val currentField = quality.map { q =>
      val completeness = (q \ "completeness").asOpt[Double].getOrElse(0.0)
      val emptyRate = (q \ "emptyRate").asOpt[Double].getOrElse(0.0)
      val recordsWithValue = (q \ "recordsWithValue").asOpt[Int].getOrElse(0)
      val totalRecords = (q \ "totalRecords").asOpt[Int].getOrElse(0)
      val avgPerRecord = (q \ "avgPerRecord").asOpt[Double].getOrElse(0.0)

      // Check for lengths to determine if this is a leaf node with values
      val isLeaf = lengths.value.nonEmpty

      // Load status.json for detailed type/pattern info (only for leaf nodes)
      val statusInfo = if (isLeaf && path.nonEmpty) {
        loadStatusInfo(ctx, path, isSource)
      } else {
        None
      }

      val uniqueCount = statusInfo.map(_.uniqueCount).getOrElse(0)
      val typeInfo = statusInfo.flatMap(_.typeInfo)
      val patternInfo = statusInfo.flatMap(_.patternInfo)
      val valueStats = statusInfo.flatMap(_.valueStats)

      // Calculate uniqueness percentage
      val uniqueness = if (totalCount > 0) {
        Math.round((uniqueCount.toDouble / totalCount) * 10000) / 100.0  // Round to 2 decimals
      } else 0.0

      // Collect issues
      val issues = collectIssues(completeness, emptyRate, typeInfo, patternInfo, valueStats)

      FieldQuality(
        path = path,
        tag = tag,
        completeness = completeness,
        emptyRate = emptyRate,
        recordsWithValue = recordsWithValue,
        totalRecords = totalRecords,
        avgPerRecord = avgPerRecord,
        totalCount = totalCount,
        uniqueCount = uniqueCount,
        uniqueness = uniqueness,
        issues = issues,
        issueCount = issues.size,
        typeInfo = typeInfo,
        patternInfo = patternInfo,
        valueStats = valueStats
      )
    }.toSeq

    // Recursively collect from children
    val childFields = kids.value.flatMap(child => collectFields(child, ctx, isSource))

    currentField ++ childFields
  }

  /**
   * Status info loaded from status.json
   */
  private case class StatusInfo(
    uniqueCount: Int,
    typeInfo: Option[JsObject],
    patternInfo: Option[JsObject],
    valueStats: Option[JsObject]
  )

  /**
   * Load type info, pattern info, value stats, and uniqueCount from status.json
   */
  private def loadStatusInfo(ctx: dataset.DatasetContext, path: String, isSource: Boolean): Option[StatusInfo] = {
    val statusOpt = if (isSource) ctx.sourceStatus(path) else ctx.status(path)

    statusOpt.flatMap { statusFile =>
      try {
        val statusJson = Json.parse(FileUtils.readFileToString(statusFile, "UTF-8"))
        val uniqueCount = (statusJson \ "uniqueCount").asOpt[Int].getOrElse(0)
        val typeInfo = (statusJson \ "typeInfo").asOpt[JsObject]
        val patternInfo = (statusJson \ "patternInfo").asOpt[JsObject]
        val valueStats = (statusJson \ "valueStats").asOpt[JsObject]
        Some(StatusInfo(uniqueCount, typeInfo, patternInfo, valueStats))
      } catch {
        case e: Exception =>
          logger.debug(s"Failed to read status.json for $path: ${e.getMessage}")
          None
      }
    }
  }

  /**
   * Collect issues based on quality metrics
   */
  private def collectIssues(
    completeness: Double,
    emptyRate: Double,
    typeInfo: Option[JsObject],
    patternInfo: Option[JsObject],
    valueStats: Option[JsObject]
  ): List[String] = {
    var issues = List.empty[String]

    // Low completeness
    if (completeness > 0 && completeness < LOW_COMPLETENESS_THRESHOLD) {
      issues = "low_completeness" :: issues
    }

    // High empty rate
    if (emptyRate > HIGH_EMPTY_RATE_THRESHOLD) {
      issues = "high_empty_rate" :: issues
    }

    // Mixed types
    typeInfo.foreach { ti =>
      val isMixed = (ti \ "isMixed").asOpt[Boolean].getOrElse(false)
      val consistency = (ti \ "consistency").asOpt[Double].getOrElse(100.0)
      if (isMixed || consistency < TYPE_CONSISTENCY_THRESHOLD) {
        issues = "mixed_types" :: issues
      }
    }

    // Invalid URIs
    patternInfo.foreach { pi =>
      (pi \ "uriValidation").asOpt[JsObject].foreach { uv =>
        val validRate = (uv \ "validRate").asOpt[Double].getOrElse(100.0)
        val invalid = (uv \ "invalid").asOpt[Int].getOrElse(0)
        if (invalid > 0 && validRate < URI_VALIDATION_THRESHOLD) {
          issues = "invalid_uris" :: issues
        }
      }
    }

    // Whitespace issues
    valueStats.foreach { vs =>
      (vs \ "whitespace").asOpt[JsObject].foreach { ws =>
        val leading = (ws \ "leadingCount").asOpt[Int].getOrElse(0)
        val trailing = (ws \ "trailingCount").asOpt[Int].getOrElse(0)
        val multiple = (ws \ "multipleSpacesCount").asOpt[Int].getOrElse(0)
        if (leading > 0 || trailing > 0 || multiple > 0) {
          issues = "whitespace_issues" :: issues
        }
      }
    }

    issues
  }

  /**
   * Calculate overall quality score (0-100)
   */
  private def calculateOverallScore(fields: Seq[FieldQuality]): Double = {
    if (fields.isEmpty) return 0.0

    // Weighted score calculation
    // Completeness: 40%, Type consistency: 25%, No issues: 20%, Value presence: 15%

    val avgCompleteness = fields.map(_.completeness).sum / fields.size
    val fieldsWithoutIssues = fields.count(_.issueCount == 0).toDouble / fields.size * 100
    val fieldsWithValues = fields.count(_.recordsWithValue > 0).toDouble / fields.size * 100

    // Type consistency score (from typeInfo if available)
    val typeScores = fields.flatMap(_.typeInfo).map { ti =>
      (ti \ "consistency").asOpt[Double].getOrElse(100.0)
    }
    val avgTypeConsistency = if (typeScores.nonEmpty) typeScores.sum / typeScores.size else 100.0

    val score = (avgCompleteness * 0.4) +
                (avgTypeConsistency * 0.25) +
                (fieldsWithoutIssues * 0.20) +
                (fieldsWithValues * 0.15)

    Math.round(score * 10) / 10.0  // Round to 1 decimal
  }

  /**
   * Convert quality summary to CSV format
   */
  def toCSV(summary: QualitySummary, spec: String, isSource: Boolean): String = {
    val sb = new StringBuilder

    // Header section
    sb.append("# Quality Report for Dataset: ").append(spec)
    if (isSource) sb.append(" (Source Analysis)")
    sb.append("\n")
    sb.append("# Generated: ").append(java.time.LocalDateTime.now().toString).append("\n")
    sb.append("\n")

    // Summary statistics
    sb.append("# Summary Statistics\n")
    sb.append("Metric,Value\n")
    sb.append(s"Overall Quality Score,${summary.overallScore}%\n")
    sb.append(s"Total Fields,${summary.totalFields}\n")
    sb.append(s"Leaf Fields (with values),${summary.leafFields}\n")
    sb.append(s"Total Records,${summary.totalRecords}\n")
    sb.append(s"Fields with Issues,${summary.fieldsWithIssues}\n")
    sb.append(s"Fields in Every Record,${summary.fieldsInEveryRecord}\n")
    sb.append(s"Avg Fields per Record,${summary.avgFieldsPerRecord}\n")
    sb.append(s"Avg Unique Fields per Record,${summary.avgUniqueFieldsPerRecord}\n")
    sb.append(s"Avg Uniqueness,${summary.avgUniqueness}%\n")
    sb.append("\n")

    // Completeness distribution
    sb.append("# Completeness Distribution\n")
    sb.append("Category,Count\n")
    sb.append(s"Excellent (>=90%),${summary.completenessDistribution.getOrElse("excellent", 0)}\n")
    sb.append(s"Good (70-89%),${summary.completenessDistribution.getOrElse("good", 0)}\n")
    sb.append(s"Fair (50-69%),${summary.completenessDistribution.getOrElse("fair", 0)}\n")
    sb.append(s"Poor (<50%),${summary.completenessDistribution.getOrElse("poor", 0)}\n")
    sb.append("\n")

    // Uniqueness distribution
    sb.append("# Uniqueness Distribution\n")
    sb.append("Category,Count\n")
    sb.append(s"Identifiers (100%),${summary.uniquenessDistribution.getOrElse("identifier", 0)}\n")
    sb.append(s"High (80-99%),${summary.uniquenessDistribution.getOrElse("high", 0)}\n")
    sb.append(s"Medium (20-79%),${summary.uniquenessDistribution.getOrElse("medium", 0)}\n")
    sb.append(s"Low (<20%),${summary.uniquenessDistribution.getOrElse("low", 0)}\n")
    sb.append("\n")

    // Issues by type
    sb.append("# Issues by Type\n")
    sb.append("Issue Type,Count\n")
    summary.issuesByType.foreach { case (issueType, count) =>
      sb.append(s"$issueType,$count\n")
    }
    sb.append("\n")

    // Fields in every record
    if (summary.fieldsInEveryRecordList.nonEmpty) {
      sb.append("# Fields in Every Record (100% Completeness)\n")
      sb.append("Path,Tag,Avg Per Record\n")
      summary.fieldsInEveryRecordList.foreach { field =>
        sb.append(s"\"${field.path}\",\"${field.tag}\",${field.avgPerRecord}\n")
      }
      sb.append("\n")
    }

    // Identifier fields
    if (summary.identifierFieldsList.nonEmpty) {
      sb.append("# Identifier Fields (100% Unique)\n")
      sb.append("Path,Tag,Unique Count,Total Count\n")
      summary.identifierFieldsList.foreach { field =>
        sb.append(s"\"${field.path}\",\"${field.tag}\",${field.uniqueCount},${field.totalCount}\n")
      }
      sb.append("\n")
    }

    // Problematic fields detail
    sb.append("# All Fields with Quality Metrics\n")
    sb.append("Path,Tag,Completeness %,Empty Rate %,Records With Value,Total Records,Avg Per Record,Total Count,Unique Count,Uniqueness %,Issue Count,Issues\n")
    summary.problematicFields.foreach { field =>
      val issuesStr = field.issues.mkString("; ")
      sb.append(s"\"${field.path}\",\"${field.tag}\",${field.completeness},${field.emptyRate},")
      sb.append(s"${field.recordsWithValue},${field.totalRecords},${field.avgPerRecord},")
      sb.append(s"${field.totalCount},${field.uniqueCount},${field.uniqueness},")
      sb.append(s"${field.issueCount},\"$issuesStr\"\n")
    }

    sb.toString()
  }
}
