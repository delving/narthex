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

import org.joda.time.DateTime
import org.joda.time.format.ISODateTimeFormat
import play.api.libs.json._
import play.api.libs.functional.syntax._

/**
 * Data models for OAI-PMH Dataset Discovery feature.
 *
 * Allows configuring OAI-PMH endpoints as discovery sources, fetching ListSets,
 * comparing against existing datasets, and bulk-importing new sets.
 */
object OaiSourceConfig {

  // Joda DateTime JSON format
  implicit val jodaDateTimeReads: Reads[DateTime] = Reads[DateTime] { json =>
    json.validate[String].map { str =>
      ISODateTimeFormat.dateTimeParser().parseDateTime(str)
    }
  }

  implicit val jodaDateTimeWrites: Writes[DateTime] = Writes[DateTime] { dt =>
    JsString(ISODateTimeFormat.dateTime().print(dt))
  }

  /**
   * A regex pattern rule for matching setSpecs to default mappings.
   *
   * @param pattern  Regex pattern to match against normalized spec (e.g., ".*bidprentje$")
   * @param prefix   Schema prefix for the default mapping (e.g., "edm")
   * @param mappingName  Named mapping within the prefix (e.g., "bidprentje")
   */
  case class MappingRule(
    pattern: String,
    prefix: String,
    mappingName: String
  )

  implicit val mappingRuleFormat: Format[MappingRule] = Json.format[MappingRule]

  /**
   * Configuration for an OAI-PMH discovery source.
   *
   * @param id                     Unique identifier (slug generated from name)
   * @param name                   Display name for the source
   * @param url                    OAI-PMH base URL
   * @param defaultMetadataPrefix  Default metadataPrefix for harvesting (e.g., "oai_dc", "edm")
   * @param defaultAggregator      Default aggregator value for imported datasets
   * @param defaultPrefix          Default schema prefix for new datasets
   * @param defaultEdmType         Default EDM type (e.g., "IMAGE", "TEXT")
   * @param harvestDelay           Scheduling interval value
   * @param harvestDelayUnit       Scheduling unit ("DAYS", "WEEKS", "MONTHS")
   * @param harvestIncremental     Whether to use incremental harvesting
   * @param mappingRules           Regex rules for auto-assigning default mappings
   * @param ignoredSets            setSpecs to exclude from discovery results
   * @param enabled                Whether this source is active
   * @param lastChecked            Timestamp of last discovery check
   * @param createdAt              Timestamp when source was created
   */
  case class OaiSource(
    id: String,
    name: String,
    url: String,
    defaultMetadataPrefix: String,
    defaultAggregator: String,
    defaultPrefix: String,
    defaultEdmType: Option[String] = None,
    harvestDelay: Option[Int] = None,
    harvestDelayUnit: Option[String] = None,
    harvestIncremental: Boolean = false,
    mappingRules: List[MappingRule] = List.empty,
    ignoredSets: List[String] = List.empty,
    enabled: Boolean = true,
    lastChecked: Option[DateTime] = None,
    createdAt: DateTime = DateTime.now()
  )

  // Custom Reads to handle missing/empty fields with defaults
  implicit val oaiSourceReads: Reads[OaiSource] = (
    (__ \ "id").readNullable[String].map(_.filter(_.nonEmpty).getOrElse("")) and
    (__ \ "name").read[String] and
    (__ \ "url").read[String] and
    (__ \ "defaultMetadataPrefix").readNullable[String].map(_.getOrElse("oai_dc")) and
    (__ \ "defaultAggregator").readNullable[String].map(_.getOrElse("")) and
    (__ \ "defaultPrefix").readNullable[String].map(_.getOrElse("edm")) and
    (__ \ "defaultEdmType").readNullable[String].map(_.filter(_.nonEmpty)) and
    (__ \ "harvestDelay").readNullable[Int] and
    (__ \ "harvestDelayUnit").readNullable[String] and
    (__ \ "harvestIncremental").readNullable[Boolean].map(_.getOrElse(false)) and
    (__ \ "mappingRules").readNullable[List[MappingRule]].map(_.getOrElse(List.empty)) and
    (__ \ "ignoredSets").readNullable[List[String]].map(_.getOrElse(List.empty)) and
    (__ \ "enabled").readNullable[Boolean].map(_.getOrElse(true)) and
    (__ \ "lastChecked").readNullable[DateTime] and
    (__ \ "createdAt").readNullable[DateTime].map(_.getOrElse(DateTime.now()))
  )(OaiSource.apply _)

