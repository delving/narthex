# Trends Capture & Aggregation Redesign — Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Fix wrong trend counts and erratic deltas by splitting trends into event snapshots (only on change) and daily aggregation summaries.

**Architecture:** Two JSONL files per dataset: `trends.jsonl` (event log, append-only, change-gated) and `trends-daily.jsonl` (nightly aggregation with end-of-day counts + deltas). Nightly job at 00:30 replaces midnight snapshot. Delta calculation uses daily summaries instead of raw snapshots.

**Tech Stack:** Scala 2.13, Play Framework 2.8.20, Joda-Time, Play JSON, AngularJS 1.3

**Design doc:** `docs/plans/2026-02-19-trends-redesign.md`

---

## Task 1: Add New Data Models to TrendTrackingService

**Files:**
- Modify: `app/services/TrendTrackingService.scala:26-143`
- Test: `test/services/TrendTrackingServiceSpec.scala` (create)

### Step 1: Write the failing test

Create `test/services/TrendTrackingServiceSpec.scala`:

```scala
package services

import org.scalatest.flatspec._
import org.scalatest.matchers._
import play.api.libs.json._

class TrendTrackingServiceSpec extends AnyFlatSpec with should.Matchers {

  "DailySummary" should "serialize and deserialize to JSON" in {
    val summary = DailySummary(
      date = "2026-02-19",
      endOfDay = EndOfDayCounts(
        sourceRecords = 90427,
        acquiredRecords = 90427,
        deletedRecords = 0,
        validRecords = 90000,
        invalidRecords = 427,
        indexedRecords = 90000
      ),
      delta = TrendDelta(source = 50, valid = 48, indexed = 48),
      events = 3
    )

    val json = Json.toJson(summary)
    val parsed = json.as[DailySummary]
    parsed.date shouldBe "2026-02-19"
    parsed.endOfDay.sourceRecords shouldBe 90427
    parsed.delta.source shouldBe 50
    parsed.events shouldBe 3
  }
}
```

### Step 2: Run test to verify it fails

Run: `make compile && sbt "testOnly services.TrendTrackingServiceSpec"`
Expected: FAIL — `DailySummary` and `EndOfDayCounts` not found

### Step 3: Add new data models

Add to `app/services/TrendTrackingService.scala` after the `TrendDelta` companion object (after line 66):

```scala
/**
 * End-of-day record counts for daily summary.
 */
case class EndOfDayCounts(
  sourceRecords: Int,
  acquiredRecords: Int,
  deletedRecords: Int,
  validRecords: Int,
  invalidRecords: Int,
  indexedRecords: Int
)

object EndOfDayCounts {
  implicit val format: Format[EndOfDayCounts] = Json.format[EndOfDayCounts]

  def fromSnapshot(s: TrendSnapshot): EndOfDayCounts = EndOfDayCounts(
    sourceRecords = s.sourceRecords,
    acquiredRecords = s.acquiredRecords,
    deletedRecords = s.deletedRecords,
    validRecords = s.validRecords,
    invalidRecords = s.invalidRecords,
    indexedRecords = s.indexedRecords
  )
}

/**
 * Daily summary: one line per day in trends-daily.jsonl.
 * Contains end-of-day counts and delta vs previous day.
 */
case class DailySummary(
  date: String,          // "2026-02-19"
  endOfDay: EndOfDayCounts,
  delta: TrendDelta,
  events: Int            // Number of event snapshots that day
)

object DailySummary {
  implicit val format: Format[DailySummary] = Json.format[DailySummary]
}
```

### Step 4: Run test to verify it passes

Run: `make compile && sbt "testOnly services.TrendTrackingServiceSpec"`
Expected: PASS

### Step 5: Commit

```bash
git add test/services/TrendTrackingServiceSpec.scala app/services/TrendTrackingService.scala
git commit -m "feat(trends): add DailySummary and EndOfDayCounts data models"
```

---

## Task 2: Add Change-Gated Event Capture

**Files:**
- Modify: `app/services/TrendTrackingService.scala:168-190` (captureSnapshot)
- Test: `test/services/TrendTrackingServiceSpec.scala`

### Step 1: Write the failing test

Add to `TrendTrackingServiceSpec.scala`:

