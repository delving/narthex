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
import init.NarthexConfig
import play.api.Logging
import play.api.libs.json._
import play.api.libs.ws.WSClient
import triplestore.TripleStore
import triplestore.Sparql.selectIndexStatsQ

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._

/**
 * Data model for dataset index statistics
 */
case class DatasetIndexStats(
  spec: String,
  recordCount: Option[Int],
  processedValid: Option[Int],
  processedInvalid: Option[Int],
  // Acquisition tracking fields
  acquiredRecordCount: Option[Int],
  deletedRecordCount: Option[Int],
  sourceRecordCount: Option[Int],
  acquisitionMethod: Option[String],
  // Index count from Hub3
  indexCount: Int,
  // Status flags
  deleted: Boolean,
  disabled: Boolean
)

object DatasetIndexStats {
  implicit val writes: Writes[DatasetIndexStats] = Json.writes[DatasetIndexStats]
}

/**
 * Response model for index stats endpoint
 */
case class IndexStatsResponse(
  totalIndexed: Long,
  totalDatasets: Int,
  correct: List[DatasetIndexStats],
  notIndexed: List[DatasetIndexStats],
  wrongCount: List[DatasetIndexStats],
  deleted: List[DatasetIndexStats],
  disabled: List[DatasetIndexStats]
)

object IndexStatsResponse {
  implicit val writes: Writes[IndexStatsResponse] = Json.writes[IndexStatsResponse]
}

/**
 * Service for fetching and comparing dataset statistics between Narthex and Hub3 index
 */
