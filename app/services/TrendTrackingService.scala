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

import java.io.File
import org.joda.time.DateTime
import play.api.Logging
import play.api.libs.json._
import services.FileHandling.appender
import services.Temporal.timeToString

import scala.io.Source
import scala.util.{Try, Using}

/**
 * Snapshot of dataset record counts at a point in time.
 */
case class TrendSnapshot(
  timestamp: DateTime,
  snapshotType: String,  // "event" (after SAVE) or "daily" (scheduled)
  sourceRecords: Int,
  acquiredRecords: Int,
  deletedRecords: Int,
  validRecords: Int,
  invalidRecords: Int,
  indexedRecords: Int
)

object TrendSnapshot {
  implicit val dateTimeReads: Reads[DateTime] = Reads { json =>
    json.validate[String].map(s => DateTime.parse(s))
  }
  implicit val dateTimeWrites: Writes[DateTime] = Writes { dt =>
    JsString(timeToString(dt))
  }
  implicit val format: Format[TrendSnapshot] = Json.format[TrendSnapshot]
}

/**
 * Delta (change) in record counts over a time period.
 */
case class TrendDelta(
  source: Int,
  valid: Int,
  indexed: Int
)

object TrendDelta {
  implicit val format: Format[TrendDelta] = Json.format[TrendDelta]

  val zero: TrendDelta = TrendDelta(0, 0, 0)
}

/**
 * Summary of trends for a single dataset.
 */
case class DatasetTrendSummary(
  spec: String,
  currentSource: Int,
  currentValid: Int,
  currentIndexed: Int,
  delta24h: TrendDelta,
  delta7d: TrendDelta,
  delta30d: TrendDelta
)

object DatasetTrendSummary {
  implicit val format: Format[DatasetTrendSummary] = Json.format[DatasetTrendSummary]
}

/**
 * Full trend history for a dataset.
 */
case class DatasetTrends(
  spec: String,
  current: Option[TrendSnapshot],
  delta24h: TrendDelta,
  delta7d: TrendDelta,
  delta30d: TrendDelta,
  history: List[TrendSnapshot]  // Last 30 daily snapshots
)

object DatasetTrends {
  implicit val format: Format[DatasetTrends] = Json.format[DatasetTrends]
}

/**
 * Organization-wide trend summary.
 */
case class OrganizationTrends(
  generatedAt: DateTime,
  totalDatasets: Int,
  totalSourceRecords: Long,
  totalIndexedRecords: Long,
  netDelta24h: TrendDelta,
  growing: List[DatasetTrendSummary],    // datasets with positive delta24h
  shrinking: List[DatasetTrendSummary],  // datasets with negative delta24h
  stable: List[DatasetTrendSummary]      // datasets with zero delta24h
)

object OrganizationTrends {
  implicit val dateTimeReads: Reads[DateTime] = Reads { json =>
    json.validate[String].map(s => DateTime.parse(s))
  }
  implicit val dateTimeWrites: Writes[DateTime] = Writes { dt =>
    JsString(timeToString(dt))
  }
  implicit val dateTimeFormat: Format[DateTime] = Format(dateTimeReads, dateTimeWrites)
  implicit val format: Format[OrganizationTrends] = Json.format[OrganizationTrends]
}

/**
 * Service for tracking dataset publication/depublication trends over time.
 *
 * Captures snapshots of record counts and computes deltas for 24h, 7d, 30d windows.
 * Follows the ActivityLogger pattern for JSONL file storage.
 */
object TrendTrackingService extends Logging {

  private val MAX_HISTORY_DAYS = 30

  /**
   * Capture a trend snapshot for a dataset.
   * Called after SAVE operations complete.
   *
   * @param trendsLog File to append to (typically {datasetDir}/trends.jsonl)
   * @param snapshotType "event" for after-SAVE, "daily" for scheduled
   * @param sourceRecords Total source records (acquiredRecords - deletedRecords)
   * @param acquiredRecords Total records acquired (harvested/uploaded)
   * @param deletedRecords Records marked deleted in OAI-PMH
   * @param validRecords Valid records after processing
   * @param invalidRecords Invalid records after processing
   * @param indexedRecords Records in Hub3 index
   */
  def captureSnapshot(
    trendsLog: File,
    snapshotType: String,
    sourceRecords: Int,
    acquiredRecords: Int,
    deletedRecords: Int,
    validRecords: Int,
    invalidRecords: Int,
    indexedRecords: Int
  ): Unit = {
    val snapshot = TrendSnapshot(
      timestamp = DateTime.now(),
      snapshotType = snapshotType,
      sourceRecords = sourceRecords,
      acquiredRecords = acquiredRecords,
      deletedRecords = deletedRecords,
      validRecords = validRecords,
      invalidRecords = invalidRecords,
      indexedRecords = indexedRecords
    )
    appendSnapshot(trendsLog, snapshot)
    logger.debug(s"Captured $snapshotType snapshot: source=$sourceRecords, valid=$validRecords, indexed=$indexedRecords")
  }