```scala
import java.io.File
import java.nio.file.Files

"captureEventSnapshot" should "skip writing when counts are unchanged" in {
  val tmpDir = Files.createTempDirectory("trends-test").toFile
  val trendsLog = new File(tmpDir, "trends.jsonl")

  // First capture should write
  TrendTrackingService.captureEventSnapshot(
    trendsLog, "harvest",
    sourceRecords = 100, acquiredRecords = 100, deletedRecords = 0,
    validRecords = 90, invalidRecords = 10, indexedRecords = 90
  )
  TrendTrackingService.readSnapshots(trendsLog).size shouldBe 1

  // Same counts again — should NOT write
  TrendTrackingService.captureEventSnapshot(
    trendsLog, "harvest",
    sourceRecords = 100, acquiredRecords = 100, deletedRecords = 0,
    validRecords = 90, invalidRecords = 10, indexedRecords = 90
  )
  TrendTrackingService.readSnapshots(trendsLog).size shouldBe 1

  // Different counts — should write
  TrendTrackingService.captureEventSnapshot(
    trendsLog, "harvest",
    sourceRecords = 110, acquiredRecords = 110, deletedRecords = 0,
    validRecords = 100, invalidRecords = 10, indexedRecords = 100
  )
  TrendTrackingService.readSnapshots(trendsLog).size shouldBe 2

  tmpDir.listFiles().foreach(_.delete())
  tmpDir.delete()
}

it should "skip writing when sourceRecords is 0" in {
  val tmpDir = Files.createTempDirectory("trends-test").toFile
  val trendsLog = new File(tmpDir, "trends.jsonl")

  TrendTrackingService.captureEventSnapshot(
    trendsLog, "harvest",
    sourceRecords = 0, acquiredRecords = 0, deletedRecords = 0,
    validRecords = 0, invalidRecords = 0, indexedRecords = 0
  )
  TrendTrackingService.readSnapshots(trendsLog).size shouldBe 0

  tmpDir.listFiles().foreach(_.delete())
  tmpDir.delete()
}

it should "skip writing when sourceRecords drops more than 50%" in {
  val tmpDir = Files.createTempDirectory("trends-test").toFile
  val trendsLog = new File(tmpDir, "trends.jsonl")

  // Initial capture with 100 records
  TrendTrackingService.captureEventSnapshot(
    trendsLog, "harvest",
    sourceRecords = 100, acquiredRecords = 100, deletedRecords = 0,
    validRecords = 90, invalidRecords = 10, indexedRecords = 90
  )

  // Drop to 40 (60% drop) — should be skipped
  TrendTrackingService.captureEventSnapshot(
    trendsLog, "harvest",
    sourceRecords = 40, acquiredRecords = 40, deletedRecords = 0,
    validRecords = 30, invalidRecords = 10, indexedRecords = 30
  )
  TrendTrackingService.readSnapshots(trendsLog).size shouldBe 1

  // Drop to 60 (40% drop) — should be accepted
  TrendTrackingService.captureEventSnapshot(
    trendsLog, "harvest",
    sourceRecords = 60, acquiredRecords = 60, deletedRecords = 0,
    validRecords = 50, invalidRecords = 10, indexedRecords = 50
  )
  TrendTrackingService.readSnapshots(trendsLog).size shouldBe 2

  tmpDir.listFiles().foreach(_.delete())
  tmpDir.delete()
}
```

### Step 2: Run test to verify it fails

Run: `make compile && sbt "testOnly services.TrendTrackingServiceSpec"`
Expected: FAIL — `captureEventSnapshot` not found

### Step 3: Implement captureEventSnapshot

Add new method to `TrendTrackingService` object (after the existing `captureSnapshot` method around line 190):

```scala
/**
 * Capture an event snapshot with guards:
 * - Skip if sourceRecords == 0 (pre-harvest state)
 * - Skip if counts unchanged vs last entry
 * - Skip if sourceRecords dropped > 50% (partial harvest)
 *
 * @param event "harvest", "save", or "manual"
 */
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
  // Guard: skip zero-count snapshots
  if (sourceRecords == 0) {
    logger.debug("Skipping event snapshot: sourceRecords is 0")
    return
  }

  // Guard: skip if counts unchanged
  val lastSnapshot = getLastSnapshot(trendsLog)
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

    // Guard: skip if sourceRecords dropped > 50% (likely partial harvest)
    if (prev.sourceRecords > 0 && sourceRecords < prev.sourceRecords * 0.5) {
      logger.warn(s"Skipping event snapshot: sourceRecords dropped from ${prev.sourceRecords} to $sourceRecords (>50% drop, likely partial harvest)")
      return
    }
  }

  val snapshot = TrendSnapshot(
    timestamp = DateTime.now(),
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
```

Also add a `getLastSnapshot` helper that reads only the last line (efficient for append-only files):

```scala
/**
 * Read only the last snapshot from a trends file (efficient: reads last line only).
 */
def getLastSnapshot(trendsLog: File): Option[TrendSnapshot] = {
  if (!trendsLog.exists() || trendsLog.length() == 0) {
    return None
  }

  Using(Source.fromFile(trendsLog)) { source =>
    source.getLines()
      .filter(_.nonEmpty)
      .foldLeft(Option.empty[String])((_, line) => Some(line))
      .flatMap(line => Try(Json.parse(line).as[TrendSnapshot]).toOption)
  }.getOrElse(None)
}
```

### Step 4: Run test to verify it passes

Run: `make compile && sbt "testOnly services.TrendTrackingServiceSpec"`
Expected: PASS

### Step 5: Commit

```bash
git add app/services/TrendTrackingService.scala test/services/TrendTrackingServiceSpec.scala
git commit -m "feat(trends): add change-gated captureEventSnapshot with guards"
```

---

## Task 3: Add Daily Summary Read/Write Methods

**Files:**
- Modify: `app/services/TrendTrackingService.scala`
- Test: `test/services/TrendTrackingServiceSpec.scala`

### Step 1: Write the failing test

Add to `TrendTrackingServiceSpec.scala`:

```scala
"readDailySummaries" should "read daily summary lines from file" in {
  val tmpDir = Files.createTempDirectory("trends-test").toFile
  val dailyLog = new File(tmpDir, "trends-daily.jsonl")

  val summary1 = DailySummary(
    date = "2026-02-18",
    endOfDay = EndOfDayCounts(100, 100, 0, 90, 10, 90),
    delta = TrendDelta(0, 0, 0),
    events = 1
  )
  val summary2 = DailySummary(
    date = "2026-02-19",
    endOfDay = EndOfDayCounts(150, 150, 0, 140, 10, 140),
    delta = TrendDelta(50, 50, 50),
    events = 2
  )

  TrendTrackingService.appendDailySummary(dailyLog, summary1)
  TrendTrackingService.appendDailySummary(dailyLog, summary2)

  val summaries = TrendTrackingService.readDailySummaries(dailyLog)
  summaries.size shouldBe 2
  summaries.head.date shouldBe "2026-02-18"
  summaries.last.delta.source shouldBe 50

  dailyLog.delete()
  tmpDir.delete()
}
```

