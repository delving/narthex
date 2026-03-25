# DsInfo PostgreSQL Snapshot Design

**Date:** 2026-03-25
**Status:** Accepted
**Scope:** Eliminate Fuseki reads from DsInfo by loading a per-instance property snapshot from PostgreSQL

## Problem

`DsInfo.getLiteralProp(prop)` reads every dataset property from Fuseki via `ts.dataGet(graphName)` — a full HTTP GET of the RDF graph. This fires on every property access: DatasetActor alone does 30+ reads per workflow run. The result is noisy `[FUSEKI READ] GET GRAPH` traffic and `CACHE MISS` warnings, even though all the data already exists in PostgreSQL.

The existing mitigation — a Play `cacheApi`-based existence check plus an in-memory `cachedModel` — is fragile. The cache is often cold (new `DsInfo` instances are created frequently), and when it misses, every property read triggers a separate Fuseki round-trip.

## Decision

Replace the Fuseki graph fetch with a **per-instance property snapshot** loaded from PostgreSQL at `DsInfo` construction time. The snapshot is a `Map[String, String]` keyed by NXProp name, populated from 6 PostgreSQL tables in 6 fast queries. All `getLiteralProp` / `getTimeProp` / `getBooleanProp` calls resolve against this map. SKOS properties (unmapped) fall through to Fuseki.

## Design

### Core mechanism

When a `DsInfo` is constructed and PostgreSQL is configured (via `GlobalDsInfoService`), it loads a snapshot:

```
DsInfo(spec, ...)
  → loadPostgresSnapshot()
    → repo.getDataset(spec)         → extract dataset fields
    → repo.getState(spec)           → extract state + retry fields
    → repo.getHarvestConfig(spec)   → extract harvest config + JSON fields
    → repo.getHarvestSchedule(spec) → extract schedule fields
    → repo.getMappingConfig(spec)   → extract mapping config fields
    → repo.getIndexing(spec)        → extract indexing fields
  → pgSnapshot = Some(Map("datasetName" -> "...", "sourceRecordCount" -> "56180", ...))
```

Then `getLiteralProp` resolves from the map:

```
getLiteralProp(prop)
  → pgSnapshot.get(prop.name) match {
      case Some(value) => Some(value)           // from PostgreSQL
      case None if isSkosProperty(prop) =>
        fusekiGetLiteralProp(prop)              // SKOS → Fuseki fallback
      case None => None                         // not in PG, not SKOS → absent
    }
  // When pgSnapshot is None (PG not configured) → Fuseki as today
```

### NXProp → PostgreSQL mapping

~85 properties map to 6 tables. The mapping is a static `Map[String, (Table, FieldExtractor)]` built once.

**datasets table:**
`orgId`, `datasetSpec`, `datasetName`, `datasetDescription`, `datasetOwner`, `datasetType`, `datasetTags`, `datasetAggregator`, `datasetDataProviderURL`, `edmType`, `datasetLanguage`, `datasetRights`, `datasetCharacter`

**dataset_state table:**
`datasetRecordCount`, `datasetErrorMessage`, `datasetErrorTime`, `datasetCurrentOperation`, `datasetOperationStartTime`, `datasetOperationTrigger`, `datasetOperationStatus`, `acquiredRecordCount`, `deletedRecordCount`, `sourceRecordCount`, `processedValid`, `processedInvalid`, `processedIncrementalValid`, `processedIncrementalInvalid`, `acquisitionMethod`, `delimitersSet`, `harvestRetryMessage`, `harvestInRetry`, `harvestRetryCount`, `harvestLastRetryTime`

State timestamp properties (`stateRaw`..`stateDisabled`): the snapshot returns the `stateChangedAt` timestamp for the prop that matches the current `state` column value, and `None` for all others. This preserves the old behavior where only the "current" state had a timestamp.

**dataset_harvest_config table:**
`harvestType`, `harvestURL`, `harvestDataset`, `harvestPrefix`, `harvestRecord`, `harvestSearch`, `harvestUsername`, `harvestPassword`, `harvestApiKey`, `harvestApiKeyParam`, `harvestDownloadURL`, `harvestContinueOnError`, `harvestErrorThreshold`, `recordRoot`, `uniqueId`, `idFilterType`, `idFilterExpression`

JSON harvest fields — read from the existing `harvest_json JSONB` column in `dataset_harvest_config` (already in V1 schema):
`harvestJsonItemsPath`, `harvestJsonIdPath`, `harvestJsonTotalPath`, `harvestJsonPageParam`, `harvestJsonPageSizeParam`, `harvestJsonPageSize`, `harvestJsonDetailPath`, `harvestJsonSkipDetail`, `harvestJsonXmlRoot`, `harvestJsonXmlRecord`

**dataset_harvest_schedule table:**
`harvestDelay`, `harvestDelayUnit`, `harvestIncremental`, `harvestIncrementalMode`, `harvestPreviousTime`, `lastFullHarvestTime`, `lastIncrementalHarvestTime`

Note: `harvestIncrementalMode` maps to the existing `incremental` boolean column on `dataset_harvest_schedule`. This is persistent dataset configuration, not per-run state.

