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

package discovery

import javax.inject._
import scala.concurrent.{ExecutionContext, Future}
import org.joda.time.DateTime
import play.api.Logger
import dataset.DsInfo
import dataset.DatasetActor.Command
import organization.OrgContext
import organization.OrgActor.DatasetMessage
import triplestore.GraphProperties._
import triplestore.TripleStore
import discovery.OaiSourceConfig._
import services.Temporal._

/**
 * Service for OAI-PMH dataset discovery and import.
 *
 * Coordinates fetching ListSets from OAI-PMH endpoints,
 * comparing against existing datasets, and importing new sets.
 */
@Singleton
class DatasetDiscoveryService @Inject()(
  orgContext: OrgContext,
  oaiParser: OaiListSetsParser
)(implicit ec: ExecutionContext, ts: TripleStore) {

  private val logger = Logger(getClass)

  // Lazy init the source repo
  lazy val sourceRepo = new OaiSourceRepo(orgContext.orgRoot)

  /**
   * Discover sets from an OAI-PMH source.
   *
   * Fetches ListSets, normalizes specs, checks against existing datasets,
   * applies ignore list, and matches mapping rules.
   */
  def discoverSets(sourceId: String): Future[Either[String, DiscoveryResult]] = {
    sourceRepo.getSource(sourceId) match {
      case None =>
        Future.successful(Left(s"Source not found: $sourceId"))

      case Some(source) =>
        oaiParser.fetchListSets(source.url).flatMap {
          case Left(error) =>
            Future.successful(Left(error))

          case Right(rawSets) =>
            // Get existing dataset specs and harvestDataset values in parallel
            val existingSpecsFuture = checkExistingDatasets()
            val existingHarvestSetsFuture = getExistingHarvestDatasets()

            for {
              existingSpecs <- existingSpecsFuture
              existingHarvestSets <- existingHarvestSetsFuture
            } yield {
              val timestamp = DateTime.now()

              logger.info(s"Comparing against ${existingSpecs.size} existing dataset specs and ${existingHarvestSets.size} harvestDataset values")
              if (existingHarvestSets.nonEmpty) {
                logger.info(s"Sample harvestDataset values: ${existingHarvestSets.take(5).mkString(", ")}")
              }
              if (rawSets.nonEmpty) {
                logger.info(s"Sample OAI setSpecs: ${rawSets.take(5).map(_.setSpec).mkString(", ")}")
              }

              // Process each raw set
              val discoveredSets = rawSets.map { raw =>
                val normalizedSpec = sourceRepo.normalizeSetSpec(raw.setSpec)

                // Determine status - check both normalized spec AND original setSpec in harvestDataset
                val status = if (source.ignoredSets.contains(raw.setSpec)) {
                  "ignored"
                } else if (existingSpecs.contains(normalizedSpec) || existingHarvestSets.contains(raw.setSpec)) {
                  "existing"
                } else {
                  "new"
                }

                // Match mapping rules
                val matchedRule = if (status == "new") {
                  sourceRepo.matchMappingRule(normalizedSpec, source.mappingRules)
                } else {
                  None
                }

                DiscoveredSet(
                  setSpec = raw.setSpec,
                  normalizedSpec = normalizedSpec,
                  setName = raw.setName,
                  title = raw.title,
                  description = raw.description,
                  status = status,
                  matchedMappingRule = matchedRule
                )
              }

              // Update last checked timestamp
              sourceRepo.updateLastChecked(sourceId)

              // Group by status
              val (newSets, existing, ignored) = discoveredSets.foldLeft(
                (List.empty[DiscoveredSet], List.empty[DiscoveredSet], List.empty[DiscoveredSet])
              ) {
                case ((n, e, i), set) =>
                  set.status match {
                    case "new" => (n :+ set, e, i)
                    case "existing" => (n, e :+ set, i)
                    case "ignored" => (n, e, i :+ set)
                    case _ => (n, e, i)
                  }
              }

              // Load cached counts
              val countsCache = sourceRepo.loadCountsCache(sourceId)
              val cachedCounts = countsCache.map(_.counts).getOrElse(Map.empty)
              val countsVerifiedAt = countsCache.map(_.lastVerified)

              // Enrich new sets with cached record counts and re-classify
              val enrichedNew = newSets.map { set =>
                cachedCounts.get(set.setSpec) match {
                  case Some(count) =>
                    set.copy(
                      recordCount = Some(count),
                      countVerifiedAt = countsVerifiedAt
                    )
                  case None => set
                }
              }

              // Split new sets into truly new (has records or unknown) and empty (verified 0)
              val (trulyNew, empty) = enrichedNew.partition { set =>
                !set.recordCount.contains(0)
              }

              // Update empty sets status
              val emptySets = empty.map(_.copy(status = "empty"))

              logger.info(s"Discovery result for ${source.name}: ${trulyNew.size} new, ${existing.size} existing, ${emptySets.size} empty, ${ignored.size} ignored")

              Right(DiscoveryResult(
                sourceId = sourceId,
                sourceName = source.name,
                timestamp = timestamp,
                totalSets = discoveredSets.length,
                newSets = trulyNew,
                existingSets = existing,
                ignoredSets = ignored,
                emptySets = emptySets,
                errors = List.empty,
                countsLastVerified = countsVerifiedAt,
                countsAvailable = countsCache.isDefined
              ))
            }
        }
    }
  }

  /**
   * Import a single set as a new dataset.
   */
  def importSet(request: SetImportRequest): Future[ImportResult] = {
    sourceRepo.getSource(request.sourceId) match {
      case None =>
        Future.successful(ImportResult(request.normalizedSpec, success = false, Some(s"Source not found: ${request.sourceId}")))

      case Some(source) =>
        // Create the dataset
        orgContext.createDsInfo(request.normalizedSpec, "character-mapped", source.defaultPrefix).flatMap { dsInfo =>
          try {
            // Set dataset metadata
            dsInfo.setSingularLiteralProps(
              datasetName -> request.datasetName,
              datasetAggregator -> request.aggregator
            )

            // Set description if provided
            request.datasetDescription.foreach { desc =>
              dsInfo.setSingularLiteralProps(datasetDescription -> desc)
            }

            // Set EDM type if provided
            request.edmType.foreach { edmType =>
              dsInfo.setSingularLiteralProps(triplestore.GraphProperties.edmType -> edmType)
            }

            // Set harvest configuration
            dsInfo.setSingularLiteralProps(
              harvestType -> "pmh",
              harvestURL -> source.url,
              harvestPrefix -> source.defaultMetadataPrefix,
              harvestDataset -> request.setSpec  // Use original setSpec
            )

            // Set harvest scheduling if configured
            // All four properties must be set for currentHarvestCron to work:
            // harvestPreviousTime, harvestDelay, harvestDelayUnit, harvestIncremental
            (source.harvestDelay, source.harvestDelayUnit) match {
              case (Some(delay), Some(unit)) =>
                logger.info(s"Setting harvest schedule for ${request.normalizedSpec}: delay=$delay, unit=$unit, incremental=${source.harvestIncremental}")
                dsInfo.setSingularLiteralProps(
                  harvestPreviousTime -> timeToLocalString(DateTime.now()),
                  harvestDelay -> delay.toString,
                  harvestDelayUnit -> unit,
                  harvestIncremental -> source.harvestIncremental.toString
                )
              case _ =>
                logger.info(s"No harvest schedule configured for ${request.normalizedSpec}")
            }

            // Set default mapping if matched
            (request.mappingPrefix, request.mappingName) match {
              case (Some(prefix), Some(name)) =>
                dsInfo.setMappingSource("default", Some(prefix), Some(name), Some("latest"))
                // Set standard OAI-PMH delimiters so dataset is processable after harvest
                // OAI-PMH has a standard structure, so we can pre-configure these
                dsInfo.setDelimiters(
                  "/OAI-PMH/ListRecords/record",
                  "/OAI-PMH/ListRecords/record/header/identifier"
                )
                logger.info(s"Set default mapping and OAI-PMH delimiters for ${request.normalizedSpec}")
              case _ =>
                // No mapping rule matched
            }

            logger.info(s"Created dataset ${request.normalizedSpec} from OAI set ${request.setSpec}")

            // Start workflow if requested
            if (request.autoStartWorkflow) {
              startImportWorkflow(request.normalizedSpec)
            }

            Future.successful(ImportResult(request.normalizedSpec, success = true))
          } catch {
            case e: Exception =>
              logger.error(s"Failed to configure dataset ${request.normalizedSpec}: ${e.getMessage}", e)
              Future.successful(ImportResult(request.normalizedSpec, success = false, Some(e.getMessage)))
          }
        }.recover {
          case e: Exception =>
            logger.error(s"Failed to create dataset ${request.normalizedSpec}: ${e.getMessage}", e)
            ImportResult(request.normalizedSpec, success = false, Some(e.getMessage))
        }
    }
  }

  /**
   * Import multiple sets.
   */
  def importSets(requests: List[SetImportRequest]): Future[List[ImportResult]] = {
    // Import sequentially to avoid overwhelming the system
    requests.foldLeft(Future.successful(List.empty[ImportResult])) { (acc, request) =>
      acc.flatMap { results =>
        importSet(request).map(result => results :+ result)
      }
    }
  }

  /**
   * Get all existing dataset specs.
   */
  private def checkExistingDatasets(): Future[Set[String]] = {
    DsInfo.listDsInfo(orgContext).map { datasets =>
      datasets.map(_.spec).toSet
    }
  }

  /**
   * Get existing harvestDataset values to detect duplicates.
   * This prevents importing a set that's already configured under a different spec.
   */
  private def getExistingHarvestDatasets(): Future[Set[String]] = {
    val query = s"""
      |PREFIX nx: <${triplestore.GraphProperties.NX_NAMESPACE}>
      |SELECT DISTINCT ?harvestDataset
      |WHERE {
      |  GRAPH ?g {
      |    ?s nx:datasetSpec ?spec .
      |    ?s nx:harvestDataset ?harvestDataset .
      |  }
      |}
    """.stripMargin

    ts.query(query).map { results =>
      results.flatMap(_.get("harvestDataset").map(_.text)).toSet
    }.recover {
      case e: Exception =>
        logger.warn(s"Failed to query existing harvestDatasets: ${e.getMessage}")
        Set.empty[String]
    }
  }

  /**
   * Start the import workflow for a dataset.
   * Workflow: harvest -> make SIP -> process (stops before save)
   *
   * Uses "start first harvest with auto-process" command which:
   * 1. Runs the first harvest (FromScratch)
   * 2. On completion, automatically triggers Make SIP -> Process
   * 3. Stops at PROCESSED state for user review before saving
   */
  private def startImportWorkflow(spec: String): Unit = {
    logger.info(s"Starting import workflow for dataset: $spec")
    // Use command that auto-continues to Make SIP -> Process after harvest
    orgContext.orgActor ! DatasetMessage(spec, Command("start first harvest with auto-process"))
  }
}
