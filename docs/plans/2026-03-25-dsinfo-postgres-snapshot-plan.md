# DsInfo PostgreSQL Snapshot Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace Fuseki reads in DsInfo.getLiteralProp with a per-instance property snapshot loaded from PostgreSQL, eliminating all `[FUSEKI READ] GET GRAPH` calls during normal workflow execution.

**Architecture:** Each `DsInfo` instance loads a `Map[String, String]` from 6 PostgreSQL tables at construction. `getLiteralProp` resolves from this map. SKOS properties fall through to Fuseki. The existing Fuseki caching (`cachedModel`, `cachedDataExists`, Play `cacheApi`) is removed.

**Tech Stack:** Scala 2.13, Play 2.8, PostgreSQL (via existing `DatasetRepository`/`PostgresDatasetRepository`), Flyway migrations.

**Spec:** `docs/plans/2026-03-25-dsinfo-postgres-snapshot-design.md`

---

### Task 1: Schema migration V11 — add missing columns

**Files:**
- Create: `conf/db/migration/V11__snapshot_columns.sql`

- [ ] **Step 1: Write the migration**

```sql
-- V11: Add columns needed for DsInfo PostgreSQL snapshot
-- Retry state (was in Fuseki, now in dataset_state)
ALTER TABLE dataset_state ADD COLUMN retry_message TEXT;
ALTER TABLE dataset_state ADD COLUMN in_retry BOOLEAN DEFAULT false;
ALTER TABLE dataset_state ADD COLUMN retry_count INT DEFAULT 0;
ALTER TABLE dataset_state ADD COLUMN last_retry_at TIMESTAMPTZ;

-- Operation status (was in Fuseki, now in dataset_state)
ALTER TABLE dataset_state ADD COLUMN operation_status TEXT;

-- Processed externally flag (was in Fuseki, now in mapping config)
ALTER TABLE dataset_mapping_config ADD COLUMN processed_externally TEXT;
```

- [ ] **Step 2: Verify migration applies**

Run: `make compile` then start app with `narthex.postgres.run-migration=true` or test via embedded PG.
Expected: V11 applies cleanly on top of V10.

- [ ] **Step 3: Commit**

```
git add conf/db/migration/V11__snapshot_columns.sql
git commit -m "chore: add V11 migration for snapshot columns — retry state, operation status, processed externally"
```

---

### Task 2: Update DatasetStateRecord and PostgresDatasetRepository for new columns

**Files:**
- Modify: `app/services/DatasetRepository.scala` — `DatasetStateRecord` case class
- Modify: `app/services/PostgresDatasetRepository.scala` — `readState`, `upsertState`

- [ ] **Step 1: Write failing test**

In `test/services/PostgresDatasetRepositorySpec.scala`, add a test that upserts a state with retry fields and reads them back:

