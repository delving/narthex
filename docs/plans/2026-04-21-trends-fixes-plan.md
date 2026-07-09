# Trends Feature Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix 17 issues in the trends feature causing the UI to show wrong / missing data. Address blockers and high-severity bugs that explain "UI shows wrong indexed numbers and no movement".

**Architecture:** Trends data flows: DatasetActor captures snapshot on SAVE → `trends.jsonl` (event log) → `OrgContext.runDailyTrendAggregation` at 00:30 → `trends-daily.jsonl` (per-day rollup) → `trends_summary.json` (org-level cache) → `AppController` endpoints → AngularJS UI. Fixes preserve this flow; they correct bad writes, wrong reads, and stale caches.

**Tech Stack:** Scala 2.13, Play 2.8, Akka, Joda Time, ScalaTest, AngularJS 1.3. Build via `make compile`. Tests via `sbt "testOnly services.TrendTrackingServiceSpec"`.

---

## Task Ordering

Tasks 1 and 2 fix the **two root causes** of "UI shows wrong data": fake indexed counts on save, and wrong-day aggregation. Do these first and deploy — they likely restore most expected UI behavior.

Tasks 3–7 address remaining high-severity data-correctness bugs (Hub3 dropout, bootstrap, race, timezone, delta off-by-one).

Tasks 8–13 address medium/low bugs (guard over-aggression, cache staleness, UI classification).

Every task ends with a human diff review step before commit, per the project workflow (`CLAUDE.md`).

---

## File Inventory

**Modified files:**
- `app/dataset/DatasetActor.scala` (line 1088–1126) — fix SAVE indexed capture
- `app/services/TrendTrackingService.scala` — fix `aggregateDay`, `calculateDeltaFromDailySummaries`, guards, timestamp handling, `cleanupOldSnapshots`, `getLastSnapshot`, `buildOrganizationTrends`
- `app/organization/OrgContext.scala` (line 90–166) — Hub3-unreachable handling, bootstrap aggregation, scheduler
- `app/services/IndexStatsService.scala` (line 95–140) — return `Option[Map]` or `Either` to distinguish unreachable from zero
- `app/controllers/AppController.scala` (line 203–311) — consistent totalDatasets, initializing state
- `app/assets/javascripts/trends/trends-controllers.js` — chart classification, initializing indicator
- `test/services/TrendTrackingServiceSpec.scala` — regression tests per task

---

## Task 1: Stop faking indexedRecords on SAVE event (BLOCKER #1)

SAVE-time event captures hard-code `indexedRecords = validRecords`. This is wrong: Hub3 push runs async; at SAVE time the real indexed count is the **previous** indexed count (unchanged until BulkAPI catches up). Solution: carry the previous snapshot's `indexedRecords` forward; if no prior snapshot exists, use 0.

**Files:**
- Modify: `app/dataset/DatasetActor.scala:1103`
- Modify: `app/services/TrendTrackingService.scala` (add overload `captureEventSnapshot` that auto-carries indexed)
- Test: `test/services/TrendTrackingServiceSpec.scala`

- [ ] **Step 1: Write failing test `captureEventSnapshot carries previous indexedRecords when indexedRecords argument is omitted`**

```scala
// Append to TrendTrackingServiceSpec.scala
it should "carry previous indexedRecords forward when caller omits it" in withTempDir { tmpDir =>
  val trendsLog = new File(tmpDir, "trends.jsonl")
  // Seed with a prior snapshot indicating Hub3 has 800 indexed
  TrendTrackingService.captureEventSnapshot(
    trendsLog, "save",
    sourceRecords = 1000, acquiredRecords = 1000, deletedRecords = 0,
    validRecords = 900, invalidRecords = 100, indexedRecords = 800
  )
  // New SAVE: valid changed to 950, but Hub3 index not yet refreshed
  TrendTrackingService.captureEventSnapshotCarryingIndexed(
    trendsLog, "save",
    sourceRecords = 1050, acquiredRecords = 1050, deletedRecords = 0,
    validRecords = 950, invalidRecords = 100
  )
  val snaps = TrendTrackingService.readSnapshots(trendsLog)
  snaps.size shouldBe 2
  snaps.last.indexedRecords shouldBe 800  // carried, NOT 950
  snaps.last.validRecords shouldBe 950
}

it should "use 0 indexed when no previous snapshot exists" in withTempDir { tmpDir =>
  val trendsLog = new File(tmpDir, "trends.jsonl")
  TrendTrackingService.captureEventSnapshotCarryingIndexed(
    trendsLog, "save",
    sourceRecords = 100, acquiredRecords = 100, deletedRecords = 0,
    validRecords = 90, invalidRecords = 10
  )
  val snaps = TrendTrackingService.readSnapshots(trendsLog)
  snaps.head.indexedRecords shouldBe 0
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `unset JDK_JAVA_OPTIONS; sbt "testOnly services.TrendTrackingServiceSpec" 2>&1 | tail -20`
Expected: compile error — `captureEventSnapshotCarryingIndexed is not a member`.

- [ ] **Step 3: Implement `captureEventSnapshotCarryingIndexed` in TrendTrackingService**

Add after `captureEventSnapshot` (around line 549):

```scala
/**
 * Like captureEventSnapshot but inherits indexedRecords from the previous snapshot.
 * Use this at SAVE time because Hub3 indexing is async and the true indexed count
 * is not yet known. The daily aggregation path (OrgContext.runDailyTrendAggregation)
 * reconciles with Hub3 and writes the real indexed count.
 */
