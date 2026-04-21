package services

import java.io.File
import java.nio.file.Files
import org.joda.time.DateTime
import org.scalatest.flatspec._
import org.scalatest.matchers._
import play.api.libs.json.Json

class TrendTrackingServiceSpec extends AnyFlatSpec with should.Matchers {

  private def withTempDir(testCode: File => Any): Unit = {
    val tmpDir = Files.createTempDirectory("trend-test").toFile
    try {
      testCode(tmpDir)
    } finally {
      tmpDir.listFiles().foreach(_.delete())
      tmpDir.delete()
    }
  }

  private def makeSnapshot(
    sourceRecords: Int = 100,
    acquiredRecords: Int = 100,
    deletedRecords: Int = 0,
    validRecords: Int = 90,
    invalidRecords: Int = 10,
    indexedRecords: Int = 80,
    snapshotType: String = "event"
  ): TrendSnapshot = TrendSnapshot(
    timestamp = DateTime.now(),
    snapshotType = snapshotType,
    sourceRecords = sourceRecords,
    acquiredRecords = acquiredRecords,
    deletedRecords = deletedRecords,
    validRecords = validRecords,
    invalidRecords = invalidRecords,
    indexedRecords = indexedRecords
  )

  // === Task 1: DailySummary JSON round-trip ===

  "DailySummary" should "serialize and deserialize correctly" in {
    val summary = DailySummary(
      date = "2026-02-18",
      endOfDay = EndOfDayCounts(100, 100, 0, 90, 10, 80),
      delta = TrendDelta(5, 3, 2),
      events = 3
    )

    val json = Json.toJson(summary)
    val parsed = json.as[DailySummary]

    parsed.date shouldBe "2026-02-18"
    parsed.endOfDay.sourceRecords shouldBe 100
    parsed.endOfDay.validRecords shouldBe 90
    parsed.delta.source shouldBe 5
    parsed.events shouldBe 3
  }

  "EndOfDayCounts" should "be created from a TrendSnapshot" in {
    val snapshot = makeSnapshot(sourceRecords = 200, validRecords = 180, indexedRecords = 170)
    val eod = EndOfDayCounts.fromSnapshot(snapshot)

    eod.sourceRecords shouldBe 200
    eod.validRecords shouldBe 180
    eod.indexedRecords shouldBe 170
  }

  // === Task 2: captureEventSnapshot ===

  "captureEventSnapshot" should "skip when sourceRecords is 0" in withTempDir { dir =>
    val trendsLog = new File(dir, "trends.jsonl")
    TrendTrackingService.captureEventSnapshot(trendsLog, "harvest", 0, 0, 0, 0, 0, 0)
    trendsLog.exists() shouldBe false
  }

  it should "skip when counts are unchanged" in withTempDir { dir =>
    val trendsLog = new File(dir, "trends.jsonl")
    // Write an initial snapshot
    TrendTrackingService.captureEventSnapshot(trendsLog, "harvest", 100, 100, 0, 90, 10, 80)
    trendsLog.exists() shouldBe true

    val sizeBefore = trendsLog.length()
    // Try to capture with same counts
    TrendTrackingService.captureEventSnapshot(trendsLog, "harvest", 100, 100, 0, 90, 10, 80)
    trendsLog.length() shouldBe sizeBefore
  }

  it should "skip when sourceRecords drops more than 50%" in withTempDir { dir =>
    val trendsLog = new File(dir, "trends.jsonl")
    TrendTrackingService.captureEventSnapshot(trendsLog, "harvest", 1000, 1000, 0, 900, 100, 800)

    val sizeBefore = trendsLog.length()
    // Try with 40% of original (>50% drop)
    TrendTrackingService.captureEventSnapshot(trendsLog, "harvest", 400, 400, 0, 360, 40, 320)
    trendsLog.length() shouldBe sizeBefore
  }

  it should "write snapshot when counts change" in withTempDir { dir =>
    val trendsLog = new File(dir, "trends.jsonl")
    TrendTrackingService.captureEventSnapshot(trendsLog, "harvest", 100, 100, 0, 90, 10, 80)
    val sizeBefore = trendsLog.length()

    TrendTrackingService.captureEventSnapshot(trendsLog, "process", 100, 100, 0, 95, 5, 85)
    trendsLog.length() should be > sizeBefore
  }

  // === Task 2: getLastSnapshot ===

  "getLastSnapshot" should "return None for missing file" in withTempDir { dir =>
    val trendsLog = new File(dir, "nonexistent.jsonl")
    TrendTrackingService.getLastSnapshot(trendsLog) shouldBe None
  }

