# Trends Lag + Search Filter Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make harvest-driven record changes visible in the trends UI same-day, eliminate the one-day scheduler-vs-aggregation timezone gap, and restore the broken "Search datasets" filter on both Trends and Index Stats pages.

**Architecture:** Three independent fixes:
1. Schedule the daily aggregation job in UTC so its target-day calculation (`LocalDate.now(UTC).minusDays(1)`) lines up with the moment the cron actually fires.
2. Run `aggregateDay(today_UTC)` after every successful SAVE so today's daily-summary row materialises (and is refreshed) without waiting for the 00:30 UTC cron — making intra-day source/valid changes visible immediately.
3. Fix AngularJS scope-shadow bug on the search input: bind `ng-model` to an object property (dot-rule) instead of a primitive that gets shadowed by `ng-if`'s child scope.

**Tech Stack:** Scala 2.13, Play 2.8, Akka, Joda Time, ScalaTest, AngularJS 1.3. Build: `make compile`. Tests: `sbt "testOnly services.TrendTrackingServiceSpec"` etc.

---

## Background — User-Reported Symptoms

User on `ingestion.brabantcloud.hubs.delving.io` (organisation `brabantcloud`) reports that recent harvest gains do not appear in the Trends UI:

- 2026-05-02: dataset `rijksmuseum-van-oudheden` periodic harvest +5,355 records — visible in Activity Log, invisible in trend stats.
- 2026-05-03: dataset `enb-376-bidprentje` periodic harvest +~5,000 valid records (17,441 → 22,361) — invisible the next morning (2026-05-04) in both 24h and 7d windows.
- "Search datasets" input boxes on Trends and Index Stats pages do nothing.

Investigation summary:

- Cron is scheduled at 00:30 system local time (`OrgContext.scala:93-94`, `ZoneId.systemDefault()`), but the date it aggregates is computed in UTC (`OrgContext.scala:127`). On Europe/Amsterdam (CEST = UTC+2), 00:30 local = 22:30 UTC, so `LocalDate.now(UTC).minusDays(1)` returns the day before yesterday in local terms. Result: a full extra day of lag.
- Even with the timezone fix, intra-day movement is invisible until the next 00:30 UTC firing because no daily summary for today exists.
- `searchQuery` is a primitive `$scope` property; the search input is rendered inside `<div ng-if="!loading && !error && trends">` (`trends.html:38`) / `<div ng-if="!loading && !error">` (`index-stats.html:26`). `ng-if` creates a child scope; `ng-model="searchQuery"` writes to the child scope, never reaches the controller's reading of `$scope.searchQuery`.

---

## Task Ordering

Tasks 1 and 2 fix the trend visibility issue. Task 1 alone closes the unintentional one-day gap; Task 2 closes the intentional same-day gap. Deploy after Task 2.

Tasks 3 and 4 fix the unrelated search-filter regression on Index Stats and Trends respectively. Independent of the trend fixes, can be reordered.

Task 5 is final verification + version bump + deploy.

---

## File Inventory

