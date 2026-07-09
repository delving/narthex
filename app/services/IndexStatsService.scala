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
  notProcessed: List[DatasetIndexStats],
  wrongCount: List[DatasetIndexStats],
  deleted: List[DatasetIndexStats],
  disabled: List[DatasetIndexStats]
)

object IndexStatsResponse {
  implicit val writes: Writes[IndexStatsResponse] = Json.writes[IndexStatsResponse]
}

/**
 * Result of a Hub3 index counts fetch. `reachable` distinguishes genuine
 * zero-record responses from network/API failures so trend aggregation can
 * skip when Hub3 is unavailable rather than corrupting indexed counts.
 */
case class Hub3IndexCounts(total: Long, counts: Map[String, Int], reachable: Boolean) {
  // The facet query is capped at IndexStatsService.FACET_LIMIT specs. A spec
  // absent from a full facet response may just have fallen off the truncated
  // list — treating that as 0 would record a fake full de-index.
  def countFor(spec: String): Option[Int] =
    counts.get(spec).orElse(if (counts.size < IndexStatsService.FACET_LIMIT) Some(0) else None)
}

object IndexStatsService {
  val FACET_LIMIT = 1000
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
  def fetchHub3IndexCounts(): Future[Hub3IndexCounts] = {
    // Return empty data when in mock mode
    if (narthexConfig.mockBulkApi) {
      logger.debug("Mock mode enabled - returning empty Hub3 index counts")
      return Future.successful(Hub3IndexCounts(0L, Map.empty, reachable = true))
    }

    val url = s"${narthexConfig.naveApiUrl}/api/search/v2"

    logger.debug(s"Fetching Hub3 index counts from: $url")

    wsClient
      .url(url)
      .withQueryStringParameters(
        "rows" -> "0",
        "facet.field" -> "meta.spec",
        "facet.limit" -> IndexStatsService.FACET_LIMIT.toString
      )
      .withRequestTimeout(hub3Timeout)
      .get()
      .map { response =>
        if (response.status != 200) {
          logger.error(s"Hub3 API error: ${response.status} - ${response.statusText}")
          Hub3IndexCounts(0L, Map.empty, reachable = false)
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
                Hub3IndexCounts(total, specCounts, reachable = true)

              case None =>
                // Hub3 responded with a valid body, but the expected facet is missing.
                // Treat as reachable-but-empty rather than unreachable so downstream
                // callers still get an authoritative "no data for any spec" signal.
                logger.warn("meta.spec facet not found in Hub3 response")
                Hub3IndexCounts(0L, Map.empty, reachable = true)
            }
          } catch {
            case e: Exception =>
              logger.error(s"Failed to parse Hub3 response: ${e.getMessage}", e)
              Hub3IndexCounts(0L, Map.empty, reachable = false)
          }
        }
      }
      .recover {
        case e: Exception =>
          logger.error(s"Failed to fetch Hub3 index counts: ${e.getMessage}", e)
          Hub3IndexCounts(0L, Map.empty, reachable = false)
      }
  }

  // Phase D4: dataset metadata from datasets.db (its own connection —
  // OrgContext injects this service, so no circular DI).
  private lazy val datasetsDb = new DatasetsDb(
    new java.io.File(narthexConfig.narthexDataDir, narthexConfig.orgId))

  def fetchNarthexDatasets(): Future[List[DatasetIndexStats]] = Future.successful {
    datasetsDb.allProps().toList.sortBy(_._1).map { case (spec, p) =>
      def i(k: String): Option[Int] = p.get(k).flatMap(v => scala.util.Try(v.toInt).toOption)
      DatasetIndexStats(
        spec = spec,
        recordCount = i("datasetRecordCount"),
        processedValid = i("processedValid"),
        processedInvalid = i("processedInvalid"),
        acquiredRecordCount = i("acquiredRecordCount"),
        deletedRecordCount = i("deletedRecordCount"),
        sourceRecordCount = i("sourceRecordCount"),
        acquisitionMethod = p.get("acquisitionMethod"),
        indexCount = 0, // Will be filled in later
        deleted = p.get("deleted").contains("true"),
        disabled = p.contains("stateDisabled")
      )
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
      hub3 <- fetchHub3IndexCounts()
    } yield {
      val activeDatasets = narthexDatasets.filter(ds => !ds.deleted && !ds.disabled)

      // Wrong count: indexed but count doesn't match
      val wrongCount = activeDatasets.count { ds =>
        val indexCount = hub3.counts.getOrElse(ds.spec, 0)
        indexCount > 0 && !ds.processedValid.contains(indexCount)
      }

      // Not indexed: has valid records but not in index
      val notIndexedCount = activeDatasets.count { ds =>
        val indexCount = hub3.counts.getOrElse(ds.spec, 0)
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
      hub3 <- fetchHub3IndexCounts()
    } yield {
      val totalIndexed = hub3.total
      val hub3Counts = hub3.counts

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

      val notProcessed = activeDatasets.filter { ds =>
        ds.processedValid.isEmpty && ds.indexCount == 0
      }

      val wrongCount = activeDatasets.filter { ds =>
        ds.indexCount > 0 && !ds.processedValid.contains(ds.indexCount)
      }

      IndexStatsResponse(
        totalIndexed = totalIndexed,
        totalDatasets = activeDatasets.size,
        correct = correct.sortBy(_.spec),
        notIndexed = notIndexed.sortBy(_.spec),
        notProcessed = notProcessed.sortBy(_.spec),
        wrongCount = wrongCount.sortBy(_.spec),
        deleted = deletedDatasets.sortBy(_.spec),
        disabled = disabledDatasets.sortBy(_.spec)
      )
    }
  }
}
