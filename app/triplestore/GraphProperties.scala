//===========================================================================
//    Copyright 2015 Delving B.V.
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

package triplestore

import org.apache.jena.rdf.model.{Model, Property}

object GraphProperties {

  val NX_NAMESPACE = "http://schemas.delving.eu/narthex/terms/"

  val XML = "http://www.w3.org/XML/1998/namespace"
  val RDF = "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
  val DC = "http://purl.org/dc/elements/1.1/"
  val SKOS = "http://www.w3.org/2004/02/skos/core#"
  val FOAF = "http://xmlns.com/foaf/0.1/"
  val CC = "http://creativecommons.org/ns#"

  case class PropType(uriOpt: Option[String])

  val stringProp = PropType(None)
  val timeProp = PropType(None)
  val intProp = PropType(None)
  val booleanProp = PropType(None)
  val uriProp = PropType(None)

  var allProps = Map.empty[String, NXProp]

  case class NXProp(name: String, dataType: PropType = stringProp) {
    val uri = s"$NX_NAMESPACE$name"
    allProps = allProps + (name -> this)
    override def toString = uri
  }


  val belongsTo = NXProp("belongsTo")
  val hubId = NXProp("hubId")
  val localId = NXProp("localId")
  val synced = NXProp("synced")
  val deleted = NXProp("deleted")
  val saveTime = NXProp("saveTime")
  val contentHash = NXProp("contentHash")

  val datasetCharacter = NXProp("datasetCharacter")

  val orgId = NXProp("orgId")
  val datasetSpec = NXProp("datasetSpec")
  val datasetName = NXProp("datasetName")
  val datasetDescription = NXProp("datasetDescription")
  val datasetOwner = NXProp("datasetOwner")
  val datasetDataProviderURL = NXProp("datasetDataProviderURL")
  val datasetAggregator = NXProp("datasetAggregator")
  val datasetType = NXProp("datasetType")
  val datasetTags = NXProp("datasetTags")
  val edmType = NXProp("edmType")

  val datasetLanguage = NXProp("datasetLanguage")
  val datasetRights = NXProp("datasetRights")

  val datasetMapToPrefix = NXProp("datasetMapToPrefix")

  val datasetRecordCount = NXProp("datasetRecordCount", intProp)
  val datasetErrorTime = NXProp("datasetErrorTime")
  val datasetErrorMessage = NXProp("datasetErrorMessage")

  val datasetRecordsInSync = NXProp("datasetRecordsInSync", booleanProp)
  val datasetResourcePropertiesInSync = NXProp("datasetResourcePropertiesInSync", booleanProp)

  val skosField = NXProp("skosField")
  val skosFieldTag = NXProp("skosFieldTag")
  val proxyLiteralValue = NXProp("proxyLiteralValue")
  val proxyLiteralField = NXProp("proxyLiteralField")

  val stateRaw = NXProp("stateRaw", timeProp)
  val stateRawAnalyzed = NXProp("stateRawAnalyzed", timeProp)
  val stateSourced = NXProp("stateSourced", timeProp)
  val stateMappable = NXProp("stateMappable", timeProp)
  val stateProcessable = NXProp("stateProcessable", timeProp)
  val stateProcessed = NXProp("stateProcessed", timeProp)
  val stateAnalyzed = NXProp("stateAnalyzed", timeProp)
  val stateSaved = NXProp("stateSaved", timeProp)
  val stateIncrementalSaved = NXProp("stateIncrementalSaved", timeProp)
  val stateSynced = NXProp("stateSynced", timeProp)
  val stateDisabled = NXProp("stateDisabled", timeProp)

  // Indexing result properties (from Hub3 webhook notifications)
  val indexingRecordsIndexed = NXProp("indexingRecordsIndexed", intProp)
  val indexingRecordsExpected = NXProp("indexingRecordsExpected", intProp)
  val indexingOrphansDeleted = NXProp("indexingOrphansDeleted", intProp)
  val indexingErrorCount = NXProp("indexingErrorCount", intProp)
  val indexingLastStatus = NXProp("indexingLastStatus")
  val indexingLastMessage = NXProp("indexingLastMessage")
  val indexingLastTimestamp = NXProp("indexingLastTimestamp", timeProp)
  val indexingLastRevision = NXProp("indexingLastRevision", intProp)