  it should "return the last snapshot" in withTempDir { dir =>
    val trendsLog = new File(dir, "trends.jsonl")
    TrendTrackingService.captureEventSnapshot(trendsLog, "harvest", 100, 100, 0, 90, 10, 80)
    TrendTrackingService.captureEventSnapshot(trendsLog, "process", 200, 200, 0, 190, 10, 180)

    val last = TrendTrackingService.getLastSnapshot(trendsLog)
    last shouldBe defined
    last.get.sourceRecords shouldBe 200
  }

  // === Task 3: Daily summary read/write ===

  "readDailySummaries" should "return empty list for missing file" in withTempDir { dir =>
    val dailyLog = new File(dir, "trends-daily.jsonl")
    TrendTrackingService.readDailySummaries(dailyLog) shouldBe List.empty
  }

  "appendDailySummary and readDailySummaries" should "round-trip correctly" in withTempDir { dir =>
    val dailyLog = new File(dir, "trends-daily.jsonl")

    val summary1 = DailySummary("2026-02-17", EndOfDayCounts(100, 100, 0, 90, 10, 80), TrendDelta.zero, 2)
    val summary2 = DailySummary("2026-02-18", EndOfDayCounts(110, 110, 0, 100, 10, 90), TrendDelta(10, 10, 10), 3)

    TrendTrackingService.appendDailySummary(dailyLog, summary1)
    TrendTrackingService.appendDailySummary(dailyLog, summary2)

    val summaries = TrendTrackingService.readDailySummaries(dailyLog)
    summaries.size shouldBe 2
    summaries.head.date shouldBe "2026-02-17"
    summaries(1).date shouldBe "2026-02-18"
  }

  "getLastDailySummary" should "return the last entry" in withTempDir { dir =>
    val dailyLog = new File(dir, "trends-daily.jsonl")

    TrendTrackingService.appendDailySummary(dailyLog,
      DailySummary("2026-02-17", EndOfDayCounts(100, 100, 0, 90, 10, 80), TrendDelta.zero, 2))
    TrendTrackingService.appendDailySummary(dailyLog,
      DailySummary("2026-02-18", EndOfDayCounts(110, 110, 0, 100, 10, 90), TrendDelta(10, 10, 10), 3))

    val last = TrendTrackingService.getLastDailySummary(dailyLog)
    last shouldBe defined
    last.get.date shouldBe "2026-02-18"
  }

  it should "return None for missing file" in withTempDir { dir =>
    val dailyLog = new File(dir, "nonexistent.jsonl")
    TrendTrackingService.getLastDailySummary(dailyLog) shouldBe None
  }

  // === Task 4: aggregateDay ===

  "aggregateDay" should "create summary with delta vs previous day" in withTempDir { dir =>
    val trendsLog = new File(dir, "trends.jsonl")
    val dailyLog = new File(dir, "trends-daily.jsonl")

    // Write a snapshot timestamped on 2026-02-18 so aggregateDay picks it up
    val snap = TrendSnapshot(
      timestamp = new DateTime(2026, 2, 18, 12, 0, 0),
      snapshotType = "harvest",
      sourceRecords = 200, acquiredRecords = 200, deletedRecords = 0,
      validRecords = 180, invalidRecords = 20, indexedRecords = 160
    )
    val w1 = services.FileHandling.appender(trendsLog)
    try { w1.write(Json.stringify(Json.toJson(snap)) + "\n") } finally { w1.close() }

    // Write a previous day summary
    TrendTrackingService.appendDailySummary(dailyLog,
      DailySummary("2026-02-17", EndOfDayCounts(100, 100, 0, 90, 10, 80), TrendDelta.zero, 1))

    TrendTrackingService.aggregateDay(trendsLog, dailyLog, "2026-02-18")

    val summaries = TrendTrackingService.readDailySummaries(dailyLog)
    summaries.size shouldBe 2

    val today = summaries(1)
    today.date shouldBe "2026-02-18"
    today.endOfDay.sourceRecords shouldBe 200
    today.delta.source shouldBe 100
    today.delta.valid shouldBe 90
    today.delta.indexed shouldBe 80
  }

  it should "create summary with zero delta when no previous day" in withTempDir { dir =>
    val trendsLog = new File(dir, "trends.jsonl")
    val dailyLog = new File(dir, "trends-daily.jsonl")

    val snap = TrendSnapshot(
      timestamp = new DateTime(2026, 2, 18, 12, 0, 0),
      snapshotType = "harvest",
      sourceRecords = 100, acquiredRecords = 100, deletedRecords = 0,
      validRecords = 90, invalidRecords = 10, indexedRecords = 80
    )
    val w = services.FileHandling.appender(trendsLog)
    try { w.write(Json.stringify(Json.toJson(snap)) + "\n") } finally { w.close() }

    TrendTrackingService.aggregateDay(trendsLog, dailyLog, "2026-02-18")

    val summaries = TrendTrackingService.readDailySummaries(dailyLog)
    summaries.size shouldBe 1
    summaries.head.delta shouldBe TrendDelta.zero
  }