  /**
   * Read all snapshots from a trends file.
   */
  def readSnapshots(trendsLog: File): List[TrendSnapshot] = {
    if (!trendsLog.exists()) {
      return List.empty
    }

    Using(Source.fromFile(trendsLog)) { source =>
      source.getLines()
        .filter(_.nonEmpty)
        .flatMap { line =>
          Try(Json.parse(line).as[TrendSnapshot]).toOption
        }
        .toList
    }.getOrElse(List.empty)
  }

  /**
   * Get the most recent snapshot for a dataset.
   */
  def getLatestSnapshot(trendsLog: File): Option[TrendSnapshot] = {
    readSnapshots(trendsLog).sortBy(_.timestamp.getMillis).lastOption
  }

  /**
   * Get snapshots within a time window.
   */
  def getSnapshotsInWindow(trendsLog: File, hoursAgo: Int): List[TrendSnapshot] = {
    val cutoff = DateTime.now().minusHours(hoursAgo)
    readSnapshots(trendsLog).filter(_.timestamp.isAfter(cutoff))
  }

  /**
   * Calculate delta between two snapshots.
   */
  def calculateDelta(current: TrendSnapshot, previous: TrendSnapshot): TrendDelta = {
    TrendDelta(
      source = current.sourceRecords - previous.sourceRecords,
      valid = current.validRecords - previous.validRecords,
      indexed = current.indexedRecords - previous.indexedRecords
    )
  }

  /**
   * Calculate delta for a time window.
   * Compares most recent snapshot to oldest snapshot within the window.
   */
  def calculateDeltaForWindow(snapshots: List[TrendSnapshot], hoursAgo: Int): TrendDelta = {
    val cutoff = DateTime.now().minusHours(hoursAgo)
    val inWindow = snapshots.filter(_.timestamp.isAfter(cutoff))

    if (inWindow.size < 2) {
      // Not enough data points - check if we have current vs anything older
      val sorted = snapshots.sortBy(_.timestamp.getMillis)
      (sorted.lastOption, sorted.find(_.timestamp.isBefore(cutoff)).orElse(sorted.headOption)) match {
        case (Some(current), Some(previous)) if current != previous =>
          calculateDelta(current, previous)
        case _ =>
          TrendDelta.zero
      }
    } else {
      val sorted = inWindow.sortBy(_.timestamp.getMillis)
      calculateDelta(sorted.last, sorted.head)
    }
  }

  /**
   * Get trends for a single dataset.
   */
  def getDatasetTrends(trendsLog: File, spec: String): DatasetTrends = {
    val snapshots = readSnapshots(trendsLog)
    val dailySnapshots = snapshots
      .filter(_.snapshotType == "daily")
      .sortBy(_.timestamp.getMillis)
      .takeRight(MAX_HISTORY_DAYS)

    DatasetTrends(
      spec = spec,
      current = snapshots.sortBy(_.timestamp.getMillis).lastOption,
      delta24h = calculateDeltaForWindow(snapshots, 24),
      delta7d = calculateDeltaForWindow(snapshots, 24 * 7),
      delta30d = calculateDeltaForWindow(snapshots, 24 * 30),
      history = dailySnapshots
    )
  }

  /**
   * Get trend summary for a dataset (lightweight version for lists).
   */
  def getDatasetTrendSummary(trendsLog: File, spec: String): Option[DatasetTrendSummary] = {
    val snapshots = readSnapshots(trendsLog)
    snapshots.sortBy(_.timestamp.getMillis).lastOption.map { current =>
      DatasetTrendSummary(
        spec = spec,
        currentSource = current.sourceRecords,
        currentValid = current.validRecords,
        currentIndexed = current.indexedRecords,
        delta24h = calculateDeltaForWindow(snapshots, 24),
        delta7d = calculateDeltaForWindow(snapshots, 24 * 7),
        delta30d = calculateDeltaForWindow(snapshots, 24 * 30)
      )
    }
  }

  /**
   * Cleanup old snapshots, keeping only the last N days of daily snapshots
   * and recent event snapshots.
   */
  def cleanupOldSnapshots(trendsLog: File): Unit = {
    val snapshots = readSnapshots(trendsLog)
    val cutoff = DateTime.now().minusDays(MAX_HISTORY_DAYS)

    val toKeep = snapshots.filter { s =>
      s.timestamp.isAfter(cutoff) || s.snapshotType == "daily"
    }.sortBy(_.timestamp.getMillis).takeRight(MAX_HISTORY_DAYS * 2)  // Keep reasonable history

    // Rewrite file with only kept snapshots
    if (toKeep.size < snapshots.size) {
      val tmpFile = new File(trendsLog.getParent, trendsLog.getName + ".tmp")
      toKeep.foreach(s => appendSnapshot(tmpFile, s))
      tmpFile.renameTo(trendsLog)
      logger.info(s"Cleaned up trends file: ${snapshots.size} -> ${toKeep.size} snapshots")
    }
  }

  /**
   * Append a snapshot to the trends file.
   */
  private def appendSnapshot(trendsLog: File, snapshot: TrendSnapshot): Unit = {
    val line = Json.stringify(Json.toJson(snapshot)) + "\n"
    val writer = appender(trendsLog)
    try {
      writer.write(line)
      writer.flush()
    } finally {
      writer.close()
    }
  }
}