  val delimitersSet = NXProp("delimitersSet", timeProp)
  val recordRoot = NXProp("recordRoot")
  val uniqueId = NXProp("uniqueId")

  val harvestType = NXProp("harvestType")
  val harvestURL = NXProp("harvestURL")
  val harvestDataset = NXProp("harvestDataset")
  val harvestRecord = NXProp("harvestRecord")
  val harvestPrefix = NXProp("harvestPrefix")
  val harvestSearch = NXProp("harvestSearch")
  val harvestUsername = NXProp("harvestUsername")
  val harvestPassword = NXProp("harvestPassword")  // Stored encrypted
  val harvestDownloadURL = NXProp("harvestDownloadURL")
  val harvestPreviousTime = NXProp("harvestPreviousTime", timeProp)
  val lastFullHarvestTime = NXProp("lastFullHarvestTime", timeProp)
  val lastIncrementalHarvestTime = NXProp("lastIncrementalHarvestTime", timeProp)
  val harvestDelay = NXProp("harvestDelay")
  val harvestDelayUnit = NXProp("harvestDelayUnit")
  val harvestIncremental = NXProp("harvestIncremental")
  val harvestIncrementalMode = NXProp("harvestIncrementalMode", booleanProp)
  val harvestIncrementalCount = NXProp("harvestIncrementalCount", intProp)
  val harvestFullCount = NXProp("harvestFullCount", intProp)

  // Harvest retry properties
  val harvestInRetry = NXProp("harvestInRetry", booleanProp)
  val harvestRetryCount = NXProp("harvestRetryCount", intProp)
  val harvestLastRetryTime = NXProp("harvestLastRetryTime", timeProp)
  val harvestRetryMessage = NXProp("harvestRetryMessage")

  val harvestContinueOnError = NXProp("harvestContinueOnError", booleanProp)
  val harvestErrorThreshold = NXProp("harvestErrorThreshold", intProp)
  val harvestErrorCount = NXProp("harvestErrorCount", intProp)
  val harvestErrorRecoveryAttempts = NXProp("harvestErrorRecoveryAttempts", intProp)

  // JSON harvest configuration
  val harvestJsonItemsPath = NXProp("harvestJsonItemsPath")          // e.g., "Items" or "result"
  val harvestJsonIdPath = NXProp("harvestJsonIdPath")                // e.g., "ID" or "record.id"
  val harvestJsonTotalPath = NXProp("harvestJsonTotalPath")          // e.g., "TotalItems"
  val harvestJsonPageParam = NXProp("harvestJsonPageParam")          // e.g., "page"
  val harvestJsonPageSizeParam = NXProp("harvestJsonPageSizeParam")  // e.g., "pagesize"
  val harvestJsonPageSize = NXProp("harvestJsonPageSize", intProp)   // default page size
  val harvestJsonDetailPath = NXProp("harvestJsonDetailPath")        // e.g., "/items/{id}"
  val harvestJsonSkipDetail = NXProp("harvestJsonSkipDetail", booleanProp) // optimization: use list records directly
  val harvestJsonXmlRoot = NXProp("harvestJsonXmlRoot")              // e.g., "records"
  val harvestJsonXmlRecord = NXProp("harvestJsonXmlRecord")          // e.g., "record"

  // API Key authentication (in addition to existing Basic Auth)
  val harvestApiKey = NXProp("harvestApiKey")                        // the API key value (stored encrypted)
  val harvestApiKeyParam = NXProp("harvestApiKeyParam")              // query param name, e.g., "api_key"