@Singleton
class IndexStatsService @Inject()(
  narthexConfig: NarthexConfig,
  wsClient: WSClient
)(implicit ec: ExecutionContext, ts: TripleStore) extends Logging {

  private val hub3Timeout = 30.seconds

  /**
   * Fetch index counts from Hub3 API using facet aggregation
   * @return Map of spec -> count, and total indexed documents
   */
  def fetchHub3IndexCounts(): Future[(Long, Map[String, Int])] = {
    val url = s"${narthexConfig.naveApiUrl}/api/search/v2"

    logger.debug(s"Fetching Hub3 index counts from: $url")

    wsClient
      .url(url)
      .withQueryStringParameters(
        "rows" -> "0",
        "facet.field" -> "meta.spec",
        "facet.limit" -> "1000"
      )
      .withRequestTimeout(hub3Timeout)
      .get()
      .map { response =>
        if (response.status != 200) {
          logger.error(s"Hub3 API error: ${response.status} - ${response.statusText}")
          (0L, Map.empty[String, Int])
        } else {
          try {
            val json = response.json

            // Find the meta.spec facet in the facets array
            val facets = (json \ "facets").asOpt[JsArray].getOrElse(JsArray.empty)
            val specFacet = facets.value.find { facet =>
              (facet \ "name").asOpt[String].contains("meta.spec")
            }

            specFacet match {
              case Some(facet) =>
                val total = (facet \ "total").asOpt[Long].getOrElse(0L)
                val links = (facet \ "links").asOpt[JsArray].getOrElse(JsArray.empty)

                val specCounts = links.value.flatMap { link =>
                  for {
                    value <- (link \ "value").asOpt[String]
                    count <- (link \ "count").asOpt[Int]
                  } yield value -> count
                }.toMap

                logger.info(s"Fetched Hub3 index counts: $total total, ${specCounts.size} specs")
                (total, specCounts)

              case None =>
                logger.warn("meta.spec facet not found in Hub3 response")
                (0L, Map.empty[String, Int])
            }
          } catch {
            case e: Exception =>
              logger.error(s"Failed to parse Hub3 response: ${e.getMessage}", e)
              (0L, Map.empty[String, Int])
          }
        }
      }
      .recover {
        case e: Exception =>
          logger.error(s"Failed to fetch Hub3 index counts: ${e.getMessage}", e)
          (0L, Map.empty[String, Int])
      }
  }

  /**
   * Fetch dataset metadata from SPARQL triplestore
   * @return List of dataset stats from Narthex
   */
  def fetchNarthexDatasets(): Future[List[DatasetIndexStats]] = {
    ts.query(selectIndexStatsQ).map { results =>
      results.map { row =>
        // Parse state timestamps to detect "stale disabled" datasets
        // A dataset is considered re-enabled if any workflow state is >1 day newer than stateDisabled
        val stateDisabledOpt = row.get("stateDisabled").map(_.text).filter(_.nonEmpty)
        val stateSavedOpt = row.get("stateSaved").map(_.text).filter(_.nonEmpty)
        val stateProcessedOpt = row.get("stateProcessed").map(_.text).filter(_.nonEmpty)
        val stateAnalyzedOpt = row.get("stateAnalyzed").map(_.text).filter(_.nonEmpty)
        val stateIncrementalSavedOpt = row.get("stateIncrementalSaved").map(_.text).filter(_.nonEmpty)

        val isDisabled = stateDisabledOpt.exists { disabledTime =>
          val disabledMillis = parseTimestamp(disabledTime)
          val oneDayMs = 24 * 60 * 60 * 1000L

          // Check if any other state is >1 day newer than disabled
          val otherStates = List(stateSavedOpt, stateProcessedOpt, stateAnalyzedOpt, stateIncrementalSavedOpt).flatten
          val isStaleDisabled = otherStates.exists { stateTime =>
            parseTimestamp(stateTime) > (disabledMillis + oneDayMs)
          }

          !isStaleDisabled  // Only disabled if NOT stale
        }

        DatasetIndexStats(
          spec = row("spec").text,
          recordCount = row.get("recordCount").flatMap(v => scala.util.Try(v.text.toInt).toOption),
          processedValid = row.get("processedValid").flatMap(v => scala.util.Try(v.text.toInt).toOption),
          processedInvalid = row.get("processedInvalid").flatMap(v => scala.util.Try(v.text.toInt).toOption),
          // Acquisition tracking fields
          acquiredRecordCount = row.get("acquiredRecordCount").flatMap(v => scala.util.Try(v.text.toInt).toOption),
          deletedRecordCount = row.get("deletedRecordCount").flatMap(v => scala.util.Try(v.text.toInt).toOption),
          sourceRecordCount = row.get("sourceRecordCount").flatMap(v => scala.util.Try(v.text.toInt).toOption),
          acquisitionMethod = row.get("acquisitionMethod").map(_.text),
          indexCount = 0, // Will be filled in later
          deleted = row.get("deleted").exists(_.text == "true"),
          disabled = isDisabled
        )
      }
    }.recover {
      case e: Exception =>
        logger.error(s"Failed to fetch Narthex datasets: ${e.getMessage}", e)
        List.empty
    }
  }

  /**
   * Parse a timestamp string to milliseconds.
   * Handles ISO format timestamps from the triplestore.
   */
  private def parseTimestamp(timestamp: String): Long = {
    try {
      org.joda.time.DateTime.parse(timestamp).getMillis
    } catch {
      case _: Exception => 0L
    }
  }

  /**
   * Get counts of datasets with index issues (lightweight for polling)
   * @return Tuple of (wrongCount, notIndexedCount)
   */
  def getIndexAlertCounts(): Future[(Int, Int)] = {
    for {
      narthexDatasets <- fetchNarthexDatasets()
      (_, hub3Counts) <- fetchHub3IndexCounts()
    } yield {
      val activeDatasets = narthexDatasets.filter(ds => !ds.deleted && !ds.disabled)

      // Wrong count: indexed but count doesn't match
      val wrongCount = activeDatasets.count { ds =>
        val indexCount = hub3Counts.getOrElse(ds.spec, 0)
        indexCount > 0 && !ds.processedValid.contains(indexCount)
      }

      // Not indexed: has valid records but not in index
      val notIndexedCount = activeDatasets.count { ds =>
        val indexCount = hub3Counts.getOrElse(ds.spec, 0)
        indexCount == 0 && ds.processedValid.exists(_ > 0)
      }

      (wrongCount, notIndexedCount)
    }
  }

  /**
   * Get combined index statistics from both Narthex and Hub3
   * @return Categorized index statistics
   */
  def getIndexStats(): Future[IndexStatsResponse] = {
    for {
      narthexDatasets <- fetchNarthexDatasets()
      (totalIndexed, hub3Counts) <- fetchHub3IndexCounts()
    } yield {
      // Merge Hub3 counts into Narthex datasets
      val mergedDatasets = narthexDatasets.map { ds =>
        ds.copy(indexCount = hub3Counts.getOrElse(ds.spec, 0))
      }

      // Log specs in Hub3 but not in Narthex (orphaned)
      val narthexSpecs = narthexDatasets.map(_.spec).toSet
      hub3Counts.keys.filterNot(narthexSpecs.contains).foreach { spec =>
        logger.warn(s"Spec '$spec' in Hub3 index but not in Narthex")
      }

      // Separate deleted datasets first
      val (deletedDatasets, nonDeletedDatasets) = mergedDatasets.partition(_.deleted)

      // Separate disabled datasets from active datasets
      val (disabledDatasets, activeDatasets) = nonDeletedDatasets.partition(_.disabled)

      // Categorize active datasets (not deleted and not disabled)
      val correct = activeDatasets.filter { ds =>
        ds.processedValid.exists(_ == ds.indexCount)
      }

      val notIndexed = activeDatasets.filter { ds =>
        ds.indexCount == 0 && ds.processedValid.exists(_ > 0)
      }

      val wrongCount = activeDatasets.filter { ds =>
        ds.indexCount > 0 && !ds.processedValid.contains(ds.indexCount)
      }

      IndexStatsResponse(
        totalIndexed = totalIndexed,
        totalDatasets = activeDatasets.size,
        correct = correct.sortBy(_.spec),
        notIndexed = notIndexed.sortBy(_.spec),
        wrongCount = wrongCount.sortBy(_.spec),
        deleted = deletedDatasets.sortBy(_.spec),
        disabled = disabledDatasets.sortBy(_.spec)
      )
    }
  }
}