```scala
"store and retrieve retry state fields" in {
  repo.createDataset(DatasetRecord(spec = "retry-test", orgId = "org-1"))
  repo.upsertState(DatasetStateRecord(
    spec = "retry-test",
    state = "SAVED",
    retryMessage = Some("connection timeout"),
    inRetry = true,
    retryCount = 3,
    lastRetryAt = Some(Instant.now()),
    operationStatus = Some("in_progress")
  ))
  val state = repo.getState("retry-test")
  state shouldBe defined
  state.get.retryMessage shouldBe Some("connection timeout")
  state.get.inRetry shouldBe true
  state.get.retryCount shouldBe 3
  state.get.lastRetryAt shouldBe defined
  state.get.operationStatus shouldBe Some("in_progress")
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `sbt "testOnly services.PostgresDatasetRepositorySpec"`
Expected: FAIL — `DatasetStateRecord` has no `retryMessage` field yet.

- [ ] **Step 3: Add fields to DatasetStateRecord**

In `app/services/DatasetRepository.scala`, add to `DatasetStateRecord`:

```scala
case class DatasetStateRecord(
    // ... existing fields ...
    acquisitionMethod: Option[String] = None,
    delimiterSet: Option[Instant] = None,
    // New fields for snapshot
    retryMessage: Option[String] = None,
    inRetry: Boolean = false,
    retryCount: Int = 0,
    lastRetryAt: Option[Instant] = None,
    operationStatus: Option[String] = None
)
```

- [ ] **Step 4: Update readState in PostgresDatasetRepository**

Add to `readState` method after `delimiterSet`:

```scala
retryMessage = getOptString(rs, "retry_message"),
inRetry = rs.getBoolean("in_retry"),
retryCount = rs.getInt("retry_count"),
lastRetryAt = getOptInstant(rs, "last_retry_at"),
operationStatus = getOptString(rs, "operation_status")
```

- [ ] **Step 5: Update upsertState SQL in PostgresDatasetRepository**

Add the 5 new columns to both the INSERT and ON CONFLICT UPDATE clauses in `upsertState`.

- [ ] **Step 6: Run test to verify it passes**

Run: `sbt "testOnly services.PostgresDatasetRepositorySpec"`
Expected: PASS

- [ ] **Step 7: Commit**

```
git commit -m "feat: add retry state and operation status to DatasetStateRecord"
```

---

### Task 3: Add processed_externally to MappingConfigRecord and repository

**Files:**
- Modify: `app/services/DatasetRepository.scala` — `MappingConfigRecord`
- Modify: `app/services/PostgresDatasetRepository.scala` — `readMappingConfig`, `upsertMappingConfig`

- [ ] **Step 1: Write failing test**

In `PostgresDatasetRepositorySpec`, test that `processedExternally` round-trips.

- [ ] **Step 2: Add `processedExternally: Option[String] = None` to `MappingConfigRecord`**

- [ ] **Step 3: Update `readMappingConfig` and `upsertMappingConfig` SQL**

- [ ] **Step 4: Run test, verify pass**

- [ ] **Step 5: Commit**

```
git commit -m "feat: add processedExternally to MappingConfigRecord"
```

---

### Task 4: Build the NXProp → PostgreSQL snapshot mapping

This is the core new code — a method that loads all 6 tables for a spec and builds a `Map[String, String]`.

**Files:**
- Create: `app/services/PropertySnapshot.scala`
- Create: `test/services/PropertySnapshotSpec.scala`

- [ ] **Step 1: Write failing test**

```scala
class PropertySnapshotSpec extends AnyFlatSpec with should.Matchers {
  // Uses embedded PostgreSQL like other specs

  "PropertySnapshot" should "load dataset fields into snapshot map" in {
    repo.createDataset(DatasetRecord(spec = "snap-test", orgId = "org-1",
      name = Some("Test Dataset"), aggregator = Some("Delving")))
    repo.upsertState(DatasetStateRecord(spec = "snap-test", state = "SAVED",
      recordCount = 100, sourceCount = 95, processedValid = 90))
    repo.upsertHarvestConfig(HarvestConfigRecord(spec = "snap-test",
      harvestType = Some("oaipmh"), harvestUrl = Some("http://example.com/oai")))

    val snapshot = PropertySnapshot.load(repo, "snap-test")

    snapshot.get("datasetName") shouldBe Some("Test Dataset")
    snapshot.get("datasetAggregator") shouldBe Some("Delving")
    snapshot.get("datasetRecordCount") shouldBe Some("100")
    snapshot.get("sourceRecordCount") shouldBe Some("95")
    snapshot.get("processedValid") shouldBe Some("90")
    snapshot.get("harvestType") shouldBe Some("oaipmh")
    snapshot.get("harvestURL") shouldBe Some("http://example.com/oai")
  }

  it should "return state timestamp only for the current state" in {
    repo.createDataset(DatasetRecord(spec = "state-test", orgId = "org-1"))
    val changedAt = Instant.parse("2026-03-25T10:00:00Z")
    repo.upsertState(DatasetStateRecord(spec = "state-test",
      state = "stateSaved", stateChangedAt = changedAt))

    val snapshot = PropertySnapshot.load(repo, "state-test")

    snapshot.get("stateSaved") shouldBe defined  // has timestamp
    snapshot.get("stateRaw") shouldBe None        // not current state
    snapshot.get("stateProcessed") shouldBe None
  }

  it should "load harvest JSON fields from JSONB column" in {
    // Insert harvest config with harvest_json populated
    // Verify harvestJsonItemsPath etc. appear in snapshot
  }