### Step 2: Run test to verify it fails

Run: `make compile && sbt "testOnly services.TrendTrackingServiceSpec"`
Expected: FAIL — `appendDailySummary` and `readDailySummaries` not found

### Step 3: Implement daily summary read/write

Add to `TrendTrackingService` object:

```scala
/**
 * Append a daily summary to the trends-daily.jsonl file.
 */
def appendDailySummary(dailyLog: File, summary: DailySummary): Unit = {
  val line = Json.stringify(Json.toJson(summary)) + "\n"
  val writer = appender(dailyLog)
  try {
    writer.write(line)
    writer.flush()
  } finally {
    writer.close()
  }
}

/**
 * Read all daily summaries from trends-daily.jsonl.
 */
def readDailySummaries(dailyLog: File): List[DailySummary] = {
  if (!dailyLog.exists()) {
    return List.empty
  }

  Using(Source.fromFile(dailyLog)) { source =>
    source.getLines()
      .filter(_.nonEmpty)
      .flatMap { line =>
        Try(Json.parse(line).as[DailySummary]).toOption
      }
      .toList
  }.getOrElse(List.empty)
}

/**
 * Get the last daily summary (most recent date).
 */
def getLastDailySummary(dailyLog: File): Option[DailySummary] = {
  if (!dailyLog.exists() || dailyLog.length() == 0) {
    return None
  }

  Using(Source.fromFile(dailyLog)) { source =>
    source.getLines()
      .filter(_.nonEmpty)
      .foldLeft(Option.empty[String])((_, line) => Some(line))
      .flatMap(line => Try(Json.parse(line).as[DailySummary]).toOption)
  }.getOrElse(None)
}
```

### Step 4: Run test to verify it passes

Run: `make compile && sbt "testOnly services.TrendTrackingServiceSpec"`
Expected: PASS

### Step 5: Commit

```bash
git add app/services/TrendTrackingService.scala test/services/TrendTrackingServiceSpec.scala
git commit -m "feat(trends): add daily summary read/write methods"
```

---

## Task 4: Add Daily Aggregation Logic

**Files:**
- Modify: `app/services/TrendTrackingService.scala`
- Test: `test/services/TrendTrackingServiceSpec.scala`

### Step 1: Write the failing test

Add to `TrendTrackingServiceSpec.scala`:

```scala
"aggregateDay" should "compute daily summary from event snapshots" in {
  val tmpDir = Files.createTempDirectory("trends-test").toFile
  val trendsLog = new File(tmpDir, "trends.jsonl")
  val dailyLog = new File(tmpDir, "trends-daily.jsonl")

  // Simulate two event snapshots during the day
  TrendTrackingService.captureEventSnapshot(
    trendsLog, "harvest",
    sourceRecords = 100, acquiredRecords = 100, deletedRecords = 0,
    validRecords = 90, invalidRecords = 10, indexedRecords = 90
  )
  TrendTrackingService.captureEventSnapshot(
    trendsLog, "save",
    sourceRecords = 110, acquiredRecords = 110, deletedRecords = 0,
    validRecords = 100, invalidRecords = 10, indexedRecords = 100
  )

  // Aggregate: no previous day, so delta should be zero
  TrendTrackingService.aggregateDay(trendsLog, dailyLog, "2026-02-19")

  val summaries = TrendTrackingService.readDailySummaries(dailyLog)
  summaries.size shouldBe 1
  summaries.head.date shouldBe "2026-02-19"
  summaries.head.endOfDay.sourceRecords shouldBe 110
  summaries.head.endOfDay.indexedRecords shouldBe 100
  summaries.head.delta shouldBe TrendDelta(0, 0, 0) // No previous day
  summaries.head.events shouldBe 2

  tmpDir.listFiles().foreach(_.delete())
  tmpDir.delete()
}

it should "compute delta vs previous day" in {
  val tmpDir = Files.createTempDirectory("trends-test").toFile
  val trendsLog = new File(tmpDir, "trends.jsonl")
  val dailyLog = new File(tmpDir, "trends-daily.jsonl")

  // Write a previous day summary
  TrendTrackingService.appendDailySummary(dailyLog, DailySummary(
    date = "2026-02-18",
    endOfDay = EndOfDayCounts(100, 100, 0, 90, 10, 90),
    delta = TrendDelta(0, 0, 0),
    events = 1
  ))

  // Add today's event snapshots
  TrendTrackingService.captureEventSnapshot(
    trendsLog, "harvest",
    sourceRecords = 150, acquiredRecords = 150, deletedRecords = 0,
    validRecords = 140, invalidRecords = 10, indexedRecords = 140
  )

  TrendTrackingService.aggregateDay(trendsLog, dailyLog, "2026-02-19")

  val summaries = TrendTrackingService.readDailySummaries(dailyLog)
  summaries.size shouldBe 2
  val today = summaries.last
  today.delta.source shouldBe 50   // 150 - 100
  today.delta.valid shouldBe 50    // 140 - 90
  today.delta.indexed shouldBe 50  // 140 - 90

  tmpDir.listFiles().foreach(_.delete())
  tmpDir.delete()
}

it should "use last event snapshot as end-of-day when no events today" in {
  val tmpDir = Files.createTempDirectory("trends-test").toFile
  val trendsLog = new File(tmpDir, "trends.jsonl")
  val dailyLog = new File(tmpDir, "trends-daily.jsonl")

  // Previous day
  TrendTrackingService.appendDailySummary(dailyLog, DailySummary(
    date = "2026-02-18",
    endOfDay = EndOfDayCounts(100, 100, 0, 90, 10, 90),
    delta = TrendDelta(0, 0, 0),
    events = 1
  ))

  // No new events, but trends.jsonl has the last known snapshot
  TrendTrackingService.captureEventSnapshot(
    trendsLog, "harvest",
    sourceRecords = 100, acquiredRecords = 100, deletedRecords = 0,
    validRecords = 90, invalidRecords = 10, indexedRecords = 90
  )

  TrendTrackingService.aggregateDay(trendsLog, dailyLog, "2026-02-19")

  val summaries = TrendTrackingService.readDailySummaries(dailyLog)
  summaries.size shouldBe 2
  val today = summaries.last
  today.delta shouldBe TrendDelta(0, 0, 0) // No change
  today.events shouldBe 0 // No events today (the snapshot was written earlier in this test but we check event count for the date)

  tmpDir.listFiles().foreach(_.delete())
  tmpDir.delete()
}
```