def captureEventSnapshotCarryingIndexed(
  trendsLog: File,
  event: String,
  sourceRecords: Int,
  acquiredRecords: Int,
  deletedRecords: Int,
  validRecords: Int,
  invalidRecords: Int
): Unit = {
  val carriedIndexed = getLastSnapshot(trendsLog).map(_.indexedRecords).getOrElse(0)
  captureEventSnapshot(
    trendsLog = trendsLog,
    event = event,
    sourceRecords = sourceRecords,
    acquiredRecords = acquiredRecords,
    deletedRecords = deletedRecords,
    validRecords = validRecords,
    invalidRecords = invalidRecords,
    indexedRecords = carriedIndexed
  )
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `unset JDK_JAVA_OPTIONS; sbt "testOnly services.TrendTrackingServiceSpec" 2>&1 | tail -10`
Expected: all tests pass.

- [ ] **Step 5: Switch DatasetActor SAVE handler to the new method**

In `app/dataset/DatasetActor.scala:1097-1118`, replace the `val indexedRecords = validRecords` line and the `captureEventSnapshot` call with:

```scala
try {
  val sourceRecords = dsInfo.getLiteralProp(sourceRecordCount).map(_.toInt).getOrElse(0)
  val acquiredRecords = dsInfo.getLiteralProp(acquiredRecordCount).map(_.toInt).getOrElse(0)
  val deletedRecords = dsInfo.getLiteralProp(deletedRecordCount).map(_.toInt).getOrElse(0)
  val validRecords = dsInfo.getLiteralProp(processedValid).map(_.toInt).getOrElse(0)
  val invalidRecords = dsInfo.getLiteralProp(processedInvalid).map(_.toInt).getOrElse(0)

  TrendTrackingService.captureEventSnapshotCarryingIndexed(
    trendsLog = datasetContext.trendsLog,
    event = "save",
    sourceRecords = sourceRecords,
    acquiredRecords = acquiredRecords,
    deletedRecords = deletedRecords,
    validRecords = validRecords,
    invalidRecords = invalidRecords
  )
} catch {
  case e: Exception =>
    log.warning(s"Failed to capture trend snapshot: ${e.getMessage}")
}
```

- [ ] **Step 6: Compile**

Run: `unset JDK_JAVA_OPTIONS; make compile 2>&1 | tail -5`
Expected: `[success]`.

- [ ] **Step 7: Run trend tests**

Run: `unset JDK_JAVA_OPTIONS; sbt "testOnly services.TrendTrackingServiceSpec" 2>&1 | tail -10`
Expected: all pass.

- [ ] **Step 8: Human diff review**

Reference skill: diff-review-before-task-commit
Wait for human acknowledgment before proceeding to commit.

- [ ] **Step 9: Commit**

```bash
git add app/dataset/DatasetActor.scala app/services/TrendTrackingService.scala test/services/TrendTrackingServiceSpec.scala
git commit -m "$(cat <<'EOF'
fix(trends): carry previous indexed count on SAVE events

SAVE-time event captures were hard-coding indexedRecords = validRecords,
producing phantom indexed gains equal to valid gains. The real indexed
count only updates after Hub3's async push completes, so the correct
value at SAVE time is whatever the previous snapshot recorded.

New captureEventSnapshotCarryingIndexed reads the last snapshot's
indexedRecords and uses it; falls back to 0 for first-ever snapshot.
Daily aggregation still reconciles against Hub3 and writes real indexed.

Addresses review finding #1 (BLOCKER).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: Fix `aggregateDay` to pick last snapshot from the target date (BLOCKER #5)

`aggregateDay` uses `getLastSnapshot(trendsLog)` which returns the globally-most-recent snapshot regardless of date. If the 00:30 aggregation fires after an early-morning event, "yesterday" summary gets polluted with today's counts. Fix: filter snapshots to the target date and take the last one within that date.

**Files:**
- Modify: `app/services/TrendTrackingService.scala` (method `aggregateDay`, line 594)
- Test: `test/services/TrendTrackingServiceSpec.scala`

- [ ] **Step 1: Write failing test `aggregateDay uses last snapshot from the target date, not global last`**

```scala
it should "aggregate the last snapshot within the target date, not the global last" in withTempDir { tmpDir =>
  val trendsLog = new File(tmpDir, "trends.jsonl")
  val dailyLog = new File(tmpDir, "trends-daily.jsonl")

  // Simulate yesterday's counts (end-of-day = 1000)
  val yesterday = org.joda.time.DateTime.now().minusDays(1).withTime(23, 50, 0, 0)
  val today = org.joda.time.DateTime.now().withTime(0, 5, 0, 0)

  val yesterdaySnap = TrendSnapshot(
    timestamp = yesterday,
    snapshotType = "save",
    sourceRecords = 1000, acquiredRecords = 1000, deletedRecords = 0,
    validRecords = 900, invalidRecords = 100, indexedRecords = 850
  )
  val todaySnap = TrendSnapshot(
    timestamp = today,
    snapshotType = "save",
    sourceRecords = 1500, acquiredRecords = 1500, deletedRecords = 0,  // DO NOT use this
    validRecords = 1400, invalidRecords = 100, indexedRecords = 850
  )

  val yesterdayDate = yesterday.toString("yyyy-MM-dd")

  import play.api.libs.json.Json
  val w = services.FileHandling.appender(trendsLog)
  try {
    w.write(Json.stringify(Json.toJson(yesterdaySnap)) + "\n")
    w.write(Json.stringify(Json.toJson(todaySnap)) + "\n")
  } finally { w.close() }

  TrendTrackingService.aggregateDay(trendsLog, dailyLog, yesterdayDate)

  val summaries = TrendTrackingService.readDailySummaries(dailyLog)
  summaries.size shouldBe 1
  // Must contain yesterday's 1000, not today's 1500
  summaries.head.endOfDay.sourceRecords shouldBe 1000
  summaries.head.endOfDay.validRecords shouldBe 900
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `unset JDK_JAVA_OPTIONS; sbt "testOnly services.TrendTrackingServiceSpec" 2>&1 | tail -20`
Expected: fail — assertion `sourceRecords shouldBe 1000` produces `1500`.

- [ ] **Step 3: Implement fix in `aggregateDay`**

Replace the body of `aggregateDay` (line 594–632) with:

```scala
def aggregateDay(trendsLog: File, dailyLog: File, date: String): Unit = {
  val allSnapshots = readSnapshots(trendsLog)

  // Filter to snapshots whose ISO timestamp begins with the target date.
  val daySnapshots = allSnapshots.filter { s =>
    timeToString(s.timestamp).startsWith(date)
  }

  val endOfDaySnapshot = daySnapshots.sortBy(_.timestamp.getMillis).lastOption

  if (endOfDaySnapshot.isEmpty) {
    // No snapshots on that date — carry forward previous day's end-of-day if available.
    val previousSummary = getLastDailySummary(dailyLog)
    previousSummary match {
      case Some(prev) =>
        val summary = DailySummary(
          date = date,
          endOfDay = prev.endOfDay,
          delta = TrendDelta.zero,
          events = 0
        )
        appendDailySummary(dailyLog, summary)
        logger.info(s"Aggregated $date with carry-forward (no snapshots on that date): source=${prev.endOfDay.sourceRecords}")
      case None =>
        logger.debug(s"No snapshots or previous summary available for $date; skipping")
    }
    return
  }

  val endOfDay = EndOfDayCounts.fromSnapshot(endOfDaySnapshot.get)
  val todayEvents = daySnapshots.size

  val previousSummary = getLastDailySummary(dailyLog)
  val delta = previousSummary match {
    case Some(prev) =>
      TrendDelta(
        source = endOfDay.sourceRecords - prev.endOfDay.sourceRecords,
        valid = endOfDay.validRecords - prev.endOfDay.validRecords,
        indexed = endOfDay.indexedRecords - prev.endOfDay.indexedRecords
      )
    case None =>
      TrendDelta.zero
  }

  val summary = DailySummary(
    date = date,
    endOfDay = endOfDay,
    delta = delta,
    events = todayEvents
  )

  appendDailySummary(dailyLog, summary)
  logger.info(s"Aggregated daily summary for $date: source=${endOfDay.sourceRecords}, delta=${delta.source}, events=$todayEvents")
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `unset JDK_JAVA_OPTIONS; sbt "testOnly services.TrendTrackingServiceSpec" 2>&1 | tail -10`
Expected: all pass.

- [ ] **Step 5: Add carry-forward test**

```scala
it should "carry forward previous end-of-day when no snapshots exist on target date" in withTempDir { tmpDir =>
  val trendsLog = new File(tmpDir, "trends.jsonl")
  val dailyLog = new File(tmpDir, "trends-daily.jsonl")

  // Seed a previous daily summary for 2026-04-18
  val prev = DailySummary(
    date = "2026-04-18",
    endOfDay = EndOfDayCounts(
      sourceRecords = 500, acquiredRecords = 500, deletedRecords = 0,
      validRecords = 450, invalidRecords = 50, indexedRecords = 400
    ),
    delta = TrendDelta.zero,
    events = 2
  )
  TrendTrackingService.appendDailySummary(dailyLog, prev)

  // Aggregate 2026-04-19 when trendsLog has no snapshots for that date
  TrendTrackingService.aggregateDay(trendsLog, dailyLog, "2026-04-19")

  val summaries = TrendTrackingService.readDailySummaries(dailyLog)
  summaries.size shouldBe 2
  summaries.last.date shouldBe "2026-04-19"
  summaries.last.endOfDay.sourceRecords shouldBe 500  // carried from previous day
  summaries.last.delta shouldBe TrendDelta.zero
  summaries.last.events shouldBe 0
}
```

- [ ] **Step 6: Run tests**

Run: `unset JDK_JAVA_OPTIONS; sbt "testOnly services.TrendTrackingServiceSpec" 2>&1 | tail -10`
Expected: all pass.

- [ ] **Step 7: Compile full project**

Run: `unset JDK_JAVA_OPTIONS; make compile 2>&1 | tail -5`
Expected: `[success]`.

- [ ] **Step 8: Human diff review**

Reference skill: diff-review-before-task-commit
Wait for human acknowledgment before proceeding to commit.

- [ ] **Step 9: Commit**

```bash
git add app/services/TrendTrackingService.scala test/services/TrendTrackingServiceSpec.scala
git commit -m "$(cat <<'EOF'
fix(trends): aggregateDay picks last snapshot on the target date

Previously aggregateDay used getLastSnapshot(trendsLog) which returned
the globally-most-recent snapshot regardless of date. When the 00:30
aggregator ran after an early-morning event capture, yesterday's
summary got polluted with today's counts.

Filter snapshots by ISO date prefix first, then take the last one
within that date. When no snapshots exist on the target date, carry
forward the previous day's end-of-day counts with zero delta instead
of skipping entirely, so the UI chart has a continuous series.

Addresses review finding #5 (BLOCKER).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: Distinguish Hub3 unreachable from zero records (BLOCKER #2)

`fetchHub3IndexCounts` returns `Map.empty` both when Hub3 is reachable-but-has-no-data AND when Hub3 is down. `runDailyTrendAggregation` cannot tell the difference, so transient Hub3 outages silently skip the "update indexed count" path, leaving stale counts forever.

**Files:**
- Modify: `app/services/IndexStatsService.scala` — change return type to signal unreachable
- Modify: `app/organization/OrgContext.scala:108-166` — respect the new signal

- [ ] **Step 1: Read `fetchHub3IndexCounts` signature**

Run: `grep -n "fetchHub3IndexCounts" app/services/IndexStatsService.scala`
Note: current signature `def fetchHub3IndexCounts(): Future[(Long, Map[String, Int])]`.

- [ ] **Step 2: Introduce a richer return type**

Edit `app/services/IndexStatsService.scala` (around line 80): add case class above the class body:

```scala
case class Hub3IndexCounts(total: Long, counts: Map[String, Int], reachable: Boolean)
```

Change `fetchHub3IndexCounts()` return type to `Future[Hub3IndexCounts]`. At line 110 (non-200 response) replace `(0L, Map.empty[String, Int])` with `Hub3IndexCounts(0L, Map.empty, reachable = false)`. In the successful parse branch, return `Hub3IndexCounts(total, counts, reachable = true)`. In any `recover`/failure path at the end of the method, return `Hub3IndexCounts(0L, Map.empty, reachable = false)`.

- [ ] **Step 3: Update all callers**

Run: `grep -rn "fetchHub3IndexCounts" app/ 2>&1`
For each call site, destructure via the new case class. Key sites:
- `app/organization/OrgContext.scala:115` — change pattern to `hub3 <- indexStatsService.fetchHub3IndexCounts()` and use `hub3.counts` / `hub3.reachable`.
- `app/controllers/AppController.scala` — any callers; preserve prior behaviour by using `.counts` and `.total`.

- [ ] **Step 4: Gate aggregation on `hub3.reachable`**

Replace `app/organization/OrgContext.scala:113-145` with:

```scala
val result = for {
  datasets <- DsInfo.listDsInfo(this)
  hub3 <- indexStatsService.fetchHub3IndexCounts()
} yield {
  if (!hub3.reachable) {
    logger.warn(s"Skipping daily trend aggregation: Hub3 unreachable")
  } else {
    var aggregated = 0
    val specs = scala.collection.mutable.ListBuffer[String]()

    datasets.foreach { dsInfo =>
      try {
        val ctx = datasetContext(dsInfo.spec)
        val trendsLog = ctx.trendsLog
        val dailyLog = ctx.trendsDailyLog
        val hub3Count = hub3.counts.getOrElse(dsInfo.spec, 0)

        val lastSnapshot = TrendTrackingService.getLastSnapshot(trendsLog)
        lastSnapshot.foreach { last =>
          if (last.indexedRecords != hub3Count) {
            TrendTrackingService.captureEventSnapshot(
              trendsLog, "daily",
              sourceRecords = last.sourceRecords,
              acquiredRecords = last.acquiredRecords,
              deletedRecords = last.deletedRecords,
              validRecords = last.validRecords,
              invalidRecords = last.invalidRecords,
              indexedRecords = hub3Count
            )
          }
        }

        TrendTrackingService.aggregateDay(trendsLog, dailyLog, yesterday)
        specs += dsInfo.spec
        aggregated += 1
      } catch {
        case e: Exception =>
          logger.warn(s"Failed to aggregate trends for ${dsInfo.spec}: ${e.getMessage}")
      }
    }

    try {
      TrendTrackingService.generateTrendsSummaryFromDaily(trendsSummaryFile, datasetsDir, specs.toList)
    } catch {
      case e: Exception =>
        logger.warn(s"Failed to generate trends summary: ${e.getMessage}")
    }

    logger.info(s"Daily trend aggregation complete: $aggregated/${datasets.size} datasets")
  }
}
```

Note: the `if (last.indexedRecords != hub3Count && hub3Count > 0)` guard lost its `> 0` clause — reachable Hub3 reporting 0 is legitimate (dataset was fully de-indexed).

- [ ] **Step 5: Compile**

Run: `unset JDK_JAVA_OPTIONS; make compile 2>&1 | tail -10`
Expected: `[success]`. If callers failed, fix each to use the new case class.

- [ ] **Step 6: Run full test suite**

Run: `unset JDK_JAVA_OPTIONS; sbt test 2>&1 | tail -10`
Expected: all pass.

- [ ] **Step 7: Human diff review**

Reference skill: diff-review-before-task-commit
Wait for human acknowledgment before proceeding to commit.

- [ ] **Step 8: Commit**

```bash
git add app/services/IndexStatsService.scala app/organization/OrgContext.scala app/controllers/AppController.scala
git commit -m "$(cat <<'EOF'
fix(trends): skip daily aggregation when Hub3 unreachable

fetchHub3IndexCounts now returns Hub3IndexCounts(total, counts,
reachable). When reachable=false (network error, non-200 response),
runDailyTrendAggregation logs a warning and skips the entire loop,
preserving previously-correct indexed counts instead of corrupting
them with zeros.

When Hub3 is reachable and reports 0 for a dataset, that IS
meaningful (full de-index), so the > 0 guard was removed.

Addresses review finding #2 (BLOCKER).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: Bootstrap aggregation so UI has data on fresh install (HIGH #3)

On a fresh install or when `narthex.trends.enabled` has been off for a while, `trends-daily.jsonl` is empty, so `readTrendsSummary` returns None and `getDatasetTrendSummaryFromDaily` falls through to the raw event log path with insufficient snapshots. UI shows 200 datasets in "stable" with zero deltas. Fix: run one aggregation at startup if `trends-daily.jsonl` is missing or empty.

**Files:**
- Modify: `app/organization/OrgContext.scala` (scheduler startup block)

- [ ] **Step 1: Extract aggregation body**

Refactor `runDailyTrendAggregation()` signature (line 108) to accept a `date: String` parameter defaulting to yesterday, so we can call it for today at bootstrap.

```scala
private def runDailyTrendAggregation(): Unit = {
  runTrendAggregation(org.joda.time.LocalDate.now().minusDays(1).toString("yyyy-MM-dd"))
}

private def runTrendAggregation(date: String): Unit = {
  logger.info(s"Running trend aggregation for $date...")
  // ... existing body, using `date` instead of `yesterday`
}
```

Rename the local `val yesterday = ...` line; the new `date` parameter replaces it.

- [ ] **Step 2: Add bootstrap call inside `scheduleDailyTrendSnapshot`**

At the end of `scheduleDailyTrendSnapshot()` (line 106), before `}` closing the method, add:

```scala
// Bootstrap: if the org has no trends_summary.json yet, run an aggregation
// immediately so the UI has something to show. Uses today's date as the
// target date to capture whatever snapshots exist so far.
if (!trendsSummaryFile.exists() || trendsSummaryFile.length() == 0) {
  logger.info("No trends summary found — running bootstrap aggregation")
  val today = org.joda.time.LocalDate.now().toString("yyyy-MM-dd")
  runTrendAggregation(today)
}
```

- [ ] **Step 3: Compile**

Run: `unset JDK_JAVA_OPTIONS; make compile 2>&1 | tail -5`
Expected: `[success]`.

- [ ] **Step 4: Human diff review**

Reference skill: diff-review-before-task-commit

- [ ] **Step 5: Commit**

```bash
git add app/organization/OrgContext.scala
git commit -m "$(cat <<'EOF'
feat(trends): bootstrap aggregation on startup when summary missing

Fresh installs or installs with trends previously disabled had empty
trends_summary.json, causing the UI to show all datasets as stable
with zero deltas. Run one aggregation at startup when the summary
file is missing or empty so the UI has usable data immediately
instead of waiting for 00:30 the next day.

Addresses review finding #3 (HIGH).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: Fix race in `cleanupOldSnapshots` (HIGH #6)

Rewrite sequence (read file → write tmp → rename) races with concurrent `appendSnapshot`. Lines written during the copy window are lost on rename.

**Files:**
- Modify: `app/services/TrendTrackingService.scala` (method `cleanupOldSnapshots`)

- [ ] **Step 1: Add file-based lock**

Replace the body of `cleanupOldSnapshots` (line 340-355) with:

```scala
def cleanupOldSnapshots(trendsLog: File): Unit = {
  val lockFile = new File(trendsLog.getParent, trendsLog.getName + ".lock")
  val raf = new java.io.RandomAccessFile(lockFile, "rw")
  val channel = raf.getChannel
  val lock = channel.lock()
  try {
    val snapshots = readSnapshots(trendsLog)
    val cutoff = DateTime.now().minusDays(MAX_HISTORY_DAYS)

    val toKeep = snapshots.filter { s =>
      s.timestamp.isAfter(cutoff) || s.snapshotType == "daily"
    }.sortBy(_.timestamp.getMillis).takeRight(MAX_HISTORY_DAYS * 2)

    if (toKeep.size < snapshots.size) {
      val tmpFile = new File(trendsLog.getParent, trendsLog.getName + ".tmp")
      toKeep.foreach(s => appendSnapshot(tmpFile, s))
      java.nio.file.Files.move(
        tmpFile.toPath,
        trendsLog.toPath,
        java.nio.file.StandardCopyOption.ATOMIC_MOVE,
        java.nio.file.StandardCopyOption.REPLACE_EXISTING
      )
      logger.info(s"Cleaned up trends file: ${snapshots.size} -> ${toKeep.size} snapshots")
    }
  } finally {
    lock.release()
    channel.close()
    raf.close()
  }
}
```

Wrap `appendSnapshot` similarly so writers also acquire the same lock:

```scala
private def appendSnapshot(trendsLog: File, snapshot: TrendSnapshot): Unit = {
  val lockFile = new File(trendsLog.getParent, trendsLog.getName + ".lock")
  val raf = new java.io.RandomAccessFile(lockFile, "rw")
  val channel = raf.getChannel
  val lock = channel.lock()
  try {
    val line = Json.stringify(Json.toJson(snapshot)) + "\n"
    val writer = appender(trendsLog)
    try {
      writer.write(line)
      writer.flush()
    } finally {
      writer.close()
    }
  } finally {
    lock.release()
    channel.close()
    raf.close()
  }
}
```

- [ ] **Step 2: Verify with stress test**

Add test:

```scala
it should "not lose snapshots when cleanup runs concurrently with appends" in withTempDir { tmpDir =>
  val trendsLog = new File(tmpDir, "trends.jsonl")
  // Seed 100 snapshots
  (1 to 100).foreach { i =>
    TrendTrackingService.captureEventSnapshot(
      trendsLog, "save",
      sourceRecords = 1000 + i, acquiredRecords = 1000 + i, deletedRecords = 0,
      validRecords = 900, invalidRecords = 100, indexedRecords = 800
    )
  }

  // Concurrently append + cleanup
  val threads = (1 to 10).map { n =>
    new Thread(() => {
      TrendTrackingService.captureEventSnapshot(
        trendsLog, "save",
        sourceRecords = 2000 + n, acquiredRecords = 2000 + n, deletedRecords = 0,
        validRecords = 1800, invalidRecords = 200, indexedRecords = 1700
      )
    })
  }
  val cleaner = new Thread(() => TrendTrackingService.cleanupOldSnapshots(trendsLog))
  threads.foreach(_.start())
  cleaner.start()
  threads.foreach(_.join())
  cleaner.join()

  val finalSnaps = TrendTrackingService.readSnapshots(trendsLog)
  // All 10 concurrent writes must be present
  val concurrentIds = finalSnaps.map(_.sourceRecords).filter(_ >= 2001).toSet
  concurrentIds.size shouldBe 10
}
```

- [ ] **Step 3: Run test**

Run: `unset JDK_JAVA_OPTIONS; sbt "testOnly services.TrendTrackingServiceSpec" 2>&1 | tail -10`
Expected: all pass.

- [ ] **Step 4: Human diff review**

Reference skill: diff-review-before-task-commit

- [ ] **Step 5: Commit**

```bash
git add app/services/TrendTrackingService.scala test/services/TrendTrackingServiceSpec.scala
git commit -m "$(cat <<'EOF'
fix(trends): serialize trends.jsonl writes with file lock

cleanupOldSnapshots and appendSnapshot both grab a FileChannel.lock on
a sidecar .lock file and use Files.move with ATOMIC_MOVE, so
concurrent SAVE events don't have their lines silently dropped during
a cleanup rewrite window.

Addresses review finding #6 (HIGH).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 6: Timezone consistency for capture and aggregation (HIGH #7)

Capture uses `DateTime.now()` (system default zone). Aggregation compares `timeToString(s.timestamp)` (formatted in the timestamp's zone) against `LocalDate.now().minusDays(1)` string. Near midnight, mismatched zones cause "snapshot taken at 23:55 UTC" to be assigned to the wrong date.

**Files:**
- Modify: `app/services/TrendTrackingService.scala` — canonicalize to UTC in capture and in date-prefix filter
- Modify: `app/services/Temporal.scala` — add a `timeToIsoUtc` helper if not present

- [ ] **Step 1: Inspect Temporal.scala**

Run: `grep -n "def time" app/services/Temporal.scala 2>&1`

- [ ] **Step 2: Add UTC timestamp helper**

Edit `app/services/Temporal.scala`. Add (adapt package to match file):

```scala
def timeToIsoUtc(dt: DateTime): String =
  dt.withZone(org.joda.time.DateTimeZone.UTC).toString("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
```

- [ ] **Step 3: Force captures to UTC**

In `TrendTrackingService.scala`, wherever we call `DateTime.now()` (lines 221, 263, 283, 342, 386, 538, 690, 731), replace with:

```scala
DateTime.now(org.joda.time.DateTimeZone.UTC)
```

And in `aggregateDay` (already updated in Task 2), replace the date-prefix filter:

```scala
val daySnapshots = allSnapshots.filter { s =>
  s.timestamp.withZone(org.joda.time.DateTimeZone.UTC).toString("yyyy-MM-dd") == date
}
```

Change `OrgContext.runTrendAggregation` (from Task 4) to build `date` in UTC:

```scala
private def runDailyTrendAggregation(): Unit = {
  val yesterday = org.joda.time.LocalDate.now(org.joda.time.DateTimeZone.UTC).minusDays(1).toString("yyyy-MM-dd")
  runTrendAggregation(yesterday)
}
```

- [ ] **Step 4: Test near-midnight boundary**

```scala
it should "assign a 23:55 UTC snapshot to that same UTC date" in withTempDir { tmpDir =>
  val trendsLog = new File(tmpDir, "trends.jsonl")
  val dailyLog = new File(tmpDir, "trends-daily.jsonl")

  val nearMidnight = new DateTime(2026, 4, 19, 23, 55, 0, org.joda.time.DateTimeZone.UTC)
  val snap = TrendSnapshot(
    timestamp = nearMidnight,
    snapshotType = "save",
    sourceRecords = 100, acquiredRecords = 100, deletedRecords = 0,
    validRecords = 90, invalidRecords = 10, indexedRecords = 80
  )
  val w = services.FileHandling.appender(trendsLog)
  try { w.write(play.api.libs.json.Json.stringify(play.api.libs.json.Json.toJson(snap)) + "\n") }
  finally { w.close() }

  TrendTrackingService.aggregateDay(trendsLog, dailyLog, "2026-04-19")

  val sums = TrendTrackingService.readDailySummaries(dailyLog)
  sums.size shouldBe 1
  sums.head.date shouldBe "2026-04-19"
  sums.head.endOfDay.sourceRecords shouldBe 100
}
```

- [ ] **Step 5: Run tests + compile**

Run: `unset JDK_JAVA_OPTIONS; sbt "testOnly services.TrendTrackingServiceSpec" 2>&1 | tail -10 && make compile 2>&1 | tail -5`

- [ ] **Step 6: Human diff review**

Reference skill: diff-review-before-task-commit

- [ ] **Step 7: Commit**

```bash
git add app/services/Temporal.scala app/services/TrendTrackingService.scala app/organization/OrgContext.scala test/services/TrendTrackingServiceSpec.scala
git commit -m "$(cat <<'EOF'
fix(trends): canonicalize capture and aggregation to UTC

Captures used DateTime.now() (system zone), but date comparisons
used string prefix match. Near midnight, the resulting date-assignment
depended on the JVM timezone and could drop a snapshot from its
own day.

All captures now use DateTime.now(DateTimeZone.UTC) and aggregateDay
filters by UTC date. OrgContext computes `yesterday` in UTC too.

Addresses review finding #7 (HIGH).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 7: Fix `delta7d`/`delta30d` off-by-one on short history (HIGH #4)

`calculateDeltaFromDailySummaries` returns `TrendDelta.zero` when no summary exists at or before `currentDate - daysAgo`. Early weeks of operation therefore show flat zeros despite real growth. Fall back to the **oldest** available summary.

**Files:**
- Modify: `app/services/TrendTrackingService.scala:636-654`

- [ ] **Step 1: Write failing test**

```scala
it should "fall back to oldest summary when no baseline at cutoff for 7d delta" in withTempDir { _ =>
  val summaries = List(
    DailySummary("2026-04-19", EndOfDayCounts(100, 100, 0, 90, 10, 80), TrendDelta.zero, 1),
    DailySummary("2026-04-20", EndOfDayCounts(200, 200, 0, 180, 20, 170), TrendDelta.zero, 1),
    DailySummary("2026-04-21", EndOfDayCounts(300, 300, 0, 280, 20, 260), TrendDelta.zero, 1)
  )
  val delta = TrendTrackingService.calculateDeltaFromDailySummaries(summaries, 7)
  // Only 3 days of data — should use oldest (2026-04-19) as baseline
  delta.source shouldBe 200   // 300 - 100
  delta.valid shouldBe 190    // 280 - 90
  delta.indexed shouldBe 180  // 260 - 80
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `unset JDK_JAVA_OPTIONS; sbt "testOnly services.TrendTrackingServiceSpec" 2>&1 | tail -10`
Expected: fail — delta is `0` / `0` / `0` not the expected non-zero numbers.

- [ ] **Step 3: Implement fix**

Replace body of `calculateDeltaFromDailySummaries` (line 636-654):

```scala
def calculateDeltaFromDailySummaries(summaries: List[DailySummary], daysAgo: Int): TrendDelta = {
  if (summaries.size < 2) return TrendDelta.zero

  val current = summaries.last
  val cutoffDate = org.joda.time.LocalDate.parse(current.date).minusDays(daysAgo).toString("yyyy-MM-dd")

  // Prefer a baseline on or before the cutoff; otherwise fall back to the oldest summary
  // so short histories still show accurate growth within the available range.
  val baseline = summaries
    .filter(_.date <= cutoffDate)
    .lastOption
    .orElse(summaries.headOption.filterNot(_ == current))

  baseline match {
    case Some(base) =>
      TrendDelta(
        source = current.endOfDay.sourceRecords - base.endOfDay.sourceRecords,
        valid = current.endOfDay.validRecords - base.endOfDay.validRecords,
        indexed = current.endOfDay.indexedRecords - base.endOfDay.indexedRecords
      )
    case None =>
      TrendDelta.zero
  }
}
```

- [ ] **Step 4: Run test**

Run: `unset JDK_JAVA_OPTIONS; sbt "testOnly services.TrendTrackingServiceSpec" 2>&1 | tail -10`
Expected: all pass.

- [ ] **Step 5: Human diff review**

Reference skill: diff-review-before-task-commit

- [ ] **Step 6: Commit**

```bash
git add app/services/TrendTrackingService.scala test/services/TrendTrackingServiceSpec.scala
git commit -m "$(cat <<'EOF'
fix(trends): delta7d/30d fall back to oldest baseline on short histories

When no daily summary exists on or before cutoff-minus-daysAgo, fall
back to the oldest available summary instead of returning zero. UI no
longer shows flat zero deltas for the first weeks of operation.

Addresses review finding #4 (HIGH).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 8: Relax `captureEventSnapshot` guards (MEDIUM #8, #9)

- `sourceRecords == 0` guard drops legitimate depublications — UI cannot show "dataset went empty".
- `>50% drop` guard drops legitimate large deletions.

Solution: only skip `sourceRecords == 0` when there's no prior snapshot (true pre-harvest state). For the drop guard, still log but write the snapshot.

**Files:**
- Modify: `app/services/TrendTrackingService.scala:514-535`

- [ ] **Step 1: Write failing tests**

```scala
it should "capture a zero-source snapshot when a prior snapshot exists (depublication)" in withTempDir { tmpDir =>
  val trendsLog = new File(tmpDir, "trends.jsonl")
  TrendTrackingService.captureEventSnapshot(
    trendsLog, "save",
    sourceRecords = 500, acquiredRecords = 500, deletedRecords = 0,
    validRecords = 450, invalidRecords = 50, indexedRecords = 400
  )
  TrendTrackingService.captureEventSnapshot(
    trendsLog, "save",
    sourceRecords = 0, acquiredRecords = 0, deletedRecords = 500,
    validRecords = 0, invalidRecords = 0, indexedRecords = 0
  )
  TrendTrackingService.readSnapshots(trendsLog).size shouldBe 2
}

it should "still capture a snapshot when source drops >50%" in withTempDir { tmpDir =>
  val trendsLog = new File(tmpDir, "trends.jsonl")
  TrendTrackingService.captureEventSnapshot(
    trendsLog, "save",
    sourceRecords = 1000, acquiredRecords = 1000, deletedRecords = 0,
    validRecords = 900, invalidRecords = 100, indexedRecords = 800
  )
  TrendTrackingService.captureEventSnapshot(
    trendsLog, "save",
    sourceRecords = 300, acquiredRecords = 300, deletedRecords = 700,
    validRecords = 270, invalidRecords = 30, indexedRecords = 800
  )
  TrendTrackingService.readSnapshots(trendsLog).size shouldBe 2
}
```

- [ ] **Step 2: Update `captureEventSnapshot` guards**

Replace lines 514-535 with:

```scala
val lastSnapshot = getLastSnapshot(trendsLog)

// Pre-harvest state: no prior history AND counts are all zero → skip
if (lastSnapshot.isEmpty && sourceRecords == 0) {
  logger.debug("Skipping event snapshot: pre-harvest state with no prior history")
  return
}

lastSnapshot.foreach { prev =>
  if (prev.sourceRecords == sourceRecords &&
      prev.acquiredRecords == acquiredRecords &&
      prev.deletedRecords == deletedRecords &&
      prev.validRecords == validRecords &&
      prev.invalidRecords == invalidRecords &&
      prev.indexedRecords == indexedRecords) {
    logger.debug("Skipping event snapshot: counts unchanged")
    return
  }

  if (prev.sourceRecords > 0 && sourceRecords < prev.sourceRecords * 0.5) {
    logger.warn(s"Large drop in sourceRecords: ${prev.sourceRecords} -> $sourceRecords (recording anyway; verify not a partial harvest)")
  }
}
```

- [ ] **Step 3: Run tests**

Run: `unset JDK_JAVA_OPTIONS; sbt "testOnly services.TrendTrackingServiceSpec" 2>&1 | tail -10`
Expected: all pass.

- [ ] **Step 4: Human diff review**

Reference skill: diff-review-before-task-commit

- [ ] **Step 5: Commit**

```bash
git add app/services/TrendTrackingService.scala test/services/TrendTrackingServiceSpec.scala
git commit -m "$(cat <<'EOF'
fix(trends): record depublications and large deletions

The sourceRecords==0 guard was dropping legitimate depublications
(dataset emptied on purpose). The 50% drop guard was dropping
legitimate large deletions. Both now proceed to record the snapshot
and only log a warning for the large-drop case.

The only case that still skips is truly pre-harvest state: no prior
history at all AND all counts zero.

Addresses review findings #8 and #9 (MEDIUM).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 9: Cross-check `trends_summary.json` staleness (MEDIUM #10)

Current `readTrendsSummary` uses a 25-hour TTL against `generatedAt` timestamp. If aggregation failed, the file stays "fresh" forever. Cross-check against the newest `trends-daily.jsonl` mtime in the datasets directory.

**Files:**
- Modify: `app/services/TrendTrackingService.scala:414-439`
- Modify: `app/controllers/AppController.scala:265` — pass `datasetsDir`

- [ ] **Step 1: Extend signature**

Change `readTrendsSummary(summaryFile: File, maxAgeHours: Int = 25)` to `readTrendsSummary(summaryFile: File, datasetsDir: File, maxAgeHours: Int = 25)`.

Inside, after the `ageHours > maxAgeHours` check (line 429), add:

```scala
// Cross-check: if any per-dataset trends-daily.jsonl is newer than the summary,
// the summary is stale regardless of generatedAt age.
val newestDailyMtime = Option(datasetsDir.listFiles())
  .getOrElse(Array.empty[File])
  .flatMap { specDir =>
    val dailyLog = new File(specDir, "trends-daily.jsonl")
    if (dailyLog.exists()) Some(dailyLog.lastModified()) else None
  }
  .maxOption
  .getOrElse(0L)

if (newestDailyMtime > summary.generatedAt.getMillis) {
  logger.info("Trends summary is stale (per-dataset daily logs newer than summary)")
  None
} else {
  Some(summary)
}
```

Replace the existing `Some(summary)` with the block above.

- [ ] **Step 2: Update caller**

In `AppController.scala:265`, change:

```scala
TrendTrackingService.readTrendsSummary(orgContext.trendsSummaryFile) match {
```

to:

```scala
TrendTrackingService.readTrendsSummary(orgContext.trendsSummaryFile, orgContext.datasetsDir) match {
```

- [ ] **Step 3: Compile + tests**

Run: `unset JDK_JAVA_OPTIONS; make compile 2>&1 | tail -5 && sbt test 2>&1 | tail -10`

- [ ] **Step 4: Human diff review**

Reference skill: diff-review-before-task-commit

- [ ] **Step 5: Commit**

```bash
git add app/services/TrendTrackingService.scala app/controllers/AppController.scala
git commit -m "$(cat <<'EOF'
fix(trends): invalidate summary cache when any daily log is newer

readTrendsSummary now compares summary.generatedAt against the newest
per-dataset trends-daily.jsonl mtime. If any daily log is newer, the
cache is considered stale so the fallback rebuild path runs.

Addresses review finding #10 (MEDIUM).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 10: Consistent totalDatasets between cached and live paths (MEDIUM #11)

Cached path: `buildOrganizationTrends` uses `summaries.size`. Live fallback path: `getOrganizationTrends` uses `datasets.size`. If some datasets have no trends-daily.jsonl, the two paths disagree. Pick one: `summaries.size` is the right one — it's the count of datasets with actual data.

**Files:**
- Modify: `app/controllers/AppController.scala:261-311`

- [ ] **Step 1: Inspect both branches**

Run: `grep -n "totalDatasets" app/controllers/AppController.scala`

- [ ] **Step 2: Align fallback path**

In the fallback branch (`AppController.scala:272-311`), replace the `OrganizationTrends(... totalDatasets = datasets.size ...)` construction so it uses `summaries.size` (just like `buildOrganizationTrends`).

- [ ] **Step 3: Compile**

Run: `unset JDK_JAVA_OPTIONS; make compile 2>&1 | tail -5`

- [ ] **Step 4: Human diff review**

Reference skill: diff-review-before-task-commit

- [ ] **Step 5: Commit**

```bash
git add app/controllers/AppController.scala
git commit -m "$(cat <<'EOF'
fix(trends): totalDatasets consistent between cached and live paths

Previously totalDatasets used summaries.size in the cached path and
datasets.size in the live fallback. The number flickered on refresh
when some datasets had no trends-daily.jsonl. Standardize on
summaries.size in both branches.

Addresses review finding #11 (MEDIUM).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 11: Align synthesized chart timestamps with recentEvents (MEDIUM #13)

`getDatasetTrendsFromDaily` synthesizes history timestamps as `DateTime.parse(ds.date + "T23:59:59.000Z")` (UTC). `recentEvents` come from the event log with whatever zone the original timestamps were taken in. After Task 6 both sides are UTC, but the suffix `.000Z` bypasses the explicit zone conversion; make it explicit for clarity.

**Files:**
- Modify: `app/services/TrendTrackingService.scala:702-712`

- [ ] **Step 1: Replace parse**

Replace the `timestamp = DateTime.parse(ds.date + "T23:59:59.000Z")` line with:

```scala
timestamp = new DateTime(
  org.joda.time.LocalDate.parse(ds.date)
    .toDateTime(org.joda.time.LocalTime.parse("23:59:59.999"), org.joda.time.DateTimeZone.UTC)
)
```

- [ ] **Step 2: Compile**

Run: `unset JDK_JAVA_OPTIONS; make compile 2>&1 | tail -5`

- [ ] **Step 3: Human diff review**

Reference skill: diff-review-before-task-commit

- [ ] **Step 4: Commit**

```bash
git add app/services/TrendTrackingService.scala
git commit -m "$(cat <<'EOF'
fix(trends): explicit UTC end-of-day for synthesized chart timestamps

Using DateTime.parse with a Z suffix produced the right instant but
hid the timezone intent. Now construct the timestamp from LocalDate
+ 23:59:59.999 UTC explicitly so refactors can't accidentally change
the zone.

Addresses review finding #13 (MEDIUM).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 12: Efficient `getLastSnapshot` + "initializing" state (MEDIUM #12, LOW #14)

`getLastSnapshot` reads the whole file. For long-running datasets this gets slow and can time out captures. Use `RandomAccessFile` to read just the final line.

Also: when dailyLog is empty but an eventLog exists, surface a flag so the UI can render "initializing" rather than blank cells.

**Files:**
- Modify: `app/services/TrendTrackingService.scala:485-496, 658-679`
- Modify: `app/controllers/AppController.scala` and `app/assets/javascripts/trends/trends-controllers.js` — render initializing state

- [ ] **Step 1: Replace `getLastSnapshot` with tail-read**

```scala
def getLastSnapshot(trendsLog: File): Option[TrendSnapshot] = {
  if (!trendsLog.exists() || trendsLog.length() == 0) return None

  Try {
    val raf = new java.io.RandomAccessFile(trendsLog, "r")
    try {
      val fileLength = raf.length()
      // Walk back until we find a newline preceding the final line, or hit BOF.
      var pos = fileLength - 1
      // Skip trailing newlines.
      while (pos >= 0 && { raf.seek(pos); val b = raf.readByte(); b == '\n' || b == '\r' }) {
        pos -= 1
      }
      val endOfLastLine = pos
      while (pos >= 0 && { raf.seek(pos); val b = raf.readByte(); b != '\n' && b != '\r' }) {
        pos -= 1
      }
      val startOfLastLine = pos + 1
      val length = (endOfLastLine - startOfLastLine + 1).toInt
      if (length <= 0) None
      else {
        val buf = new Array[Byte](length)
        raf.seek(startOfLastLine)
        raf.readFully(buf)
        val line = new String(buf, "UTF-8")
        Try(Json.parse(line).as[TrendSnapshot]).toOption
      }
    } finally raf.close()
  }.toOption.flatten
}
```

- [ ] **Step 2: Test correctness against a large file**

```scala
it should "return the last snapshot without reading the whole file" in withTempDir { tmpDir =>
  val trendsLog = new File(tmpDir, "trends.jsonl")
  (1 to 5000).foreach { i =>
    TrendTrackingService.captureEventSnapshot(
      trendsLog, "save",
      sourceRecords = i, acquiredRecords = i, deletedRecords = 0,
      validRecords = i, invalidRecords = 0, indexedRecords = i
    )
  }
  val last = TrendTrackingService.getLastSnapshot(trendsLog).get
  last.sourceRecords shouldBe 5000
}
```

- [ ] **Step 3: Add `initializing` flag to DatasetTrendSummary**

Add an optional field `initializing: Option[Boolean] = None` to `DatasetTrendSummary`. Update Play JSON format to tolerate missing/false.

In `getDatasetTrendSummaryFromDaily` (line 658), when falling back to `getDatasetTrendSummary(trendsLog, spec)`, map the result to set `initializing = Some(true)`.

- [ ] **Step 4: Update UI**

In `app/assets/javascripts/trends/trends-controllers.js`, when rendering trend cells, if `dataset.initializing === true`, show a small badge "Initializing — first aggregation pending" instead of zero deltas.

- [ ] **Step 5: Compile + tests**

Run: `unset JDK_JAVA_OPTIONS; make compile 2>&1 | tail -5 && sbt "testOnly services.TrendTrackingServiceSpec" 2>&1 | tail -10`

- [ ] **Step 6: Human diff review**

Reference skill: diff-review-before-task-commit

- [ ] **Step 7: Commit**

```bash
git add app/services/TrendTrackingService.scala test/services/TrendTrackingServiceSpec.scala app/assets/javascripts/trends/trends-controllers.js
git commit -m "$(cat <<'EOF'
perf(trends): O(1) getLastSnapshot + initializing state for UI

getLastSnapshot now tail-reads the final line via RandomAccessFile
instead of streaming the whole JSONL file. On datasets with years
of history, previous captures could time out during SAVE and be
silently dropped.

DatasetTrendSummary gains an optional initializing flag set when
the fallback path is used (no daily summary yet); the UI renders
'Initializing' instead of misleading zero deltas.

Addresses review findings #12 and #14.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 13: Classification by `valid` delta + lambda-return cleanup (LOW #15, #16, #17)

- `buildOrganizationTrends.stable` classification ignores `valid` delta. A dataset with unchanged source/indexed but changing valid records lands in "stable".
- 24h chart in `trends-controllers.js` only renders when `recentEvents.length > 0`.
- `captureEventSnapshot` uses `return` inside `foreach` (NonLocalReturnControl).

**Files:**
- Modify: `app/services/TrendTrackingService.scala:445-478, 498-535`
- Modify: `app/assets/javascripts/trends/trends-controllers.js:318`

- [ ] **Step 1: Include `valid` in growing/shrinking/stable classification**

In `buildOrganizationTrends` (line 456-466):

```scala
val growing = summaries.filter(s =>
  s.delta24h.source > 0 || s.delta24h.indexed > 0 || s.delta24h.valid > 0
).sortBy(s => -(s.delta24h.source + s.delta24h.indexed + s.delta24h.valid))

val shrinking = summaries.filter(s =>
  s.delta24h.source < 0 || s.delta24h.indexed < 0 || s.delta24h.valid < 0
).sortBy(s => s.delta24h.source + s.delta24h.indexed + s.delta24h.valid)

val stable = summaries.filter(s =>
  s.delta24h.source == 0 && s.delta24h.indexed == 0 && s.delta24h.valid == 0
).sortBy(_.spec)
```

- [ ] **Step 2: Replace `foreach { return }` pattern**

Rewrite `captureEventSnapshot` guards to avoid `return` inside lambdas:

```scala
def captureEventSnapshot(
  trendsLog: File,
  event: String,
  sourceRecords: Int,
  acquiredRecords: Int,
  deletedRecords: Int,
  validRecords: Int,
  invalidRecords: Int,
  indexedRecords: Int
): Unit = {
  val lastSnapshot = getLastSnapshot(trendsLog)

  val shouldSkip = lastSnapshot match {
    case None =>
      sourceRecords == 0
    case Some(prev) =>
      prev.sourceRecords == sourceRecords &&
        prev.acquiredRecords == acquiredRecords &&
        prev.deletedRecords == deletedRecords &&
        prev.validRecords == validRecords &&
        prev.invalidRecords == invalidRecords &&
        prev.indexedRecords == indexedRecords
  }

  if (shouldSkip) {
    logger.debug(s"Skipping event snapshot for $event: no change / pre-harvest state")
  } else {
    lastSnapshot.foreach { prev =>
      if (prev.sourceRecords > 0 && sourceRecords < prev.sourceRecords * 0.5) {
        logger.warn(s"Large drop in sourceRecords: ${prev.sourceRecords} -> $sourceRecords (recording anyway)")
      }
    }
    val snapshot = TrendSnapshot(
      timestamp = DateTime.now(org.joda.time.DateTimeZone.UTC),
      snapshotType = event,
      sourceRecords = sourceRecords,
      acquiredRecords = acquiredRecords,
      deletedRecords = deletedRecords,
      validRecords = validRecords,
      invalidRecords = invalidRecords,
      indexedRecords = indexedRecords
    )
    appendSnapshot(trendsLog, snapshot)
    logger.debug(s"Captured event snapshot ($event): source=$sourceRecords, valid=$validRecords, indexed=$indexedRecords")
  }
}
```

- [ ] **Step 3: Fix 24h chart rendering threshold**

In `app/assets/javascripts/trends/trends-controllers.js` (around line 318 — locate the `recentEvents.length > 0` condition):

```javascript
// Old: render chart only when recentEvents has data
// New: render chart whenever we have any time series — events OR daily summaries.
var hasEvents = $scope.trends.recentEvents && $scope.trends.recentEvents.length > 0;
var hasDaily = $scope.trends.dailySummaries && $scope.trends.dailySummaries.length > 0;
if (hasEvents || hasDaily) {
  // build chart
}
// When rendering the "24h" view, fall back message when only daily data is available:
$scope.chart24hLabel = hasEvents ? "24h events" : "Daily summaries (event log sparse)";
```

- [ ] **Step 4: Compile + tests**

Run: `unset JDK_JAVA_OPTIONS; make compile 2>&1 | tail -5 && sbt "testOnly services.TrendTrackingServiceSpec" 2>&1 | tail -10`

- [ ] **Step 5: Human diff review**

Reference skill: diff-review-before-task-commit

- [ ] **Step 6: Commit**

```bash
git add app/services/TrendTrackingService.scala app/assets/javascripts/trends/trends-controllers.js
git commit -m "$(cat <<'EOF'
fix(trends): include valid delta in classification; clean return pattern

- buildOrganizationTrends now classifies growing/shrinking based on
  valid delta too, so datasets with processed-valid movement but
  unchanged source/indexed no longer hide in 'stable'.
- captureEventSnapshot replaced foreach { return } (NonLocalReturnControl)
  with an explicit early-skip branch that future refactors can't
  accidentally swallow via Throwable catches.
- 24h chart now falls back to dailySummaries when recentEvents is
  empty and shows a label indicating so.

Addresses review findings #15, #16, #17 (LOW).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Final Verification

- [ ] **Run full test suite**

Run: `unset JDK_JAVA_OPTIONS; sbt test 2>&1 | tail -15`
Expected: all tests pass.

- [ ] **Sanity-check with dev server**

Run: `unset JDK_JAVA_OPTIONS; make run`
Open `http://localhost:9000/narthex/#/trends` and verify:
  - At least some datasets appear in growing/shrinking (not all in stable).
  - Deltas match the underlying trends-daily.jsonl files.
  - After one SAVE event on a dataset, the UI's indexed count does NOT instantly jump to valid (it should lag until aggregator runs).

- [ ] **Deploy + bump version**

```bash
make set-version V=0.8.7.19
git push && git push --tags
make deploy SSH_HOST=root@ingestion.brabantcloud.hubs.delving.io ORG_ID=brabantcloud
```