  it should "load retry state fields" in {
    repo.createDataset(DatasetRecord(spec = "retry-test", orgId = "org-1"))
    repo.upsertState(DatasetStateRecord(spec = "retry-test",
      retryMessage = Some("timeout"), inRetry = true, retryCount = 2))

    val snapshot = PropertySnapshot.load(repo, "retry-test")

    snapshot.get("harvestRetryMessage") shouldBe Some("timeout")
    snapshot.get("harvestInRetry") shouldBe Some("true")
    snapshot.get("harvestRetryCount") shouldBe Some("2")
  }

  it should "load schedule fields including harvestIncremental and harvestIncrementalMode" in {
    repo.createDataset(DatasetRecord(spec = "sched-test", orgId = "org-1"))
    repo.upsertHarvestSchedule(HarvestScheduleRecord(
      spec = "sched-test", delay = Some("60"), delayUnit = Some("minutes"),
      incremental = true))

    val snapshot = PropertySnapshot.load(repo, "sched-test")

    snapshot.get("harvestDelay") shouldBe Some("60")
    snapshot.get("harvestDelayUnit") shouldBe Some("minutes")
    // Both NXProps map to the same `incremental` boolean column
    snapshot.get("harvestIncremental") shouldBe Some("true")
    snapshot.get("harvestIncrementalMode") shouldBe Some("true")
  }