### Step 2: Run test to verify it fails

Run: `make compile && sbt "testOnly services.TrendTrackingServiceSpec"`
Expected: FAIL — `aggregateDay` not found

### Step 3: Implement aggregateDay

Add to `TrendTrackingService` object:

```scala
/**
 * Aggregate a day's event snapshots into a daily summary.
 * Uses the last event snapshot as end-of-day counts.
 * Computes delta vs previous day's summary.
 *
 * @param trendsLog The trends.jsonl file (event snapshots)
 * @param dailyLog The trends-daily.jsonl file (daily summaries)
 * @param date The date to aggregate (format: "YYYY-MM-DD")
 */
def aggregateDay(trendsLog: File, dailyLog: File, date: String): Unit = {
  val lastSnapshot = getLastSnapshot(trendsLog)
  if (lastSnapshot.isEmpty) {
    logger.debug(s"No snapshots to aggregate for $date")
    return
  }

  val current = lastSnapshot.get
  val endOfDay = EndOfDayCounts.fromSnapshot(current)

  // Count events for this specific date
  val datePrefix = date  // "2026-02-19"
  val todayEvents = readSnapshots(trendsLog).count { s =>
    timeToString(s.timestamp).startsWith(datePrefix)
  }

  // Get previous day's summary for delta calculation
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
  logger.info(s"Aggregated daily summary for $date: source=${endOfDay.sourceRecords}, delta=${delta.source}")
}
```

### Step 4: Run test to verify it passes

Run: `make compile && sbt "testOnly services.TrendTrackingServiceSpec"`
Expected: PASS

### Step 5: Commit

```bash
git add app/services/TrendTrackingService.scala test/services/TrendTrackingServiceSpec.scala
git commit -m "feat(trends): add daily aggregation logic"
```

---

## Task 5: Fix Delta Calculation to Use Daily Summaries

**Files:**
- Modify: `app/services/TrendTrackingService.scala:240-295`
- Test: `test/services/TrendTrackingServiceSpec.scala`

### Step 1: Write the failing test

Add to `TrendTrackingServiceSpec.scala`:

```scala
"calculateDeltaFromDailySummaries" should "calculate correct delta for N-day window" in {
  val summaries = List(
    DailySummary("2026-02-12", EndOfDayCounts(100, 100, 0, 90, 10, 90), TrendDelta(0, 0, 0), 1),
    DailySummary("2026-02-13", EndOfDayCounts(120, 120, 0, 110, 10, 110), TrendDelta(20, 20, 20), 2),
    DailySummary("2026-02-18", EndOfDayCounts(150, 150, 0, 140, 10, 140), TrendDelta(5, 5, 5), 1),
    DailySummary("2026-02-19", EndOfDayCounts(160, 160, 0, 148, 12, 148), TrendDelta(10, 8, 8), 1)
  )

  // 1 day: compare to previous day's summary
  val delta1d = TrendTrackingService.calculateDeltaFromDailySummaries(summaries, 1)
  delta1d.source shouldBe 10  // 160 - 150
  delta1d.indexed shouldBe 8  // 148 - 140

  // 7 days: compare to 7 days ago (2026-02-12 is closest before window start)
  val delta7d = TrendTrackingService.calculateDeltaFromDailySummaries(summaries, 7)
  delta7d.source shouldBe 60  // 160 - 100
  delta7d.indexed shouldBe 58  // 148 - 90
}

it should "return zero when not enough data" in {
  val summaries = List(
    DailySummary("2026-02-19", EndOfDayCounts(160, 160, 0, 148, 12, 148), TrendDelta(0, 0, 0), 1)
  )

  val delta = TrendTrackingService.calculateDeltaFromDailySummaries(summaries, 7)
  delta shouldBe TrendDelta.zero
}
```

### Step 2: Run test to verify it fails

Run: `make compile && sbt "testOnly services.TrendTrackingServiceSpec"`
Expected: FAIL — `calculateDeltaFromDailySummaries` not found

### Step 3: Implement new delta calculation

Add to `TrendTrackingService` object:

```scala
/**
 * Calculate delta for a time window using daily summaries.
 * Finds the summary closest to N days ago and computes:
 *   current (last summary) - baseline (N days ago).
 *
 * @param summaries All daily summaries, assumed sorted by date ascending
 * @param daysAgo Number of days to look back
 */
def calculateDeltaFromDailySummaries(summaries: List[DailySummary], daysAgo: Int): TrendDelta = {
  if (summaries.size < 2) return TrendDelta.zero

  val current = summaries.last
  val cutoffDate = org.joda.time.LocalDate.parse(current.date).minusDays(daysAgo).toString("yyyy-MM-dd")

  // Find the closest summary on or before the cutoff date
  val baseline = summaries.reverse.find(_.date <= cutoffDate)

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

### Step 4: Run test to verify it passes

Run: `make compile && sbt "testOnly services.TrendTrackingServiceSpec"`
Expected: PASS

### Step 5: Commit

```bash
git add app/services/TrendTrackingService.scala test/services/TrendTrackingServiceSpec.scala
git commit -m "feat(trends): add daily-summary-based delta calculation"
```

---

## Task 6: Update getDatasetTrendSummary and getDatasetTrends to Use Daily Summaries

**Files:**
- Modify: `app/services/TrendTrackingService.scala:262-295`
- Test: `test/services/TrendTrackingServiceSpec.scala`

### Step 1: Write the failing test

Add to `TrendTrackingServiceSpec.scala`:

```scala
"getDatasetTrendSummaryFromDaily" should "compute summary from daily file" in {
  val tmpDir = Files.createTempDirectory("trends-test").toFile
  val dailyLog = new File(tmpDir, "trends-daily.jsonl")
  val trendsLog = new File(tmpDir, "trends.jsonl")

  // Write 3 days of daily summaries
  TrendTrackingService.appendDailySummary(dailyLog, DailySummary(
    "2026-02-17", EndOfDayCounts(100, 100, 0, 90, 10, 90), TrendDelta(0, 0, 0), 1))
  TrendTrackingService.appendDailySummary(dailyLog, DailySummary(
    "2026-02-18", EndOfDayCounts(120, 120, 0, 110, 10, 110), TrendDelta(20, 20, 20), 2))
  TrendTrackingService.appendDailySummary(dailyLog, DailySummary(
    "2026-02-19", EndOfDayCounts(150, 150, 0, 140, 10, 140), TrendDelta(30, 30, 30), 1))

  val summary = TrendTrackingService.getDatasetTrendSummaryFromDaily(dailyLog, trendsLog, "test-spec")
  summary shouldBe defined
  summary.get.currentSource shouldBe 150
  summary.get.currentIndexed shouldBe 140
  // 24h delta = last day's delta
  summary.get.delta24h.source shouldBe 30

  tmpDir.listFiles().foreach(_.delete())
  tmpDir.delete()
}
```

### Step 2: Run test to verify it fails

Run: `make compile && sbt "testOnly services.TrendTrackingServiceSpec"`
Expected: FAIL — `getDatasetTrendSummaryFromDaily` not found

### Step 3: Implement new summary methods

Add to `TrendTrackingService` object:

```scala
/**
 * Get trend summary using daily summaries (new method).
 * Falls back to legacy snapshots if no daily file exists.
 */
def getDatasetTrendSummaryFromDaily(
  dailyLog: File,
  trendsLog: File,
  spec: String
): Option[DatasetTrendSummary] = {
  val dailySummaries = readDailySummaries(dailyLog)

  if (dailySummaries.nonEmpty) {
    val current = dailySummaries.last
    Some(DatasetTrendSummary(
      spec = spec,
      currentSource = current.endOfDay.sourceRecords,
      currentValid = current.endOfDay.validRecords,
      currentIndexed = current.endOfDay.indexedRecords,
      delta24h = calculateDeltaFromDailySummaries(dailySummaries, 1),
      delta7d = calculateDeltaFromDailySummaries(dailySummaries, 7),
      delta30d = calculateDeltaFromDailySummaries(dailySummaries, 30)
    ))
  } else {
    // Fallback to legacy snapshot-based calculation
    getDatasetTrendSummary(trendsLog, spec)
  }
}

/**
 * Get full dataset trends using daily summaries (new method).
 */