  // Operation tracking for restart recovery
  val datasetCurrentOperation = NXProp("datasetCurrentOperation")  // HARVESTING, PROCESSING, SAVING, etc.
  val datasetOperationStartTime = NXProp("datasetOperationStartTime", timeProp)
  val datasetOperationTrigger = NXProp("datasetOperationTrigger")  // "automatic" or "manual"
  val datasetOperationStatus = NXProp("datasetOperationStatus")    // "in_progress", "completed", "interrupted"

  // Mapping source properties for default mappings feature
  val datasetMappingSource = NXProp("datasetMappingSource")                    // "manual" | "default"
  val datasetDefaultMappingPrefix = NXProp("datasetDefaultMappingPrefix")      // e.g., "edm", "crm"
  val datasetDefaultMappingName = NXProp("datasetDefaultMappingName")          // e.g., "museum-mapping"
  val datasetDefaultMappingVersion = NXProp("datasetDefaultMappingVersion")    // hash or "latest"

  val idFilterType = NXProp("idFilterType")
  val idFilterExpression = NXProp("idFilterExpression")

  val processedValid = NXProp("processedValid", intProp)
  val processedInvalid = NXProp("processedInvalid", intProp)
  val processedIncrementalValid = NXProp("processedIncrementalValid", intProp)
  val processedIncrementalInvalid = NXProp("processedIncrementalInvalid", intProp)

  // Acquisition tracking properties (for clear record count semantics)
  val acquiredRecordCount = NXProp("acquiredRecordCount", intProp)    // Total records (harvested or uploaded)
  val deletedRecordCount = NXProp("deletedRecordCount", intProp)      // Deleted in OAI-PMH (0 for uploads)
  val sourceRecordCount = NXProp("sourceRecordCount", intProp)        // Active records in source.xml
  val acquisitionMethod = NXProp("acquisitionMethod")                 // "harvest" or "upload"

  val recordGraphsInSync = NXProp("recordGraphsInSync", booleanProp)
  val recordGraphsStored = NXProp("recordGraphsStored", intProp)
  val recordGraphsIndexed = NXProp("recordGraphsIndexed", intProp)
  val recordGraphsOutOfSync = NXProp("recordGraphsOutOfSync", intProp)
  val recordGraphsDeleted = NXProp("recordGraphsDeleted", intProp)
  val naveSyncErrorMessage = NXProp("naveSyncErrorMessage")

  val publishOAIPMH = NXProp("publishOAIPMH", booleanProp)
  val publishIndex = NXProp("publishIndex", booleanProp)
  val publishLOD = NXProp("publishLOD", booleanProp)
  val categoriesInclude = NXProp("categoriesInclude", booleanProp)

  val skosSpec = NXProp("skosSpec")
  val skosName = NXProp("skosName")
  val skosOwner = NXProp("skosOwner", uriProp)
  val skosUploadTime = NXProp("skosUploadTime", timeProp)

  val exactMatch = s"${SKOS}exactMatch"
  val belongsToCategory = NXProp("belongsToCategory", uriProp)
  val mappingConcept = NXProp("mappingConcept", uriProp)
  val mappingVocabulary = NXProp("mappingVocabulary", uriProp)
  val mappingDeleted = NXProp("mappingDeleted", booleanProp)
  val mappingTime = NXProp("mappingTime", timeProp)

  val skosFrequency = s"${NX_NAMESPACE}skosFrequency"

  val rdfType = s"${RDF}type"
  val foafDocument = s"${FOAF}Document"
  val foafPrimaryTopic = s"${FOAF}primaryTopic"
  val ccAttributionName = s"${CC}attributionName"
  val datasetEntity = s"${NX_NAMESPACE}Dataset"
  val recordEntity = s"${NX_NAMESPACE}Record"
  val terminologyMapping = s"${NX_NAMESPACE}TerminologyMapping"
  val instanceMapping = s"${NX_NAMESPACE}InstanceMapping"
  val proxyResource = s"${NX_NAMESPACE}ProxyResource"
  val skosCollection = s"${SKOS}Collection"

  def nxProp(m: Model, localName: String): Property = {
    m.setNsPrefix("nx", NX_NAMESPACE)
    m.getProperty(NX_NAMESPACE, localName)
  }


}