  it should "return empty map for non-existent spec" in {
    PropertySnapshot.load(repo, "no-such-dataset") shouldBe empty
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Expected: FAIL — `PropertySnapshot` doesn't exist.

- [ ] **Step 3: Implement PropertySnapshot.load**

Create `app/services/PropertySnapshot.scala`:

```scala
package services

import java.time.Instant
import java.time.format.DateTimeFormatter

/** Builds a Map[String, String] keyed by NXProp name from PostgreSQL records.
  *
  * This snapshot replaces the Fuseki RDF graph fetch for DsInfo.getLiteralProp.
  * One snapshot is loaded per DsInfo instance at construction time.
  */
object PropertySnapshot {

  private val isoFormatter = DateTimeFormatter.ISO_INSTANT

  def load(repo: DatasetRepository, spec: String): Map[String, String] = {
    val props = Map.newBuilder[String, String]

    // datasets table
    repo.getDataset(spec).foreach { ds =>
      ds.name.foreach(v => props += "datasetName" -> v)
      ds.description.foreach(v => props += "datasetDescription" -> v)
      ds.owner.foreach(v => props += "datasetOwner" -> v)
      ds.datasetType.foreach(v => props += "datasetType" -> v)
      ds.character.foreach(v => props += "datasetCharacter" -> v)
      ds.language.foreach(v => props += "datasetLanguage" -> v)
      ds.rights.foreach(v => props += "datasetRights" -> v)
      ds.aggregator.foreach(v => props += "datasetAggregator" -> v)
      ds.dataProviderUrl.foreach(v => props += "datasetDataProviderURL" -> v)
      ds.edmType.foreach(v => props += "edmType" -> v)
      props += "orgId" -> ds.orgId
      props += "datasetSpec" -> ds.spec
      if (ds.tags.nonEmpty) props += "datasetTags" -> ds.tags.mkString(",")
    }

    // dataset_state table
    repo.getState(spec).foreach { st =>
      props += "datasetRecordCount" -> st.recordCount.toString
      props += "acquiredRecordCount" -> st.acquiredCount.toString
      props += "deletedRecordCount" -> st.deletedCount.toString
      props += "sourceRecordCount" -> st.sourceCount.toString
      props += "processedValid" -> st.processedValid.toString
      props += "processedInvalid" -> st.processedInvalid.toString
      props += "processedIncrementalValid" -> st.processedIncrementalValid.toString
      props += "processedIncrementalInvalid" -> st.processedIncrementalInvalid.toString
      st.errorMessage.foreach(v => props += "datasetErrorMessage" -> v)
      st.errorTime.foreach(v => props += "datasetErrorTime" -> fmtInstant(v))
      st.currentOperation.foreach(v => props += "datasetCurrentOperation" -> v)
      st.operationStart.foreach(v => props += "datasetOperationStartTime" -> fmtInstant(v))
      st.operationTrigger.foreach(v => props += "datasetOperationTrigger" -> v)
      st.operationStatus.foreach(v => props += "datasetOperationStatus" -> v)
      st.acquisitionMethod.foreach(v => props += "acquisitionMethod" -> v)
      st.delimiterSet.foreach(v => props += "delimitersSet" -> fmtInstant(v))
      // Retry state
      st.retryMessage.foreach(v => props += "harvestRetryMessage" -> v)
      props += "harvestInRetry" -> st.inRetry.toString
      props += "harvestRetryCount" -> st.retryCount.toString
      st.lastRetryAt.foreach(v => props += "harvestLastRetryTime" -> fmtInstant(v))
      // State timestamp — only the current state gets a timestamp
      addStateTimestamp(props, st.state, st.stateChangedAt)
    }

    // dataset_harvest_config table
    repo.getHarvestConfig(spec).foreach { hc =>
      hc.harvestType.foreach(v => props += "harvestType" -> v)
      hc.harvestUrl.foreach(v => props += "harvestURL" -> v)
      hc.harvestDataset.foreach(v => props += "harvestDataset" -> v)
      hc.harvestPrefix.foreach(v => props += "harvestPrefix" -> v)
      hc.harvestRecord.foreach(v => props += "harvestRecord" -> v)
      hc.harvestSearch.foreach(v => props += "harvestSearch" -> v)
      hc.harvestDownloadUrl.foreach(v => props += "harvestDownloadURL" -> v)
      hc.harvestUsername.foreach(v => props += "harvestUsername" -> v)
      hc.harvestPassword.foreach(v => props += "harvestPassword" -> v)
      hc.harvestApiKey.foreach(v => props += "harvestApiKey" -> v)
      hc.harvestApiKeyParam.foreach(v => props += "harvestApiKeyParam" -> v)
      hc.recordRoot.foreach(v => props += "recordRoot" -> v)
      hc.uniqueId.foreach(v => props += "uniqueId" -> v)
      props += "harvestContinueOnError" -> hc.continueOnError.toString
      hc.errorThreshold.foreach(v => props += "harvestErrorThreshold" -> v.toString)
      hc.idFilterType.foreach(v => props += "idFilterType" -> v)
      hc.idFilterExpression.foreach(v => props += "idFilterExpression" -> v)
      // JSON harvest fields from harvest_json JSONB column
      // TODO: parse hc.harvestJson and extract fields
    }

    // dataset_harvest_schedule table
    repo.getHarvestSchedule(spec).foreach { hs =>
      hs.delay.foreach(v => props += "harvestDelay" -> v)
      hs.delayUnit.foreach(v => props += "harvestDelayUnit" -> v)
      // Both NXProps map to the same `incremental` boolean column:
      // - harvestIncremental (string prop, used in DsInfo.getHarvestCron)
      // - harvestIncrementalMode (boolean prop, used in DatasetActor.processIncremental)
      props += "harvestIncremental" -> hs.incremental.toString
      props += "harvestIncrementalMode" -> hs.incremental.toString
      hs.previousTime.foreach(v => props += "harvestPreviousTime" -> fmtInstant(v))
      hs.lastFullHarvest.foreach(v => props += "lastFullHarvestTime" -> fmtInstant(v))
      hs.lastIncrementalHarvest.foreach(v => props += "lastIncrementalHarvestTime" -> fmtInstant(v))
    }

    // dataset_mapping_config table
    repo.getMappingConfig(spec).foreach { mc =>
      mc.mapToPrefix.foreach(v => props += "datasetMapToPrefix" -> v)
      mc.mappingSource.foreach(v => props += "datasetMappingSource" -> v)
      mc.defaultMappingPrefix.foreach(v => props += "datasetDefaultMappingPrefix" -> v)
      mc.defaultMappingName.foreach(v => props += "datasetDefaultMappingName" -> v)
      mc.defaultMappingVersion.foreach(v => props += "datasetDefaultMappingVersion" -> v)
      props += "publishOAIPMH" -> mc.publishOaipmh.toString
      props += "publishIndex" -> mc.publishIndex.toString
      props += "publishLOD" -> mc.publishLod.toString
      props += "categoriesInclude" -> mc.categoriesInclude.toString
      mc.processedExternally.foreach(v => props += "processedExternally" -> v)
    }

    // dataset_indexing table
    repo.getIndexing(spec).foreach { idx =>
      idx.recordsIndexed.foreach(v => props += "indexingRecordsIndexed" -> v.toString)
      idx.recordsExpected.foreach(v => props += "indexingRecordsExpected" -> v.toString)
      idx.orphansDeleted.foreach(v => props += "indexingOrphansDeleted" -> v.toString)
      idx.errorCount.foreach(v => props += "indexingErrorCount" -> v.toString)
      idx.lastStatus.foreach(v => props += "indexingLastStatus" -> v)
      idx.lastMessage.foreach(v => props += "indexingLastMessage" -> v)
      idx.lastTimestamp.foreach(v => props += "indexingLastTimestamp" -> fmtInstant(v))
      idx.lastRevision.foreach(v => props += "indexingLastRevision" -> v.toString)
    }

    props.result()
  }

  /** Map the current state name to the corresponding NXProp timestamp key. */
  private def addStateTimestamp(
      props: scala.collection.mutable.Builder[(String, String), Map[String, String]],
      state: String,
      changedAt: Instant
  ): Unit = {
    // state column stores values like "stateSaved", "stateRaw", etc.
    // These map directly to NXProp names
    val validStates = Set(
      "stateRaw", "stateRawAnalyzed", "stateSourced", "stateSourceAnalyzed",
      "stateMappable", "stateProcessable", "stateProcessed", "stateAnalyzed",
      "stateSaved", "stateIncrementalSaved", "stateSynced", "stateDisabled"
    )
    if (validStates.contains(state)) {
      props += state -> fmtInstant(changedAt)
    }
  }

  private def fmtInstant(i: Instant): String = isoFormatter.format(i)

  /** Set of NXProp names that are SKOS/vocab properties — these fall through to Fuseki. */
  val skosProperties: Set[String] = Set(
    "skosField", "skosFieldTag", "skosSpec", "skosName", "skosOwner",
    "skosUploadTime", "proxyLiteralValue", "proxyLiteralField",
    "belongsToCategory", "mappingConcept", "mappingVocabulary",
    "mappingDeleted", "mappingTime"
  )
}
```

- [ ] **Step 4: Run tests, verify pass**

Run: `sbt "testOnly services.PropertySnapshotSpec"`
Expected: PASS

- [ ] **Step 5: Commit**

```
git commit -m "feat: add PropertySnapshot — NXProp to PostgreSQL mapping for DsInfo reads"
```

---

### Task 5: Wire snapshot into DsInfo — replace Fuseki reads

This is the key integration. Modify `DsInfo` to load the snapshot at construction and route `getLiteralProp` through it.

**Files:**
- Modify: `app/dataset/DsInfo.scala` — constructor, `getLiteralProp`, remove old caching

- [ ] **Step 1: Add snapshot loading to DsInfo constructor**

Replace the caching block (lines 802–840) with:

```scala
// PostgreSQL property snapshot — loaded once at construction, replaces Fuseki graph fetch
private var pgSnapshot: Option[Map[String, String]] = {
  val svc = writeService.orElse(services.GlobalDsInfoService.get())
  svc.map(s => services.PropertySnapshot.load(s.repo, spec))
}
```

- [ ] **Step 2: Replace getLiteralProp**

Replace the current `getLiteralProp` (line 878) with:

```scala
def getLiteralProp(prop: NXProp): Option[String] = {
  pgSnapshot match {
    case Some(snapshot) =>
      snapshot.get(prop.name) match {
        case some @ Some(_) => some
        case None if services.PropertySnapshot.skosProperties.contains(prop.name) =>
          fusekiGetLiteralProp(prop)
        case None => None
      }
    case None => fusekiGetLiteralProp(prop)
  }
}

/** Original Fuseki-backed property read — used as fallback for SKOS properties
  * and when PostgreSQL is not configured.
  */
private def fusekiGetLiteralProp(prop: NXProp): Option[String] = {
  val m = getModel
  val res = m.getResource(uri)
  val objects = m.listObjectsOfProperty(res, m.getProperty(prop.uri))
  if (objects.hasNext) Some(objects.next().asLiteral().getString) else None
}
```

- [ ] **Step 3: Remove old caching code**

Remove:
- `cachedDataExists`, `cachedModel` vars
- `loadCachedExistence()` method
- `cacheDataExists()` method
- `invalidateCachedModel()` method
- `loadCachedExistence()` call in constructor
- The three branches in `futureModel` that used `cachedDataExists` — simplify to just call `ts.dataGet(graphName)` directly (for SKOS fallback only)

- [ ] **Step 4: Add snapshot reload after writes**

In `setSingularLiteralProps`, after the Fuseki write + pgWrite, reload the snapshot:

```scala
// Reload snapshot after write so subsequent reads see fresh data
pgSnapshot = pgSnapshot.flatMap { _ =>
  val svc = writeService.orElse(services.GlobalDsInfoService.get())
  svc.map(s => services.PropertySnapshot.load(s.repo, spec))
}
```

- [ ] **Step 5: Remove cacheDataExists/invalidateCachedModel call sites (atomic with Step 3)**

These callers must be removed in the same step as the methods they call:
- `app/dataset/DsInfo.scala:637` — `dsInfo.cacheDataExists(dataExists)` in `freshDsInfo` → remove call
- `app/dataset/DsInfo.scala:714` — `dsInfo.cacheDataExists(exists)` in `listDsInfoWithStateFilter` → remove call
- `app/dataset/DsInfo.scala:741` — `dsInfo.cacheDataExists(answer)` in `withDsInfo` → remove call
- `app/dataset/DatasetActor.scala:665` — `dsInfo.invalidateCachedModel()` → remove call

- [ ] **Step 6: Compile**

Run: `make compile`
Expected: Success. All callers of `getLiteralProp` unchanged — same signature.

- [ ] **Step 7: Run all tests**

Run: `sbt test`
Expected: All 117+ tests pass.

- [ ] **Step 8: Commit**

```
git commit -m "feat: route DsInfo.getLiteralProp through PostgreSQL snapshot — eliminates Fuseki reads"
```

---

### Task 6: Wire retry state writes to PostgreSQL

The retry state properties (`harvestInRetry`, `harvestRetryCount`, etc.) are written by `DsInfo.setInRetry`, `incrementRetryCount`, `clearRetryState`. These need `pgWrite` calls to populate the new columns.

**Files:**
- Modify: `app/dataset/DsInfo.scala` — retry methods
- Modify: `app/services/DsInfoService.scala` — add retry write methods

- [ ] **Step 1: Fix existing clearRetryState bug in DsInfoService**

The existing `DsInfoService.clearRetryState` (line 238) incorrectly clears `currentOperation` and `operationStart` instead of retry fields. Fix it:

```scala
def clearRetryState(spec: String): Unit = {
  repo.getState(spec).foreach { existing =>
    repo.upsertState(existing.copy(
      inRetry = false,
      retryCount = 0,
      retryMessage = None,
      lastRetryAt = None
    ))
  }
}
```

- [ ] **Step 2: Add setRetryState method to DsInfoService**

```scala
def setRetryState(spec: String, inRetry: Boolean, retryCount: Int,
                  retryMessage: Option[String], lastRetryAt: Option[Instant]): Unit = {
  repo.getState(spec).foreach { existing =>
    repo.upsertState(existing.copy(
      inRetry = inRetry,
      retryCount = retryCount,
      retryMessage = retryMessage,
      lastRetryAt = lastRetryAt
    ))
  }
}
```

- [ ] **Step 3: Add pgWrite calls in DsInfo retry methods**

In `DsInfo.setInRetry` (line 1230), add after `setSingularLiteralProps`:
```scala
pgWrite(_.setRetryState(spec, inRetry = true, retryCount = retryCount,
  retryMessage = Some(message), lastRetryAt = Some(java.time.Instant.now())))
```

In `DsInfo.incrementRetryCount` (line 1248), add after `setSingularLiteralProps`:
```scala
pgWrite(_.setRetryState(spec, inRetry = true, retryCount = newCount,
  retryMessage = None, lastRetryAt = Some(java.time.Instant.now())))
```

`DsInfo.clearRetryState` (line 1261) already has `pgWrite(_.clearRetryState(spec))` — this will now work correctly after Step 1.

- [ ] **Step 4: Wire operationStatus writes**

In `DsInfo.setCurrentOperation` and `clearOperation`, add `pgWrite` for `operationStatus`.

- [ ] **Step 5: Add pgWrite for processedExternally in AppController**

In `AppController.scala` line 2141, add a `pgWrite` call before/after the `setSingularLiteralProps(processedExternally -> source)` call:
```scala
GlobalDsInfoService.get().foreach(_.upsertMappingConfig(spec, processedExternally = Some(source)))
```

- [ ] **Step 4: Compile and test**

Run: `make compile && sbt test`

- [ ] **Step 5: Commit**

```
git commit -m "feat: wire retry state and operation status writes to PostgreSQL"
```

---

### Task 7: Wire harvest JSON fields read path

The `harvest_json JSONB` column exists in V1 but is never read. Wire the snapshot to extract JSON harvest fields from it.

**Files:**
- Modify: `app/services/DatasetRepository.scala` — add `harvestJson` field to `HarvestConfigRecord`
- Modify: `app/services/PostgresDatasetRepository.scala` — read/write `harvest_json`
- Modify: `app/services/PropertySnapshot.scala` — parse JSON fields

- [ ] **Step 1: Add `harvestJson: Option[String] = None` to `HarvestConfigRecord`**

- [ ] **Step 2: Update `readHarvestConfig` to read `harvest_json` column**

- [ ] **Step 3: Update `upsertHarvestConfig` to write `harvest_json`**

- [ ] **Step 4: In PropertySnapshot.load, parse JSON fields**

```scala
// Parse harvest_json JSONB for individual fields
hc.harvestJson.foreach { jsonStr =>
  try {
    val json = play.api.libs.json.Json.parse(jsonStr)
    (json \ "itemsPath").asOpt[String].foreach(v => props += "harvestJsonItemsPath" -> v)
    (json \ "idPath").asOpt[String].foreach(v => props += "harvestJsonIdPath" -> v)
    (json \ "totalPath").asOpt[String].foreach(v => props += "harvestJsonTotalPath" -> v)
    (json \ "pageParam").asOpt[String].foreach(v => props += "harvestJsonPageParam" -> v)
    (json \ "pageSizeParam").asOpt[String].foreach(v => props += "harvestJsonPageSizeParam" -> v)
    (json \ "pageSize").asOpt[Int].foreach(v => props += "harvestJsonPageSize" -> v.toString)
    (json \ "detailPath").asOpt[String].foreach(v => props += "harvestJsonDetailPath" -> v)
    (json \ "skipDetail").asOpt[Boolean].foreach(v => props += "harvestJsonSkipDetail" -> v.toString)
    (json \ "xmlRoot").asOpt[String].foreach(v => props += "harvestJsonXmlRoot" -> v)
    (json \ "xmlRecord").asOpt[String].foreach(v => props += "harvestJsonXmlRecord" -> v)
  } catch {
    case _: Exception => // malformed JSON — skip
  }
}
```

- [ ] **Step 5: Add test for JSON fields in PropertySnapshotSpec**

- [ ] **Step 6: Compile and test**

- [ ] **Step 7: Commit**

```
git commit -m "feat: wire harvest JSON fields through snapshot read path"
```

---

### Task 8: Integration verification

**Files:** None (testing only)

- [ ] **Step 1: Run full test suite**

Run: `sbt test`
Expected: All tests pass.

- [ ] **Step 2: Manual verification**

Start the app with PostgreSQL configured. Run a dataset workflow (harvest or analyze). Check logs:
- No `[FUSEKI READ] GET GRAPH` during workflow (except SKOS)
- No `CACHE MISS` warnings
- Workflow completes successfully

- [ ] **Step 3: Verify with Fuseki reads disabled**

Set `narthex.fuseki.reads-enabled=false` in config. Run a workflow. Confirm it completes without Fuseki errors (SKOS operations may fail — that's expected and Phase 5).

- [ ] **Step 4: Human diff review**

Reference skill: diff-review-before-task-commit
Wait for human acknowledgment before proceeding to final commit.

- [ ] **Step 5: Final commit if needed**

```
git commit -m "feat: integration-verified — DsInfo reads routed through PostgreSQL snapshot"
```
