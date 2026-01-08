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

package dataset

import org.scalatest.flatspec._
import org.scalatest.matchers._

/**
 * Tests for DsInfo serialization to ensure WebSocket updates include all necessary fields.
 *
 * These tests prevent the "whack-a-mole" bug pattern where fields are missing from
 * WebSocket updates, causing data to disappear when the frontend receives sparse updates.
 *
 * If a test fails, it means a field needs to be added to DsInfo.webSocketFields registry.
 */
class DsInfoSerializationSpec extends AnyFlatSpec with should.Matchers {

  // Get all field names from webSocketFields registry
  private lazy val webSocketFieldNames: Set[String] =
    DsInfo.webSocketFields.map(_.jsonName).toSet

  /**
   * Test 1: All DsInfoLight fields should be covered in webSocketFields
   *
   * DsInfoLight is used for initial page load. All its fields must also be in
   * WebSocket updates, otherwise data will be lost when updates arrive.
   */
  "webSocketFields" should "include all DsInfoLight case class fields" in {
    // These are the field names from DsInfoLight that should appear in WebSocket updates
    // Field name mapping: DsInfoLight field name -> expected JSON field name
    val dsInfoLightFieldMappings = Map(
      "spec" -> "spec", // Handled as core field
      "name" -> "datasetName",
      "processedValid" -> "processedValid",
      "processedInvalid" -> "processedInvalid",
      "recordCount" -> "datasetRecordCount",
      "acquiredRecordCount" -> "acquiredRecordCount",
      "deletedRecordCount" -> "deletedRecordCount",
      "sourceRecordCount" -> "sourceRecordCount",
      "acquisitionMethod" -> "acquisitionMethod",
      "stateDisabled" -> "stateDisabled",
      "stateRaw" -> "stateRaw",
      "stateRawAnalyzed" -> "stateRawAnalyzed",
      "stateSourced" -> "stateSourced",
      "stateMappable" -> "stateMappable",
      "stateProcessable" -> "stateProcessable",
      "stateAnalyzed" -> "stateAnalyzed",
      "stateProcessed" -> "stateProcessed",
      "stateSaved" -> "stateSaved",
      "stateIncrementalSaved" -> "stateIncrementalSaved",
      "currentOperation" -> "currentOperation",
      "operationStatus" -> "operationStatus",
      "errorMessage" -> "datasetErrorMessage", // Also duplicated as "errorMessage" in computed fields
      "harvestType" -> "harvestType",
      "harvestDownloadURL" -> "harvestDownloadURL",
      "harvestIncrementalMode" -> "harvestIncrementalMode",
      "processedIncrementalValid" -> "processedIncrementalValid",
      "processedIncrementalInvalid" -> "processedIncrementalInvalid",
      "delimitersSet" -> "delimitersSet",
      "recordRootValue" -> "recordRoot",
      "uniqueIdValue" -> "uniqueId",
      "harvestUsername" -> "harvestUsername",
      "harvestPasswordSet" -> "harvestPasswordSet",
      "harvestApiKeySet" -> "harvestApiKeySet"
    )

    // Check that all expected JSON field names are in webSocketFields
    val expectedJsonFields = dsInfoLightFieldMappings.values.toSet - "spec" // spec is a core field
    val missingInWebSocket = expectedJsonFields.filterNot { field =>
      webSocketFieldNames.contains(field) || field == "mappingSource" // mappingSource is computed
    }

    withClue(s"DsInfoLight fields missing from webSocketFields registry: ") {
      missingInWebSocket shouldBe empty
    }
  }