**dataset_mapping_config table:**
`datasetMapToPrefix`, `datasetMappingSource`, `datasetDefaultMappingPrefix`, `datasetDefaultMappingName`, `datasetDefaultMappingVersion`, `publishOAIPMH`, `publishIndex`, `publishLOD`, `categoriesInclude`, `processedExternally`

**dataset_indexing table:**
`indexingRecordsIndexed`, `indexingRecordsExpected`, `indexingOrphansDeleted`, `indexingErrorCount`, `indexingLastStatus`, `indexingLastMessage`, `indexingLastTimestamp`, `indexingLastRevision`

### Properties NOT mapped (SKOS — Fuseki fallback)

These remain on Fuseki until Phase 5 (SKOS migration):
`skosField`, `skosFieldTag`, `skosSpec`, `skosName`, `skosOwner`, `skosUploadTime`, `proxyLiteralValue`, `proxyLiteralField`, `belongsToCategory`, `mappingConcept`, `mappingVocabulary`, `mappingDeleted`, `mappingTime`

### Write-only properties (not needed in read snapshot)

These are written via `setSingularLiteralProps` but never read back via `getLiteralProp`. They continue to write to Fuseki under the dual-write regime. Not included in the snapshot:

- `datasetRecordsInSync`, `datasetResourcePropertiesInSync` — written by DatasetActor after saves/skosification, never read back
- `harvestErrorCount`, `harvestErrorRecoveryAttempts` — written by Harvester as informational counters, never read back in the actor

### Dead properties (no reads or writes in application code)

These exist only in `GraphProperties.scala` declarations with no references elsewhere:
`recordGraphsInSync`, `recordGraphsStored`, `recordGraphsIndexed`, `recordGraphsOutOfSync`, `recordGraphsDeleted`, `naveSyncErrorMessage`, `harvestIncrementalCount`, `harvestFullCount`

### Schema changes needed

**V11 migration:**
- Add retry columns to `dataset_state`: `retry_message TEXT`, `in_retry BOOLEAN DEFAULT false`, `retry_count INT DEFAULT 0`, `last_retry_at TIMESTAMPTZ`
- Add `operation_status TEXT` to `dataset_state`
- Add `processed_externally TEXT` to `dataset_mapping_config`

Note: `harvest_json JSONB` already exists in `dataset_harvest_config` (V1). No schema change needed for JSON harvest fields — just wire the read/write path.

### Write path — snapshot invalidation

After any `setSingularLiteralProps` call that writes to PostgreSQL (via `pgWrite`), the snapshot must be refreshed. Two options:

**Option A (simple):** Reload the full snapshot after any write. 6 fast queries — acceptable since writes are infrequent relative to reads.

**Option B (surgical):** Update the specific keys in the map that were written. Faster but more code.

Recommendation: **Option A** for simplicity. Writes happen at workflow state transitions (~10 per workflow run). 6 queries at each transition is negligible.

### Concurrency assumption

The actor's single `DsInfo` instance is the authoritative writer and reader for its dataset during a workflow. Operations like `incrementRetryCount()` (read current count from snapshot, increment, write back) are safe because no other writer is modifying the same dataset concurrently. Controller-created `DsInfo` instances get a fresh snapshot at construction, so they always see the latest PG state.

### Code to remove

Once the snapshot is the read path, the following become dead code:

- `cachedDataExists: Option[Boolean]`
- `cachedModel: Option[Model]`
- `loadCachedExistence()` and Play `cacheApi` dependency
- `cacheDataExists(exists: Boolean)`
- `invalidateCachedModel()`
- The `futureModel` caching branches (`Some(true)` → cached model, `None` → CACHE MISS)

`futureModel` / `getModel` stay for the SKOS fallback path only.

### What this eliminates

| Before | After |
|--------|-------|
| Every `getLiteralProp` → Fuseki HTTP GET GRAPH | In-memory map lookup |
| `CACHE MISS` warnings on every new DsInfo | No cache needed — snapshot is the data |
| 4+ Fuseki round-trips per DatasetActor workflow step | 0 Fuseki reads (except SKOS) |
| Play cacheApi dependency for dataset existence | Removed |
| `cachedModel` / `cachedDataExists` state management | Removed |

### What stays unchanged

- Dual-write path: `setSingularLiteralProps` still writes to both Fuseki (when enabled) and PostgreSQL
- SKOS property reads still hit Fuseki
- `DsInfo` object lifecycle (created per-request or per-actor)
- All `getLiteralProp` call sites — zero changes needed in DatasetActor, PreviewController, Harvester, etc.

### Outstanding write path gaps

`AppController.setSingularLiteralProps(processedExternally -> source)` (line 2142) writes to Fuseki but has no `pgWrite` call. This needs to be wired up so the snapshot contains the value after it's set.

### Testing approach

1. Unit test the NXProp → snapshot mapping: given known PostgreSQL records, verify the snapshot contains expected property values
2. Verify `getLiteralProp` returns the same values from PG snapshot as from Fuseki (comparison test using FusekiMigration's existing data)
3. Verify SKOS properties still fall through to Fuseki
4. Verify snapshot refresh after writes
5. Integration: run a workflow with `fuseki.reads-enabled=false` and confirm it completes without errors