**Modified files:**
- `app/organization/OrgContext.scala` (lines 90-105) — schedule cron in UTC; align target-date computation.
- `app/dataset/DatasetActor.scala` (lines 1100-1119) — call `aggregateDay(today_UTC)` after SAVE-snapshot capture.
- `app/services/TrendTrackingService.scala` — no behavioural change in this plan, but the new `aggregateDay` invocation must be safe to call repeatedly within the same UTC date (it already replaces the day's row in `trends-daily.jsonl` if appended again — verified in Task 2).
- `app/assets/javascripts/indexStats/index-stats-controllers.js` — replace `$scope.searchQuery` primitive with `$scope.search.query` object property.
- `app/assets/javascripts/trends/trends-controllers.js` — same replacement.
- `public/templates/index-stats.html` — `ng-model`, `ng-show`, `ng-if`, interpolation references updated to `search.query`.
- `public/templates/trends.html` — same.
- `test/services/TrendTrackingServiceSpec.scala` — regression tests.
- `version.sbt` and `app/assets/javascripts/main.js` — version bump.

---

## Task 1: Schedule daily aggregation in UTC

**Problem:** `scheduleDailyTrendSnapshot` builds `targetTime` in `ZoneId.systemDefault()`. `runDailyTrendAggregation` then calls `LocalDate.now(UTC).minusDays(1)`. On a server in CEST, the cron fires at 00:30 local = 22:30 UTC the previous calendar day, so the aggregator targets *the day before yesterday* in local terms. Concretely: harvest on 2026-05-03 is not aggregated until the cron firing on 2026-05-05 at 00:30 local = 22:30 UTC 2026-05-04, which finally targets "2026-05-03".

**Fix:** Schedule in UTC so 00:30 UTC fires immediately after midnight UTC, and the `LocalDate.now(UTC).minusDays(1)` computation correctly identifies the just-completed day.

**Files:**
- Modify: `app/organization/OrgContext.scala:90-105`

- [ ] **Step 1: Read the current scheduler block to confirm exact lines**

Run: `sed -n '20,30p;80,120p' app/organization/OrgContext.scala`

Expected: imports include `import java.time.{LocalTime, ZoneId, ZonedDateTime}`; method `scheduleDailyTrendSnapshot` exists at line 90.

- [ ] **Step 2: Replace `ZoneId.systemDefault()` with UTC in scheduler computation**

Edit `app/organization/OrgContext.scala`. In `scheduleDailyTrendSnapshot()` at lines 93-95, change:

```scala
    val now = ZonedDateTime.now(ZoneId.systemDefault())
    val targetTime = now.toLocalDate.atTime(LocalTime.of(0, minute)).atZone(ZoneId.systemDefault())
    val nextRun = if (now.isAfter(targetTime)) targetTime.plusDays(1) else targetTime
```

to:

```scala
    val zone = ZoneId.of("UTC")
    val now = ZonedDateTime.now(zone)
    val targetTime = now.toLocalDate.atTime(LocalTime.of(0, minute)).atZone(zone)
    val nextRun = if (now.isAfter(targetTime)) targetTime.plusDays(1) else targetTime
```

- [ ] **Step 3: Update the log message to mention UTC**

In the same method around line 98, change:

```scala
    logger.info(s"Scheduling daily trend aggregation at 00:$minute. First run in ${initialDelayMillis / 3600000} hours.")
```

to:

```scala
    logger.info(s"Scheduling daily trend aggregation at 00:$minute UTC. First run in ${initialDelayMillis / 3600000} hours.")
```

- [ ] **Step 4: Compile**

Run: `unset JDK_JAVA_OPTIONS; make compile 2>&1 | tail -5`
Expected: `[success]`.

- [ ] **Step 5: Smoke-test scheduler logic in REPL (optional)**

Run:
```bash
unset JDK_JAVA_OPTIONS; sbt "console" <<EOF
import java.time._
val zone = ZoneId.of("UTC")
val now = ZonedDateTime.now(zone)
val target = now.toLocalDate.atTime(LocalTime.of(0, 30)).atZone(zone)
val next = if (now.isAfter(target)) target.plusDays(1) else target
println(s"now=\$now next=\$next delayHours=\${java.time.Duration.between(now, next).toHours}")
EOF
```

Expected: `next` is at 00:30 UTC on either today or tomorrow depending on current UTC time; `delayHours` between 0 and 24.

- [ ] **Step 6: Human diff review**

Reference skill: diff-review-before-task-commit
Wait for human acknowledgment before proceeding to commit.

- [ ] **Step 7: Commit**

```bash
git add app/organization/OrgContext.scala
git commit -m "$(cat <<'EOF'
fix(trends): schedule daily aggregation in UTC

scheduleDailyTrendSnapshot computed targetTime in ZoneId.systemDefault()
but runDailyTrendAggregation derives the target day with
LocalDate.now(UTC).minusDays(1). On servers in CEST/CET this caused
the cron to fire at 22:30/23:30 UTC and aggregate "the day before
yesterday" in local terms, leaving harvests invisible in the trends
UI for an extra full day.

Switching the scheduler to UTC means 00:30 UTC fires right after the
UTC day boundary, and the aggregator's UTC date math lines up with
the just-completed day.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: Aggregate today after every SAVE

**Problem:** Even with Task 1 applied, today's harvest is not reflected in the trends UI until the next 00:30 UTC firing. Users expect to see a +5,000 jump within minutes, not a day later.

**Fix:** After capturing the per-event snapshot in `DatasetActor.Saving.GraphSaveComplete`, immediately call `TrendTrackingService.aggregateDay(trendsLog, dailyLog, today_UTC)`. This rewrites today's row in `trends-daily.jsonl` with the latest end-of-day counts (`aggregateDay` filters all of today's snapshots and uses the last one). Subsequent saves on the same day refresh the row.

**Side effect to verify:** if `aggregateDay` is called multiple times for the same date, `trends-daily.jsonl` should not accumulate stale rows — `appendDailySummary` simply appends, so the last-row reader still wins, but old rows pile up. Acceptable for now (cleanupOldSnapshots prunes, and rows are tiny). Document this in a comment so future readers know it is intentional.

The `indexed` count on these intra-day daily rows will lag because Hub3 push is async; the 00:30 UTC cron still pulls real Hub3 counts via `fetchHub3IndexCounts` and writes a `daily` reconciliation snapshot. That is the existing behaviour and is acceptable.

**Files:**
- Modify: `app/dataset/DatasetActor.scala:1100-1119`
- Test: `test/services/TrendTrackingServiceSpec.scala`

- [ ] **Step 1: Confirm `aggregateDay` is safe to call multiple times for the same date**

Read `app/services/TrendTrackingService.scala:751-805`.

Expected: `aggregateDay` reads `trendsLog`, filters by date, computes end-of-day, and `appendDailySummary` appends a new row. Multiple calls per day → multiple rows for the same date in `trends-daily.jsonl`. Readers (`readDailySummaries` then `.last`) take the most recent — semantically correct, just slightly bloated.

- [ ] **Step 2: Write a regression test that two same-day aggregations both end up reflected, with the later one winning**

Append to `test/services/TrendTrackingServiceSpec.scala` (find the end of the existing `class TrendTrackingServiceSpec` body):

```scala
  it should "let a second aggregateDay call on the same date overwrite the effective summary" in withTempDir { tmpDir =>
    val trendsLog = new File(tmpDir, "trends.jsonl")
    val dailyLog = new File(tmpDir, "trends-daily.jsonl")
    val today = org.joda.time.LocalDate.now(org.joda.time.DateTimeZone.UTC).toString("yyyy-MM-dd")

    // First save
    TrendTrackingService.captureEventSnapshot(
      trendsLog, "save",
      sourceRecords = 1000, acquiredRecords = 1000, deletedRecords = 0,
      validRecords = 900, invalidRecords = 100, indexedRecords = 800
    )
    TrendTrackingService.aggregateDay(trendsLog, dailyLog, today)

    // Second save the same day with more records
    TrendTrackingService.captureEventSnapshot(
      trendsLog, "save",
      sourceRecords = 1500, acquiredRecords = 1500, deletedRecords = 0,
      validRecords = 1400, invalidRecords = 100, indexedRecords = 800
    )
    TrendTrackingService.aggregateDay(trendsLog, dailyLog, today)

    val summaries = TrendTrackingService.readDailySummaries(dailyLog)
    summaries.last.date shouldBe today
    summaries.last.endOfDay.sourceRecords shouldBe 1500
    summaries.last.endOfDay.validRecords shouldBe 1400
  }
```

- [ ] **Step 3: Run the new test to verify it passes against current code**

Run: `unset JDK_JAVA_OPTIONS; sbt "testOnly services.TrendTrackingServiceSpec" 2>&1 | tail -10`
Expected: PASS (this confirms `aggregateDay` is already safe; the test pins the contract for Task 2).

- [ ] **Step 4: Modify `DatasetActor.Saving.GraphSaveComplete` to aggregate today**

In `app/dataset/DatasetActor.scala`, replace the block at lines 1100-1119 (the existing `try { ... captureEventSnapshotCarryingIndexed ... }`) with:

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

        // Refresh today's row in trends-daily.jsonl so the UI shows source/valid
        // movement immediately instead of waiting for the 00:30 UTC cron. The
        // indexed column is still carried-forward here; the cron later pulls the
        // real Hub3 count and writes a "daily" reconciliation snapshot.
        val todayUtc = org.joda.time.LocalDate.now(org.joda.time.DateTimeZone.UTC).toString("yyyy-MM-dd")
        TrendTrackingService.aggregateDay(
          datasetContext.trendsLog,
          datasetContext.trendsDailyLog,
          todayUtc
        )
      } catch {
        case e: Exception =>
          log.warning(s"Failed to capture trend snapshot: ${e.getMessage}")
      }
```

- [ ] **Step 5: Verify `DatasetContext.trendsDailyLog` exists; if not, add it**

Run: `grep -n "trendsDailyLog\|trends-daily.jsonl" app/dataset/DatasetContext.scala`
Expected: a `val trendsDailyLog = new File(rootDir, "trends-daily.jsonl")` declaration exists alongside `trendsLog`. If missing, add it next to `trendsLog`:

```scala
  val trendsDailyLog = new File(rootDir, "trends-daily.jsonl")
```

- [ ] **Step 6: Compile**

Run: `unset JDK_JAVA_OPTIONS; make compile 2>&1 | tail -5`
Expected: `[success]`.

- [ ] **Step 7: Run tests**

Run: `unset JDK_JAVA_OPTIONS; sbt "testOnly services.TrendTrackingServiceSpec" 2>&1 | tail -10`
Expected: all tests pass.

- [ ] **Step 8: Manual sanity check (optional, requires running server)**

```bash
# Pre-condition: pick a small test dataset that you can re-save quickly.
# Watch the file:
tail -f ~/NarthexFiles/devorg/datasets/<spec>/trends-daily.jsonl
# Trigger a save in the UI; expect a new row to appear with today's UTC date.
```

- [ ] **Step 9: Human diff review**

Reference skill: diff-review-before-task-commit
Wait for human acknowledgment before proceeding to commit.

- [ ] **Step 10: Commit**

```bash
git add app/dataset/DatasetActor.scala app/dataset/DatasetContext.scala test/services/TrendTrackingServiceSpec.scala
git commit -m "$(cat <<'EOF'
feat(trends): refresh today's daily summary after every SAVE

Previously the trends UI only saw a movement after the 00:30 UTC cron
ran, which meant a harvest at 14:52 UTC on day N was invisible until
the morning of day N+1. Users on production reported this lag
repeatedly (rijksmuseum-van-oudheden +5,355; enb-376-bidprentje
+~5,000) — both cases visible in the Activity Log but not in trends.

After captureEventSnapshotCarryingIndexed, also call aggregateDay for
today's UTC date. This appends/refreshes the today row in
trends-daily.jsonl so the next /trends API call surfaces the change
immediately. Multiple saves the same day each rewrite the row;
readers take the last entry.

The indexed column on these intra-day rows is still carried-forward
because Hub3 push is async; the 00:30 UTC cron continues to pull the
real Hub3 count and write a "daily" reconciliation snapshot.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: Fix Search filter on Index Stats (AngularJS scope shadow)

**Problem:** `public/templates/index-stats.html:26` wraps the search input inside `<div ng-if="!loading && !error">`. AngularJS `ng-if` creates a child scope. `ng-model="searchQuery"` (primitive) writes to that child scope's own `searchQuery`, never reaching the controller's `$scope.searchQuery`. Filter logic reads the empty parent value and returns the unfiltered list.

**Fix:** Apply the AngularJS "always have a dot in `ng-model`" rule. Replace the primitive with an object property `search.query`. Child scopes inherit the parent object reference; writes to `search.query` mutate the parent's object.

**Files:**
- Modify: `app/assets/javascripts/indexStats/index-stats-controllers.js:29,53-68`
- Modify: `public/templates/index-stats.html:79-103`

- [ ] **Step 1: Replace primitive with object in the controller**

Edit `app/assets/javascripts/indexStats/index-stats-controllers.js`. Change line 29:

```javascript
        $scope.searchQuery = '';
```

to:

```javascript
        $scope.search = { query: '' };
```

Replace `filterBySearch` (lines 50-61) with:

```javascript
        /**
         * Filter datasets by search query
         */
        function filterBySearch(datasets) {
            var raw = ($scope.search && $scope.search.query) || '';
            if (raw.trim() === '') {
                return datasets;
            }
            var query = raw.toLowerCase().trim();
            return datasets.filter(function(ds) {
                return ds.spec.toLowerCase().indexOf(query) !== -1;
            });
        }
```

Replace `clearSearch` (lines 63-68):

```javascript
        /**
         * Clear search query
         */
        $scope.clearSearch = function () {
            $scope.search.query = '';
        };
```

- [ ] **Step 2: Update template to use `search.query`**

Edit `public/templates/index-stats.html`. Replace every reference to `searchQuery` inside the controller's template body with `search.query`:

Line 84: `ng-model="searchQuery"` → `ng-model="search.query"`
Line 85: `ng-show="searchQuery"` → `ng-show="search.query"`
Line 97: `ng-if="searchQuery"` → `ng-if="search.query"`; `"{{ searchQuery }}"` → `"{{ search.query }}"`
Lines 98-103: `ng-if="!searchQuery && activeTab === ..."` → `ng-if="!search.query && activeTab === ..."` (six occurrences).

Verify after edit: `grep -n "searchQuery" public/templates/index-stats.html` returns no matches.

- [ ] **Step 3: Compile (recompiles JS via the asset pipeline)**

Run: `unset JDK_JAVA_OPTIONS; make compile 2>&1 | tail -5`
Expected: `[success]`.

- [ ] **Step 4: Manual UI test**

Run: `unset JDK_JAVA_OPTIONS; make run`
Open `http://localhost:9000/narthex/#/index-stats`. Type a substring of an existing dataset spec into "Search datasets...". Expected: list filters live (with 150 ms debounce); clear button appears and works; tab counts reflect the filter.

- [ ] **Step 5: Human diff review**

Reference skill: diff-review-before-task-commit

- [ ] **Step 6: Commit**

```bash
git add app/assets/javascripts/indexStats/index-stats-controllers.js public/templates/index-stats.html
git commit -m "$(cat <<'EOF'
fix(indexStats): bind search input to object property to escape ng-if scope

The Search datasets input lived inside <div ng-if="!loading && !error">,
which creates a child scope. ng-model on a primitive (searchQuery) wrote
to the child scope and never reached the controller's $scope.searchQuery,
so filterBySearch always saw an empty string and returned the unfiltered
list.

Apply the AngularJS "always have a dot" rule: bind to $scope.search.query.
Child scopes inherit the parent object reference, so writes mutate the
shared object.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: Fix Search filter on Trends (same scope-shadow bug)

**Problem:** Identical to Task 3 but on `public/templates/trends.html:38` (`<div ng-if="!loading && !error && trends">`). Controller in `app/assets/javascripts/trends/trends-controllers.js:26`.

**Files:**
- Modify: `app/assets/javascripts/trends/trends-controllers.js:26,156-187`
- Modify: `public/templates/trends.html:79-87`

- [ ] **Step 1: Replace primitive with object in the controller**

Edit `app/assets/javascripts/trends/trends-controllers.js`. Change line 26:

```javascript
        $scope.searchQuery = '';
```

to:

```javascript
        $scope.search = { query: '' };
```

Replace `filterBySearch` (lines 156-164):

```javascript
        /**
         * Filter datasets by search query
         */
        function filterBySearch(datasets) {
            var raw = ($scope.search && $scope.search.query) || '';
            if (raw.trim() === '') {
                return datasets;
            }
            var query = raw.toLowerCase().trim();
            return datasets.filter(function(ds) {
                return ds.spec.toLowerCase().indexOf(query) !== -1;
            });
        }
```

Replace `clearSearch` (lines 182-187):

```javascript
        /**
         * Clear search query
         */
        $scope.clearSearch = function () {
            $scope.search.query = '';
        };
```

- [ ] **Step 2: Update template to use `search.query`**

Edit `public/templates/trends.html`. Replace every `searchQuery` reference inside the template body:

Line 79: `ng-model="searchQuery"` → `ng-model="search.query"`
Line 80: `ng-show="searchQuery"` → `ng-show="search.query"`

Verify: `grep -n "searchQuery" public/templates/trends.html` returns no matches.

- [ ] **Step 3: Compile**

Run: `unset JDK_JAVA_OPTIONS; make compile 2>&1 | tail -5`
Expected: `[success]`.

- [ ] **Step 4: Manual UI test**

Run: `unset JDK_JAVA_OPTIONS; make run`
Open `http://localhost:9000/narthex/#/trends`. Type a substring of an existing dataset spec. Expected: Growing/Shrinking/Stable tab badges and rows update live; clear button works.

- [ ] **Step 5: Human diff review**

Reference skill: diff-review-before-task-commit

- [ ] **Step 6: Commit**

```bash
git add app/assets/javascripts/trends/trends-controllers.js public/templates/trends.html
git commit -m "$(cat <<'EOF'
fix(trends): bind search input to object property to escape ng-if scope

Same AngularJS scope-shadow bug as Index Stats: the search input lives
inside <div ng-if="!loading && !error && trends"> which creates a child
scope, and ng-model on a primitive ($scope.searchQuery) wrote to the
child scope so the controller never saw user input.

Bind to $scope.search.query instead.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: Final verification + version bump + deploy

- [ ] **Step 1: Run full test suite**

Run: `unset JDK_JAVA_OPTIONS; sbt test 2>&1 | tail -20`
Expected: all tests pass.

- [ ] **Step 2: Manual integration verification on local dev server**

Run: `unset JDK_JAVA_OPTIONS; make run`

Verify:
1. Pick a dataset with at least one previous trend snapshot. Trigger a SAVE (re-process or re-harvest). Watch `~/NarthexFiles/devorg/datasets/<spec>/trends-daily.jsonl` — a new row with today's UTC date should appear within seconds of save completion.
2. Open `http://localhost:9000/narthex/#/trends`. Confirm `delta24h` reflects today's change.
3. Type into the search box on both `/trends` and `/index-stats`. Confirm filtering works.

- [ ] **Step 3: Bump version**

Run: `make set-version V=0.8.7.22`
This commits and tags the version bump in both `version.sbt` and `app/assets/javascripts/main.js`.

- [ ] **Step 4: Push commits and tags**

```bash
git push && git push --tags
```

- [ ] **Step 5: Deploy to production**

```bash
make deploy SSH_HOST=root@ingestion.brabantcloud.hubs.delving.io ORG_ID=brabantcloud
```

- [ ] **Step 6: Post-deploy verification**

SSH to the server (or use the application logs over the deploy SSH session) and confirm:
1. Application logs show `Scheduling daily trend aggregation at 00:30 UTC. First run in N hours.` where N matches expectation for current UTC time.
2. Pick a recently-active dataset; trigger or wait for a SAVE; confirm `trends-daily.jsonl` for that dataset has a new row with today's UTC date.
3. Open the production Trends UI. Confirm intra-day movement appears for the active dataset.
4. Confirm Search datasets input filters the list on both pages.

- [ ] **Step 7: Notify the user**

Report back with:
- The version deployed (0.8.7.22).
- Confirmation that intra-day trend updates now appear within seconds of SAVE.
- Confirmation that the search filter works on both pages.
- A note that historical snapshots from before the timezone fix were filtered into the wrong UTC date during aggregation; if any of yesterday/today's daily summaries look off, a one-off `aggregateDay` call (via the existing `/api/trends/snapshot` POST or by deleting `trends-daily.jsonl` for the affected datasets and re-running the cron) will rebuild from the event log.