  implicit val oaiSourceWrites: Writes[OaiSource] = Json.writes[OaiSource]

  implicit val oaiSourceFormat: Format[OaiSource] = Format(oaiSourceReads, oaiSourceWrites)

  /**
   * A set discovered from an OAI-PMH ListSets response.
   *
   * @param setSpec           Original setSpec from OAI-PMH (e.g., "enb_05.documenten")
   * @param normalizedSpec    Transformed spec for Narthex (e.g., "enb-05-documenten")
   * @param setName           From <setName> element
   * @param title             From dc:title in setDescription (if present)
   * @param description       From dc:description in setDescription (if present)
   * @param status            "new", "existing", or "ignored"
   * @param matchedMappingRule  Which mapping rule matched, if any
   */
  case class DiscoveredSet(
    setSpec: String,
    normalizedSpec: String,
    setName: String,
    title: Option[String],
    description: Option[String],
    status: String,
    matchedMappingRule: Option[MappingRule]
  )

  implicit val discoveredSetFormat: Format[DiscoveredSet] = Json.format[DiscoveredSet]

  /**
   * Result of a discovery operation against an OAI-PMH source.
   *
   * @param sourceId   ID of the source that was queried
   * @param sourceName Display name of the source
   * @param timestamp  When the discovery was performed
   * @param totalSets  Total number of sets found
   * @param newSets    Sets that don't exist in Narthex and aren't ignored
   * @param existingSets  Sets that already exist as datasets
   * @param ignoredSets   Sets that are in the ignore list
   * @param errors     Any errors encountered during discovery
   */
  case class DiscoveryResult(
    sourceId: String,
    sourceName: String,
    timestamp: DateTime,
    totalSets: Int,
    newSets: List[DiscoveredSet],
    existingSets: List[DiscoveredSet],
    ignoredSets: List[DiscoveredSet],
    errors: List[String]
  )

  implicit val discoveryResultFormat: Format[DiscoveryResult] = Json.format[DiscoveryResult]

  /**
   * Request to import a single set as a new dataset.
   *
   * @param sourceId          ID of the OAI source
   * @param setSpec           Original setSpec (used for harvestDataset)
   * @param normalizedSpec    Transformed spec (used as dataset identifier)
   * @param datasetName       Display name for the dataset
   * @param datasetDescription  Description for the dataset
   * @param aggregator        Aggregator value
   * @param edmType           EDM type value
   * @param mappingPrefix     Default mapping prefix (if matched)
   * @param mappingName       Default mapping name (if matched)
   * @param autoStartWorkflow Whether to auto-start harvest workflow
   */
  case class SetImportRequest(
    sourceId: String,
    setSpec: String,
    normalizedSpec: String,
    datasetName: String,
    datasetDescription: Option[String],
    aggregator: String,
    edmType: Option[String],
    mappingPrefix: Option[String],
    mappingName: Option[String],
    autoStartWorkflow: Boolean = true
  )

  implicit val setImportRequestFormat: Format[SetImportRequest] = Json.format[SetImportRequest]

  /**
   * Result of an import operation.
   */
  case class ImportResult(
    spec: String,
    success: Boolean,
    error: Option[String] = None
  )

  implicit val importResultFormat: Format[ImportResult] = Json.format[ImportResult]

  /**
   * Request to ignore or unignore sets.
   */
  case class IgnoreRequest(
    setSpecs: List[String]
  )

  implicit val ignoreRequestFormat: Format[IgnoreRequest] = Json.format[IgnoreRequest]

  /**
   * Cached record counts for a source's sets.
   */
  case class SetCountCache(
    sourceId: String,
    lastVerified: DateTime,
    counts: Map[String, Int],
    errors: Map[String, String],
    summary: CountSummary
  )

  case class CountSummary(
    totalSets: Int,
    newWithRecords: Int,
    empty: Int
  )

  implicit val countSummaryFormat: Format[CountSummary] = Json.format[CountSummary]
  implicit val setCountCacheFormat: Format[SetCountCache] = Json.format[SetCountCache]
}