  it should "not create summary when no snapshots exist" in withTempDir { dir =>
    val trendsLog = new File(dir, "trends.jsonl")
    val dailyLog = new File(dir, "trends-daily.jsonl")

    TrendTrackingService.aggregateDay(trendsLog, dailyLog, "2026-02-18")
    dailyLog.exists() shouldBe false
  }

  // === Task 5: calculateDeltaFromDailySummaries ===

  "calculateDeltaFromDailySummaries" should "return zero when not enough data" in {
    val summaries = List(
      DailySummary("2026-02-18", EndOfDayCounts(100, 100, 0, 90, 10, 80), TrendDelta.zero, 1)
    )
    TrendTrackingService.calculateDeltaFromDailySummaries(summaries, 1) shouldBe TrendDelta.zero
  }

  it should "compute delta for 1-day window" in {
    val summaries = List(
      DailySummary("2026-02-17", EndOfDayCounts(100, 100, 0, 90, 10, 80), TrendDelta.zero, 1),
      DailySummary("2026-02-18", EndOfDayCounts(150, 150, 0, 140, 10, 130), TrendDelta(50, 50, 50), 2)
    )
    val delta = TrendTrackingService.calculateDeltaFromDailySummaries(summaries, 1)
    delta.source shouldBe 50
    delta.valid shouldBe 50
    delta.indexed shouldBe 50
  }

  it should "compute delta for 7-day window" in {
    val base = EndOfDayCounts(100, 100, 0, 90, 10, 80)
    val summaries = (0 to 7).map { i =>
      val date = org.joda.time.LocalDate.parse("2026-02-11").plusDays(i).toString("yyyy-MM-dd")
      val counts = EndOfDayCounts(100 + i * 10, 100 + i * 10, 0, 90 + i * 10, 10, 80 + i * 10)
      DailySummary(date, counts, TrendDelta.zero, 1)
    }.toList

    val delta = TrendTrackingService.calculateDeltaFromDailySummaries(summaries, 7)
    // Current (day 7) vs baseline at day 0 (7 days ago): 170-100=70
    delta.source shouldBe 70
  }

  it should "return zero for empty list" in {
    TrendTrackingService.calculateDeltaFromDailySummaries(List.empty, 1) shouldBe TrendDelta.zero
  }

  // === Task 6: getDatasetTrendSummaryFromDaily ===

  "getDatasetTrendSummaryFromDaily" should "compute summary from daily file" in withTempDir { dir =>
    val dailyLog = new File(dir, "trends-daily.jsonl")
    val trendsLog = new File(dir, "trends.jsonl")

    TrendTrackingService.appendDailySummary(dailyLog,
      DailySummary("2026-02-17", EndOfDayCounts(100, 100, 0, 90, 10, 80), TrendDelta.zero, 1))
    TrendTrackingService.appendDailySummary(dailyLog,
      DailySummary("2026-02-18", EndOfDayCounts(150, 150, 0, 140, 10, 130), TrendDelta(50, 50, 50), 2))

    val result = TrendTrackingService.getDatasetTrendSummaryFromDaily(dailyLog, trendsLog, "test-spec")
    result shouldBe defined
    result.get.spec shouldBe "test-spec"
    result.get.currentSource shouldBe 150
    result.get.currentValid shouldBe 140
    result.get.currentIndexed shouldBe 130
  }

  it should "fall back to trends log when no daily data exists" in withTempDir { dir =>
    val dailyLog = new File(dir, "trends-daily.jsonl")
    val trendsLog = new File(dir, "trends.jsonl")

    // Write only to trends log
    TrendTrackingService.captureEventSnapshot(trendsLog, "harvest", 100, 100, 0, 90, 10, 80)

    val result = TrendTrackingService.getDatasetTrendSummaryFromDaily(dailyLog, trendsLog, "test-spec")
    result shouldBe defined
    result.get.currentSource shouldBe 100
  }