def getDatasetTrendsFromDaily(
  dailyLog: File,
  trendsLog: File,
  spec: String
): DatasetTrends = {
  val dailySummaries = readDailySummaries(dailyLog)
  val lastSnapshot = getLastSnapshot(trendsLog)

  if (dailySummaries.nonEmpty) {
    DatasetTrends(
      spec = spec,
      current = lastSnapshot,
      delta24h = calculateDeltaFromDailySummaries(dailySummaries, 1),
      delta7d = calculateDeltaFromDailySummaries(dailySummaries, 7),
      delta30d = calculateDeltaFromDailySummaries(dailySummaries, 30),
      history = dailySummaries.takeRight(MAX_HISTORY_DAYS).map { ds =>
        TrendSnapshot(
          timestamp = DateTime.parse(ds.date + "T23:59:59.000Z"),
          snapshotType = "daily",
          sourceRecords = ds.endOfDay.sourceRecords,
          acquiredRecords = ds.endOfDay.acquiredRecords,
          deletedRecords = ds.endOfDay.deletedRecords,
          validRecords = ds.endOfDay.validRecords,
          invalidRecords = ds.endOfDay.invalidRecords,
          indexedRecords = ds.endOfDay.indexedRecords
        )
      }
    )
  } else {
    // Fallback to legacy
    getDatasetTrends(trendsLog, spec)
  }
}
```

### Step 4: Run test to verify it passes

Run: `make compile && sbt "testOnly services.TrendTrackingServiceSpec"`
Expected: PASS

### Step 5: Commit

```bash
git add app/services/TrendTrackingService.scala test/services/TrendTrackingServiceSpec.scala
git commit -m "feat(trends): add daily-summary-based summary and trends methods"
```

---

## Task 7: Add trendsDailyLog to DatasetContext

**Files:**
- Modify: `app/dataset/DatasetContext.scala:61`

### Step 1: Add the file reference

In `app/dataset/DatasetContext.scala`, after line 61 (`val trendsLog = ...`), add:

```scala
val trendsDailyLog = new File(rootDir, "trends-daily.jsonl")
```

### Step 2: Verify compilation

Run: `make compile`
Expected: Clean compilation

### Step 3: Commit

```bash
git add app/dataset/DatasetContext.scala
git commit -m "feat(trends): add trendsDailyLog file reference to DatasetContext"
```

---

## Task 8: Update DatasetActor to Use Change-Gated Capture

**Files:**
- Modify: `app/dataset/DatasetActor.scala:1071-1094`

### Step 1: Replace captureSnapshot with captureEventSnapshot

In `app/dataset/DatasetActor.scala`, replace lines 1071-1094 (the try/catch block in the `GraphSaveComplete` handler):

**Old code (lines 1071-1094):**
```scala
// Capture trend snapshot after successful save
try {
  val sourceRecords = dsInfo.getLiteralProp(sourceRecordCount).map(_.toInt).getOrElse(0)
  // ... rest of existing code ...
  TrendTrackingService.captureSnapshot(
    trendsLog = datasetContext.trendsLog,
    snapshotType = "event",
    // ...
  )
} catch {
  case e: Exception =>
    log.warning(s"Failed to capture trend snapshot: ${e.getMessage}")
}
```

**New code:**
```scala
// Capture trend snapshot after successful save (change-gated)
try {
  val sourceRecords = dsInfo.getLiteralProp(sourceRecordCount).map(_.toInt).getOrElse(0)
  val acquiredRecords = dsInfo.getLiteralProp(acquiredRecordCount).map(_.toInt).getOrElse(0)
  val deletedRecords = dsInfo.getLiteralProp(deletedRecordCount).map(_.toInt).getOrElse(0)
  val validRecords = dsInfo.getLiteralProp(processedValid).map(_.toInt).getOrElse(0)
  val invalidRecords = dsInfo.getLiteralProp(processedInvalid).map(_.toInt).getOrElse(0)
  // Use validRecords as proxy for indexed - actual Hub3 count checked in daily aggregation
  val indexedRecords = validRecords

  TrendTrackingService.captureEventSnapshot(
    trendsLog = datasetContext.trendsLog,
    event = "save",
    sourceRecords = sourceRecords,
    acquiredRecords = acquiredRecords,
    deletedRecords = deletedRecords,
    validRecords = validRecords,
    invalidRecords = invalidRecords,
    indexedRecords = indexedRecords
  )
} catch {
  case e: Exception =>
    log.warning(s"Failed to capture trend snapshot: ${e.getMessage}")
}
```

### Step 2: Verify compilation

Run: `make compile`
Expected: Clean compilation

### Step 3: Commit

```bash
git add app/dataset/DatasetActor.scala
git commit -m "feat(trends): use change-gated captureEventSnapshot in DatasetActor"
```

---

## Task 9: Update OrgContext Scheduler to 00:30 Aggregation

**Files:**
- Modify: `app/organization/OrgContext.scala:80-164`
- Modify: `app/init/NarthexConfig.scala:96-100`

### Step 1: Update config default from midnight to 00:30

In `app/init/NarthexConfig.scala`, change the comment and default for `trendSnapshotHour` (lines 96-100):

**Old:**
```scala
// Hour of day (0-23) to run daily trend snapshot (default: 0 = midnight)
def trendSnapshotHour: Int = configuration
  .getOptional[Int]("narthex.trends.snapshotHour")
  .getOrElse(0)
```

**New:**
```scala
// Minute offset past midnight to run daily aggregation (default: 30 = 00:30)
def trendAggregationMinute: Int = configuration
  .getOptional[Int]("narthex.trends.aggregationMinute")
  .getOrElse(30)
logger.info(s"narthex.trends.aggregationMinute: $trendAggregationMinute")
```

Remove the old `logger.info` line for `trendSnapshotHour`.

### Step 2: Rewrite scheduleDailyTrendSnapshot

In `app/organization/OrgContext.scala`, replace `scheduleDailyTrendSnapshot` (lines 89-106):

```scala
/**
 * Schedule daily trend aggregation at 00:30 (or configured minute).
 * Aggregates event snapshots into daily summaries per dataset.
 */
private def scheduleDailyTrendSnapshot(): Unit = {
  val minute = narthexConfig.trendAggregationMinute

  // Calculate initial delay until next 00:MM
  val now = ZonedDateTime.now(ZoneId.systemDefault())
  val targetTime = now.toLocalDate.atTime(LocalTime.of(0, minute)).atZone(ZoneId.systemDefault())
  val nextRun = if (now.isAfter(targetTime)) targetTime.plusDays(1) else targetTime
  val initialDelayMillis = java.time.Duration.between(now, nextRun).toMillis

  logger.info(s"Scheduling daily trend aggregation at 00:$minute. First run in ${initialDelayMillis / 3600000} hours.")

  actorSystem.scheduler.scheduleWithFixedDelay(
    initialDelayMillis.millis,
    24.hours
  )(new Runnable {
    override def run(): Unit = runDailyTrendAggregation()
  })(actorSystem.dispatcher)
}
```

### Step 3: Rewrite runDailyTrendSnapshot to runDailyTrendAggregation

Replace `runDailyTrendSnapshot` (lines 111-164):

```scala
/**
 * Execute daily trend aggregation for all datasets.
 * For each dataset:
 * 1. Read last event snapshot from trends.jsonl
 * 2. Compute daily summary with delta vs previous day
 * 3. Append to trends-daily.jsonl
 * Then regenerate org-level trends_summary.json.
 */