  /**
   * Test 2: All saveable harvest fields should be in WebSocket updates
   *
   * These are fields the frontend can modify in harvest settings.
   * They MUST survive WebSocket updates or user changes will be lost.
   */
  "webSocketFields" should "include all saveable harvest fields" in {
    val saveableHarvestFields = Set(
      "harvestType", "harvestURL", "harvestDataset", "harvestPrefix",
      "harvestSearch", "harvestRecord", "harvestDownloadURL",
      "harvestContinueOnError", "harvestErrorThreshold",
      "harvestUsername", "harvestPasswordSet", "harvestApiKeySet",
      "harvestJsonItemsPath", "harvestJsonIdPath", "harvestJsonTotalPath",
      "harvestJsonPageParam", "harvestJsonPageSizeParam", "harvestJsonPageSize",
      "harvestJsonDetailPath", "harvestJsonSkipDetail",
      "harvestJsonXmlRoot", "harvestJsonXmlRecord", "harvestApiKeyParam",
      "harvestDelay", "harvestDelayUnit", "harvestIncremental", "harvestIncrementalMode"
    )

    val missing = saveableHarvestFields -- webSocketFieldNames
    withClue(s"Saveable harvest fields missing from webSocketFields: ") {
      missing shouldBe empty
    }
  }

  /**
   * Test 3: All saveable metadata fields should be in WebSocket updates
   */
  "webSocketFields" should "include all saveable metadata fields" in {
    val saveableMetadataFields = Set(
      "datasetName", "datasetDescription", "datasetAggregator",
      "datasetOwner", "datasetLanguage", "datasetRights",
      "datasetType", "datasetTags", "edmType", "datasetDataProviderURL"
    )

    val missing = saveableMetadataFields -- webSocketFieldNames
    withClue(s"Saveable metadata fields missing from webSocketFields: ") {
      missing shouldBe empty
    }
  }

  /**
   * Test 4: All saveable publish and filter fields should be in WebSocket updates
   */
  "webSocketFields" should "include all saveable publish and filter fields" in {
    val saveableFields = Set(
      "publishOAIPMH", "publishIndex", "publishLOD",
      "idFilterType", "idFilterExpression"
    )

    val missing = saveableFields -- webSocketFieldNames
    withClue(s"Saveable publish/filter fields missing from webSocketFields: ") {
      missing shouldBe empty
    }
  }

  /**
   * Test 5: All state fields should be in WebSocket updates
   *
   * State fields are critical for UI to show correct dataset status.
   */
  "webSocketFields" should "include all state fields" in {
    val stateFields = Set(
      "stateDisabled", "stateRaw", "stateRawAnalyzed",
      "stateSourced", "stateMappable", "stateProcessable",
      "stateAnalyzed", "stateProcessed", "stateSaved", "stateIncrementalSaved"
    )

    val missing = stateFields -- webSocketFieldNames
    withClue(s"State fields missing from webSocketFields: ") {
      missing shouldBe empty
    }
  }

  /**
   * Test 6: All indexing result fields should be in WebSocket updates
   */
  "webSocketFields" should "include all indexing result fields" in {
    val indexingFields = Set(
      "indexingRecordsIndexed", "indexingRecordsExpected",
      "indexingOrphansDeleted", "indexingErrorCount",
      "indexingLastStatus", "indexingLastMessage",
      "indexingLastTimestamp", "indexingLastRevision"
    )

    val missing = indexingFields -- webSocketFieldNames
    withClue(s"Indexing fields missing from webSocketFields: ") {
      missing shouldBe empty
    }
  }

  /**
   * Test 7: No duplicate field names in registry
   *
   * Duplicate field names would cause one value to overwrite another.
   */
  "webSocketFields" should "not have duplicate field names" in {
    val fieldNames = DsInfo.webSocketFields.map(_.jsonName)
    val duplicates = fieldNames.diff(fieldNames.distinct)

    withClue(s"Duplicate field names found in webSocketFields: ") {
      duplicates shouldBe empty
    }
  }

  /**
   * Test 8: Registry should have reasonable size
   *
   * Sanity check to ensure we haven't accidentally cleared the registry.
   */
  "webSocketFields" should "have at least 50 field specifications" in {
    DsInfo.webSocketFields.size should be >= 50
  }

  /**
   * Test 9: All FieldSpec types should properly return values
   */
  "FieldSpec types" should "be defined correctly" in {
    // Verify StringField, IntField, BoolField, NonEmptyField are all used
    val fieldTypes = DsInfo.webSocketFields.map(_.getClass.getSimpleName).toSet

    fieldTypes should contain("StringField")
    fieldTypes should contain("IntField")
    fieldTypes should contain("BoolField")
    fieldTypes should contain("NonEmptyField")
  }
}