  "getDatasetTrendsFromDaily" should "build trends from daily summaries" in withTempDir { dir =>
    val dailyLog = new File(dir, "trends-daily.jsonl")
    val trendsLog = new File(dir, "trends.jsonl")

    TrendTrackingService.appendDailySummary(dailyLog,
      DailySummary("2026-02-17", EndOfDayCounts(100, 100, 0, 90, 10, 80), TrendDelta.zero, 1))
    TrendTrackingService.appendDailySummary(dailyLog,
      DailySummary("2026-02-18", EndOfDayCounts(150, 150, 0, 140, 10, 130), TrendDelta(50, 50, 50), 2))

    // Also write a snapshot for "current"
    TrendTrackingService.captureEventSnapshot(trendsLog, "harvest", 150, 150, 0, 140, 10, 130)

    val trends = TrendTrackingService.getDatasetTrendsFromDaily(dailyLog, trendsLog, "test-spec")
    trends.spec shouldBe "test-spec"
    trends.current shouldBe defined
    trends.history.size shouldBe 2
    trends.history.head.snapshotType shouldBe "daily"
  }

  it should "fall back to trends log when no daily data" in withTempDir { dir =>
    val dailyLog = new File(dir, "trends-daily.jsonl")
    val trendsLog = new File(dir, "trends.jsonl")

    TrendTrackingService.captureEventSnapshot(trendsLog, "harvest", 100, 100, 0, 90, 10, 80)

    val trends = TrendTrackingService.getDatasetTrendsFromDaily(dailyLog, trendsLog, "test-spec")
    trends.spec shouldBe "test-spec"
    trends.current shouldBe defined
    trends.current.get.sourceRecords shouldBe 100
  }

  // === Task 1 fix: captureEventSnapshotCarryingIndexed ===

  "captureEventSnapshotCarryingIndexed" should "carry previous indexedRecords forward" in withTempDir { tmpDir =>
    val trendsLog = new File(tmpDir, "trends.jsonl")
    // Seed a snapshot where Hub3 has 800 indexed
    TrendTrackingService.captureEventSnapshot(
      trendsLog, "save",
      sourceRecords = 1000, acquiredRecords = 1000, deletedRecords = 0,
      validRecords = 900, invalidRecords = 100, indexedRecords = 800
    )
    // New SAVE: valid climbs to 950 but Hub3 index not yet refreshed
    TrendTrackingService.captureEventSnapshotCarryingIndexed(
      trendsLog, "save",
      sourceRecords = 1050, acquiredRecords = 1050, deletedRecords = 0,
      validRecords = 950, invalidRecords = 100
    )
    val snaps = TrendTrackingService.readSnapshots(trendsLog)
    snaps.size shouldBe 2
    snaps.last.indexedRecords shouldBe 800
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

  // === Task 2 fix: aggregateDay picks target-date snapshot ===

  "aggregateDay" should "aggregate the last snapshot within the target date, not the global last" in withTempDir { tmpDir =>
    val trendsLog = new File(tmpDir, "trends.jsonl")
    val dailyLog = new File(tmpDir, "trends-daily.jsonl")

    val yesterday = DateTime.now().minusDays(1).withTime(23, 50, 0, 0)
    val today = DateTime.now().withTime(0, 5, 0, 0)

    val yesterdaySnap = TrendSnapshot(
      timestamp = yesterday,
      snapshotType = "save",
      sourceRecords = 1000, acquiredRecords = 1000, deletedRecords = 0,
      validRecords = 900, invalidRecords = 100, indexedRecords = 850
    )
    val todaySnap = TrendSnapshot(
      timestamp = today,
      snapshotType = "save",
      sourceRecords = 1500, acquiredRecords = 1500, deletedRecords = 0,
      validRecords = 1400, invalidRecords = 100, indexedRecords = 850
    )

    val yesterdayDate = yesterday.toString("yyyy-MM-dd")

    val w = services.FileHandling.appender(trendsLog)
    try {
      w.write(Json.stringify(Json.toJson(yesterdaySnap)) + "\n")
      w.write(Json.stringify(Json.toJson(todaySnap)) + "\n")
    } finally { w.close() }

    TrendTrackingService.aggregateDay(trendsLog, dailyLog, yesterdayDate)

    val summaries = TrendTrackingService.readDailySummaries(dailyLog)
    summaries.size shouldBe 1
    summaries.head.endOfDay.sourceRecords shouldBe 1000
    summaries.head.endOfDay.validRecords shouldBe 900
  }

  it should "carry forward previous end-of-day when no snapshots exist on target date" in withTempDir { tmpDir =>
    val trendsLog = new File(tmpDir, "trends.jsonl")
    val dailyLog = new File(tmpDir, "trends-daily.jsonl")

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

    TrendTrackingService.aggregateDay(trendsLog, dailyLog, "2026-04-19")

    val summaries = TrendTrackingService.readDailySummaries(dailyLog)
    summaries.size shouldBe 2
    summaries.last.date shouldBe "2026-04-19"
    summaries.last.endOfDay.sourceRecords shouldBe 500
    summaries.last.delta shouldBe TrendDelta.zero
    summaries.last.events shouldBe 0
  }
}