private def runDailyTrendAggregation(): Unit = {
  logger.info("Running daily trend aggregation...")

  // The date we're aggregating is yesterday (since we run after midnight)
  val yesterday = org.joda.time.LocalDate.now().minusDays(1).toString("yyyy-MM-dd")

  val result = for {
    datasets <- DsInfo.listDsInfo(this)
    (_, hub3Counts) <- indexStatsService.fetchHub3IndexCounts()
  } yield {
    var aggregated = 0
    val specs = scala.collection.mutable.ListBuffer[String]()

    datasets.foreach { dsInfo =>
      try {
        val ctx = datasetContext(dsInfo.spec)
        val trendsLog = ctx.trendsLog
        val dailyLog = ctx.trendsDailyLog

        // Update the last event snapshot with actual Hub3 indexed count
        // before aggregating, to get accurate indexed numbers
        val hub3Count = hub3Counts.getOrElse(dsInfo.spec, 0)

        // If the last event snapshot has indexedRecords as a proxy (validRecords),
        // capture a corrected snapshot with Hub3 count if it differs
        val lastSnapshot = TrendTrackingService.getLastSnapshot(trendsLog)
        lastSnapshot.foreach { last =>
          if (last.indexedRecords != hub3Count && hub3Count > 0) {
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

    // Generate organization-level summary file using daily summaries
    try {
      TrendTrackingService.generateTrendsSummaryFromDaily(trendsSummaryFile, datasetsDir, specs.toList)
    } catch {
      case e: Exception =>
        logger.warn(s"Failed to generate trends summary: ${e.getMessage}")
    }

    logger.info(s"Daily trend aggregation complete: $aggregated/${datasets.size} datasets")
  }

  val _ = result.recover {
    case e: Exception =>
      logger.error(s"Daily trend aggregation failed: ${e.getMessage}", e)
  }
}
```

### Step 4: Add generateTrendsSummaryFromDaily to TrendTrackingService

Add to `app/services/TrendTrackingService.scala`:

```scala
/**
 * Generate org-level trends summary using daily summaries (new).
 */
def generateTrendsSummaryFromDaily(summaryFile: File, datasetsDir: File, specs: List[String]): Unit = {
  val summaries = specs.flatMap { spec =>
    val dailyLog = new File(datasetsDir, s"$spec/trends-daily.jsonl")
    val trendsLog = new File(datasetsDir, s"$spec/trends.jsonl")
    getDatasetTrendSummaryFromDaily(dailyLog, trendsLog, spec)
  }

  val summary = TrendsSummaryFile(
    generatedAt = DateTime.now(),
    summaries = summaries
  )

  val tmpFile = new File(summaryFile.getParent, summaryFile.getName + ".tmp")
  val content = Json.prettyPrint(Json.toJson(summary))

  val writer = new java.io.FileWriter(tmpFile)
  try {
    writer.write(content)
    writer.flush()
  } finally {
    writer.close()
  }

  tmpFile.renameTo(summaryFile)
  logger.info(s"Generated trends summary from daily data: ${summaries.size} datasets")
}
```

### Step 5: Verify compilation

Run: `make compile`
Expected: Clean compilation

### Step 6: Commit

```bash
git add app/organization/OrgContext.scala app/init/NarthexConfig.scala app/services/TrendTrackingService.scala
git commit -m "feat(trends): replace midnight snapshot with 00:30 daily aggregation"
```

---

## Task 10: Update AppController to Use Daily Summaries

**Files:**
- Modify: `app/controllers/AppController.scala:205-394`

### Step 1: Update indexStatsWithTrends (lines 211-239)

Replace the `augmentWithTrends` function to use daily summaries:

```scala
def augmentWithTrends(datasets: List[DatasetIndexStats]): List[JsObject] = {
  datasets.map { ds =>
    val dailyLog = new java.io.File(orgContext.datasetsDir, s"${ds.spec}/trends-daily.jsonl")
    val trendsLog = new java.io.File(orgContext.datasetsDir, s"${ds.spec}/trends.jsonl")
    val trendSummary = TrendTrackingService.getDatasetTrendSummaryFromDaily(dailyLog, trendsLog, ds.spec)
    val baseJson = Json.toJson(ds).as[JsObject]

    trendSummary match {
      case Some(summary) =>
        baseJson ++ Json.obj(
          "delta24h" -> Json.obj(
            "source" -> summary.delta24h.source,
            "valid" -> summary.delta24h.valid,
            "indexed" -> summary.delta24h.indexed
          )
        )
      case None =>
        baseJson ++ Json.obj(
          "delta24h" -> Json.obj(
            "source" -> 0,
            "valid" -> 0,
            "indexed" -> 0
          )
        )
    }
  }
}
```

### Step 2: Update getOrganizationTrends fallback (lines 276-280)

In the fallback case, change:

```scala
val trendsLog = orgContext.datasetContext(dsInfo.spec).trendsLog
TrendTrackingService.getDatasetTrendSummary(trendsLog, dsInfo.spec)
```

To:

```scala
val ctx = orgContext.datasetContext(dsInfo.spec)
TrendTrackingService.getDatasetTrendSummaryFromDaily(ctx.trendsDailyLog, ctx.trendsLog, dsInfo.spec)
```

### Step 3: Update getDatasetTrends (lines 325-329)

Replace:

```scala
def getDatasetTrends(spec: String) = Action { request =>
  val trendsLog = orgContext.datasetContext(spec).trendsLog
  val trends = TrendTrackingService.getDatasetTrends(trendsLog, spec)
  Ok(Json.toJson(trends))
}
```

With:

```scala
def getDatasetTrends(spec: String) = Action { request =>
  val ctx = orgContext.datasetContext(spec)
  val trends = TrendTrackingService.getDatasetTrendsFromDaily(ctx.trendsDailyLog, ctx.trendsLog, spec)
  Ok(Json.toJson(trends))
}
```

### Step 4: Update triggerTrendSnapshot (lines 334-394)

Replace the manual snapshot trigger to use the new `captureEventSnapshot` and `aggregateDay`:

```scala
def triggerTrendSnapshot = Action.async { request =>
  import triplestore.GraphProperties._

  val today = org.joda.time.LocalDate.now().toString("yyyy-MM-dd")

  listDsInfo(orgContext).flatMap { datasets =>
    indexStatsService.fetchHub3IndexCounts().map { case (_, hub3Counts) =>
      var captured = 0
      val specs = scala.collection.mutable.ListBuffer[String]()

      datasets.foreach { dsInfo =>
        try {
          val ctx = orgContext.datasetContext(dsInfo.spec)
          val sourceRecords = dsInfo.getLiteralProp(sourceRecordCount).map(_.toInt).getOrElse(0)
          val acquiredRecords = dsInfo.getLiteralProp(acquiredRecordCount).map(_.toInt).getOrElse(0)
          val deletedRecords = dsInfo.getLiteralProp(deletedRecordCount).map(_.toInt).getOrElse(0)
          val validRecords = dsInfo.getLiteralProp(processedValid).map(_.toInt).getOrElse(0)
          val invalidRecords = dsInfo.getLiteralProp(processedInvalid).map(_.toInt).getOrElse(0)
          val indexedRecords = hub3Counts.getOrElse(dsInfo.spec, 0)

          TrendTrackingService.captureEventSnapshot(
            trendsLog = ctx.trendsLog,
            event = "manual",
            sourceRecords = sourceRecords,
            acquiredRecords = acquiredRecords,
            deletedRecords = deletedRecords,
            validRecords = validRecords,
            invalidRecords = invalidRecords,
            indexedRecords = indexedRecords
          )

          // Also aggregate a daily summary for today
          TrendTrackingService.aggregateDay(ctx.trendsLog, ctx.trendsDailyLog, today)

          specs += dsInfo.spec
          captured += 1
        } catch {
          case e: Exception =>
            logger.warn(s"Failed to capture snapshot for ${dsInfo.spec}: ${e.getMessage}")
        }
      }

      // Generate organization-level summary
      try {
        TrendTrackingService.generateTrendsSummaryFromDaily(
          orgContext.trendsSummaryFile,
          orgContext.datasetsDir,
          specs.toList
        )
      } catch {
        case e: Exception =>
          logger.warn(s"Failed to generate trends summary: ${e.getMessage}")
      }

      Ok(Json.obj(
        "success" -> true,
        "datasetsProcessed" -> datasets.size,
        "snapshotsCaptured" -> captured
      ))
    }
  }.recover {
    case e: Exception =>
      logger.error(s"Failed to trigger trend snapshot: ${e.getMessage}", e)
      InternalServerError(Json.obj("error" -> "Failed to trigger trend snapshot"))
  }
}
```

### Step 5: Verify compilation

Run: `make compile`
Expected: Clean compilation

### Step 6: Commit

```bash
git add app/controllers/AppController.scala
git commit -m "feat(trends): update AppController to use daily summaries"
```

---

## Task 11: Update Frontend — Info Text

**Files:**
- Modify: `public/templates/trends.html:151-155`

### Step 1: Update info note

Replace the info alert at lines 151-155:

**Old:**
```html
<strong>Note:</strong> Trend data is captured automatically after each SAVE operation and daily at midnight.
Use the "Capture Snapshot" button to manually capture current counts.
```

**New:**
```html
<strong>Note:</strong> Trend data is captured automatically after each processing run (when counts change).
Daily summaries are aggregated at 00:30 each night.
Use the "Capture Snapshot" button to manually capture current counts and aggregate today's summary.
```

### Step 2: Verify with make compile

Run: `make compile`
Expected: Clean compilation

### Step 3: Commit

```bash
git add public/templates/trends.html
git commit -m "feat(trends): update frontend info text for new aggregation timing"
```

---

## Task 12: Full Integration Test — Compile and Verify

### Step 1: Run full compilation

Run: `make compile`
Expected: Clean compilation, no errors

### Step 2: Run all tests

Run: `sbt test`
Expected: All tests pass including the new `TrendTrackingServiceSpec`

### Step 3: Commit any remaining changes

If any fixups were needed during integration, commit them.

### Step 4: Version bump

Run: `make bump-version`

This updates both `version.sbt` and the JS cache-busting string in `app/assets/javascripts/main.js`.

### Step 5: Commit version bump

```bash
git add version.sbt app/assets/javascripts/main.js
git commit -m "chore: bump version to 0.8.8.0"
```

---

## Summary of Changes

| File | Change |
|------|--------|
| `app/services/TrendTrackingService.scala` | Add `EndOfDayCounts`, `DailySummary` models; `captureEventSnapshot` with guards; `aggregateDay`; `calculateDeltaFromDailySummaries`; `getDatasetTrendSummaryFromDaily`; `getDatasetTrendsFromDaily`; `generateTrendsSummaryFromDaily` |
| `app/dataset/DatasetContext.scala` | Add `trendsDailyLog` file reference |
| `app/dataset/DatasetActor.scala` | Switch from `captureSnapshot` to `captureEventSnapshot` |
| `app/organization/OrgContext.scala` | Replace midnight snapshot with 00:30 aggregation; `runDailyTrendAggregation` |
| `app/init/NarthexConfig.scala` | Replace `trendSnapshotHour` with `trendAggregationMinute` (default 30) |
| `app/controllers/AppController.scala` | All trend endpoints use daily summaries with legacy fallback |
| `public/templates/trends.html` | Updated info text |
| `test/services/TrendTrackingServiceSpec.scala` | New test file with comprehensive tests |
