# OAI-PMH Date-Only Granularity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add per-dataset boolean controlling whether the OAI-PMH `from=` parameter is sent as date-only (`YYYY-MM-DD`) or full timestamp (`YYYY-MM-DDTHH:MM:SSZ`); remove the WEEKS heuristic that wrongly couples cron cadence to server timestamp granularity.

**Architecture:** Standalone NXProp `harvestDateOnly` (boolean), persisted as RDF triple per dataset. Read at the single OAI-PMH strategy construction site (`PeriodicHarvest.scala:97`) and passed to existing `ModifiedAfter(mod, justDate)` strategy. Existing `Harvesting.fetchPMHPage` format branch is unchanged in behavior — but the format logic is extracted into a pure helper to enable unit testing.

**Tech Stack:** Scala 2.13, Play Framework 2.8.20, ScalaTest, Joda Time, AngularJS 1.3 (legacy frontend), Apache Fuseki triplestore.

**Spec:** `docs/specs/2026-04-20-oai-pmh-date-only-granularity-design.md`

---

## File Structure

| File | Responsibility | Action |
|------|----------------|--------|
| `app/harvest/Harvesting.scala` | OAI-PMH request construction; will gain extracted pure helper `formatFromParameter` | Modify (lines 281-286 + add helper) |
| `app/triplestore/GraphProperties.scala` | NXProp registry | Modify (add one line) |
| `app/dataset/DsInfo.scala` | DsInfo serialization registry (`webSocketFields`) | Modify (add one line) |
| `app/harvest/PeriodicHarvest.scala` | Cron-driven harvest scheduler | Modify (lines 96-97) |
| `public/templates/dataset-list.html` | Dataset config form (AngularJS) | Modify (add checkbox in cron section) |
| `app/assets/javascripts/datasetList/dataset-list-controllers.js` | Frontend save logic | Modify (add field name to `harvestCronFields`) |
| `test/harvest/HarvestingFromFormatSpec.scala` | New unit test | Create |
| `test/dataset/DsInfoSerializationSpec.scala` | Existing serialization registry test | Modify (add field to `saveableHarvestFields` set) |

---

## Task 1: Extract `formatFromParameter` helper + tests

**Files:**
- Modify: `app/harvest/Harvesting.scala` (lines 281-286)
- Create: `test/harvest/HarvestingFromFormatSpec.scala`

The current format logic is buried inside `fetchPMHPage` and unreachable from tests because it lives in the middle of a `withQueryString` chain. Extract it into a pure helper on the `Harvesting` companion object so it can be tested directly.

- [ ] **Step 1: Write the failing test**

Create `test/harvest/HarvestingFromFormatSpec.scala`:

```scala
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

package harvest

import org.joda.time.{DateTime, DateTimeZone}
import org.scalatest.flatspec._
import org.scalatest.matchers._

class HarvestingFromFormatSpec extends AnyFlatSpec with should.Matchers {

  private val sampleTime = new DateTime(2026, 3, 9, 14, 30, 45, DateTimeZone.UTC)

  "formatFromParameter" should "produce date-only format when justDate is true" in {
    val result = Harvesting.formatFromParameter(sampleTime, justDate = true)
    result shouldBe "2026-03-09"
  }

  it should "produce full ISO UTC format when justDate is false" in {
    val result = Harvesting.formatFromParameter(sampleTime, justDate = false)
    result shouldBe "2026-03-09T14:30:45Z"
  }

  it should "strip sub-second precision when justDate is false" in {
    val withMillis = sampleTime.withMillis(sampleTime.getMillis + 123)
    val result = Harvesting.formatFromParameter(withMillis, justDate = false)
    result shouldBe "2026-03-09T14:30:45Z"
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `sbt "testOnly harvest.HarvestingFromFormatSpec"`
Expected: FAIL — `value formatFromParameter is not a member of object harvest.Harvesting`

- [ ] **Step 3: Add helper to `Harvesting` companion object**

In `app/harvest/Harvesting.scala`, add this method inside `object Harvesting { ... }` block (before the closing brace, around line 163, after `case class HarvestCron`):

```scala
  /**
   * Format a DateTime for the OAI-PMH `from=` query parameter.
   *
   * @param mod      the timestamp from the previous harvest
   * @param justDate when true, returns YYYY-MM-DD (for servers with day-level
   *                 granularity, e.g., Brocade/Anet); when false, returns full
   *                 UTC ISO 8601 (YYYY-MM-DDTHH:MM:SSZ)
   */
  def formatFromParameter(mod: DateTime, justDate: Boolean): String = {
    val dateTime = services.Temporal.timeToUTCString(mod)
    val withoutMillis = dateTime.replaceAll("\\.[0-9]+", "")
    if (justDate) withoutMillis.substring(0, withoutMillis.indexOf('T'))
    else withoutMillis.replaceAll("\\.[0-9]{3}[Z]{0,1}$", "Z")
  }
```

- [ ] **Step 4: Replace inline logic in `fetchPMHPage`**

In `app/harvest/Harvesting.scala`, replace lines 281-286 with:

```scala
          case ModifiedAfter(mod, justDate) =>
            withSet.withQueryString("from" -> Harvesting.formatFromParameter(mod, justDate))
```

- [ ] **Step 5: Run test to verify it passes**

Run: `sbt "testOnly harvest.HarvestingFromFormatSpec"`
Expected: PASS — all 3 tests green.

- [ ] **Step 6: Compile to ensure no other breakage**

Run: `make compile`
Expected: BUILD SUCCESS.

- [ ] **Step 7: Human diff review**

Reference skill: diff-review-before-task-commit
Wait for human acknowledgment before proceeding to commit.

- [ ] **Step 8: Commit**

```bash
git add app/harvest/Harvesting.scala test/harvest/HarvestingFromFormatSpec.scala
git commit -m "refactor: extract formatFromParameter helper from Harvesting.fetchPMHPage"
```

---

## Task 2: Add `harvestDateOnly` NXProp

**Files:**
- Modify: `app/triplestore/GraphProperties.scala` (~line 129)

- [ ] **Step 1: Add the property**

In `app/triplestore/GraphProperties.scala`, after line 129 (`val harvestIncrementalMode = NXProp("harvestIncrementalMode", booleanProp)`), add:

```scala
  val harvestDateOnly = NXProp("harvestDateOnly", booleanProp)
```

- [ ] **Step 2: Compile**

Run: `make compile`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Human diff review**

Reference skill: diff-review-before-task-commit
Wait for human acknowledgment before proceeding to commit.

- [ ] **Step 4: Commit**

```bash
git add app/triplestore/GraphProperties.scala
git commit -m "feat: add harvestDateOnly NXProp for OAI-PMH date-only granularity"
```

---

## Task 3: Register `harvestDateOnly` in DsInfo serialization

**Files:**
- Modify: `app/dataset/DsInfo.scala` (~line 283)
- Modify: `test/dataset/DsInfoSerializationSpec.scala` (line 99)

The frontend reads dataset config via WebSocket updates that serialize from `webSocketFields`. New props must be registered there or the checkbox value will not be visible to the UI on dataset GET. The existing `DsInfoSerializationSpec` enforces this via a `saveableHarvestFields` set.

- [ ] **Step 1: Add the field to the test's expected set (failing test)**

In `test/dataset/DsInfoSerializationSpec.scala`, modify the `saveableHarvestFields` set (line 99) — add `"harvestDateOnly"` to the existing entry containing `harvestDelay`/`harvestDelayUnit`/`harvestIncremental`. Replace lines 99-109 with:

```scala
    val saveableHarvestFields = Set(
      "harvestType", "harvestURL", "harvestDataset", "harvestPrefix",
      "harvestSearch", "harvestRecord", "harvestDownloadURL",
      "harvestContinueOnError", "harvestErrorThreshold",
      "harvestUsername", "harvestPasswordSet", "harvestApiKeySet",
      "harvestJsonItemsPath", "harvestJsonIdPath", "harvestJsonTotalPath",
      "harvestJsonPageParam", "harvestJsonPageSizeParam", "harvestJsonPageSize",
      "harvestJsonDetailPath", "harvestJsonSkipDetail",
      "harvestJsonXmlRoot", "harvestJsonXmlRecord", "harvestApiKeyParam",
      "harvestDelay", "harvestDelayUnit", "harvestIncremental", "harvestIncrementalMode",
      "harvestDateOnly"
    )
```

- [ ] **Step 2: Run test to verify it fails**

Run: `sbt "testOnly dataset.DsInfoSerializationSpec"`
Expected: FAIL — `Saveable harvest fields missing from webSocketFields: Set(harvestDateOnly)`.

- [ ] **Step 3: Register the field in `DsInfo.webSocketFields`**

In `app/dataset/DsInfo.scala`, after line 283 (`BoolField("harvestContinueOnError", harvestContinueOnError),`), add:

```scala
      BoolField("harvestDateOnly", harvestDateOnly),
```

- [ ] **Step 4: Run test to verify it passes**

Run: `sbt "testOnly dataset.DsInfoSerializationSpec"`
Expected: PASS — all tests green.

- [ ] **Step 5: Compile**

Run: `make compile`
Expected: BUILD SUCCESS.

- [ ] **Step 6: Human diff review**

Reference skill: diff-review-before-task-commit
Wait for human acknowledgment before proceeding to commit.

- [ ] **Step 7: Commit**

```bash
git add app/dataset/DsInfo.scala test/dataset/DsInfoSerializationSpec.scala
git commit -m "feat: register harvestDateOnly in DsInfo webSocketFields"
```

---

## Task 4: Replace WEEKS heuristic with `harvestDateOnly` read

**Files:**
- Modify: `app/harvest/PeriodicHarvest.scala` (lines 96-97)

This is the behavioral change: removing the bug heuristic and reading the new property.

- [ ] **Step 1: Read current code**

In `app/harvest/PeriodicHarvest.scala`, lines 96-97 currently read:

```scala
                  val justDate = harvestCron.unit == DelayUnit.WEEKS
                  val strategy = if (harvestCron.incremental) ModifiedAfter(harvestCron.previous, justDate) else FromScratchIncremental
```

- [ ] **Step 2: Verify imports**

The file should already import `triplestore.GraphProperties._` or have an explicit `harvestDateOnly` import. Check the imports at the top of `app/harvest/PeriodicHarvest.scala`. If `GraphProperties._` is not imported, add this import line near the top of the file:

```scala
import triplestore.GraphProperties.harvestDateOnly
```

- [ ] **Step 3: Replace lines 96-97**

`DsInfo.getBooleanProp` is defined at `app/dataset/DsInfo.scala:865-866` and returns plain `Boolean` (false for absent or non-`"true"` values). Replace those two lines with:

```scala
                  val dateOnly = info.getBooleanProp(harvestDateOnly)
                  val strategy = if (harvestCron.incremental) ModifiedAfter(harvestCron.previous, dateOnly) else FromScratchIncremental
```

- [ ] **Step 4: Compile**

Run: `make compile`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Run existing test suite to verify no regressions**

Run: `sbt test`
Expected: All tests PASS. (No new test added — this is a wiring change covered by manual smoke in Task 6.)

- [ ] **Step 6: Human diff review**

Reference skill: diff-review-before-task-commit
Wait for human acknowledgment before proceeding to commit.

- [ ] **Step 7: Commit**

```bash
git add app/harvest/PeriodicHarvest.scala
git commit -m "fix: drive OAI-PMH date-only granularity from harvestDateOnly prop, not cron unit

Removes the WEEKS heuristic that wrongly assumed weekly-cron datasets need
day-level timestamps. The two concerns are orthogonal: server granularity
capability is per-endpoint, cron cadence is per-dataset preference.

Behavior change: weekly-cron datasets now send full timestamps by default.
Operators must enable the new harvestDateOnly checkbox for endpoints with
day-level granularity (e.g., Brocade/Anet)."
```

---

## Task 5: Add UI checkbox + persist field

**Files:**
- Modify: `public/templates/dataset-list.html` (around line 1530, in cron section near incremental radio)
- Modify: `app/assets/javascripts/datasetList/dataset-list-controllers.js` (line 1215)

- [ ] **Step 1: Locate insertion point in template**

Open `public/templates/dataset-list.html`. Find the cron section starting near line 1512 (the `harvestDelay` input). Find the `harvestIncremental` radio block ending around line 1543. The new checkbox goes after that block, before the Save button at line 1552.

- [ ] **Step 2: Add the checkbox**

Insert this block after the closing `</div>` of the `harvestIncremental` form-group (after line 1543, before the Save button):

```html
            <div class="form-group">
                <label class="control-label" for="hc_dateonly">OAI-PMH Date Granularity</label>
                <div class="checkbox">
                    <label>
                        <input type="checkbox" id="hc_dateonly" data-ng-model="dataset.edit.harvestDateOnly" data-ng-true-value="'true'" data-ng-false-value="'false'"/>
                        Send date-only timestamp (<code>YYYY-MM-DD</code>) instead of full ISO timestamp.
                        Required for OAI-PMH endpoints that only support day-level granularity (e.g., Brocade/Anet).
                    </label>
                </div>
            </div>
```

Note: `ng-true-value`/`ng-false-value` are quoted strings because triplestore booleans are persisted as `"true"`/`"false"` strings, matching the pattern of other harvest boolean fields in this template.

- [ ] **Step 3: Add field to controller's `harvestCronFields` array**

In `app/assets/javascripts/datasetList/dataset-list-controllers.js`, find `harvestCronFields` at line 1215. Append `"harvestDateOnly"` to that array. Example: if the current array is

```js
    var harvestCronFields = [
        "harvestDelay", "harvestDelayUnit", "harvestIncremental", "harvestIncrementalMode"
    ];
```

change it to

```js
    var harvestCronFields = [
        "harvestDelay", "harvestDelayUnit", "harvestIncremental", "harvestIncrementalMode",
        "harvestDateOnly"
    ];
```

(If the actual array contents differ, append `"harvestDateOnly"` to whatever is there — do not replace existing entries.)

- [ ] **Step 4: Compile (assets bundled by sbt-web)**

Run: `make compile`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Manual UI smoke test**

Run: `sbt run`

In a browser at `http://localhost:9000`:
1. Navigate to a dataset with OAI-PMH harvest configured
2. Open the harvest cron section
3. Verify the new checkbox appears with the explanatory label
4. Toggle it on, click Save
5. Reload the page → checkbox should still be checked
6. Toggle it off, click Save, reload → checkbox should be unchecked

Stop the dev server with Ctrl+C.

- [ ] **Step 6: Human diff review**

Reference skill: diff-review-before-task-commit
Wait for human acknowledgment before proceeding to commit.

- [ ] **Step 7: Commit**

```bash
git add public/templates/dataset-list.html app/assets/javascripts/datasetList/dataset-list-controllers.js
git commit -m "feat: add harvestDateOnly checkbox to dataset harvest config UI"
```

---

## Task 6: Manual smoke test against Brocade

**Files:** none (verification only)

This is the end-to-end check that the feature solves the original Brocade problem. The agent cannot do this autonomously — flag for the human operator.

- [ ] **Step 1: Identify a Brocade-backed dataset in production or staging**

Look for datasets with `harvestURL` containing `anet.be`. These are the Brocade endpoints that need day-level granularity.

- [ ] **Step 2: Enable `harvestDateOnly` on one such dataset via UI**

Open the dataset config, check the new "Send date-only timestamp" checkbox in the cron section, click Save.

- [ ] **Step 3: Trigger an incremental harvest**

Wait for the next periodic tick, or trigger manually if a manual incremental option exists.

- [ ] **Step 4: Verify the request format in logs**

In Narthex logs, look for the `harvest url:` log line from `Harvesting.fetchPMHPage`. The `from=` parameter should be of the form `from=2026-03-09` (no `T`), not `from=2026-03-09T14:30:45Z`.

- [ ] **Step 5: Verify Brocade responds with records**

The harvest should complete successfully and acquire records (or at least not fail with a granularity-related OAI-PMH error).

- [ ] **Step 6: Regression check on a non-Brocade weekly dataset**

Pick another dataset with `harvestDelayUnit=weeks` and `harvestDateOnly` left unchecked. Trigger an incremental harvest. The `from=` parameter should now contain a full timestamp (`YYYY-MM-DDTHH:MM:SSZ`) — this is the deliberate behavior change from removing the WEEKS heuristic. Confirm the endpoint still accepts full timestamps and returns records.

If any production weekly-cron dataset fails after this regression check because its endpoint actually needed day-only, the operator should manually enable `harvestDateOnly` on that dataset.

---

## Self-Review Checklist (post-write)

- ✅ **Spec coverage:** All 5 file changes from spec map to Tasks 1-5. Spec testing section maps to Task 1 (unit tests for format) + Task 6 (manual smoke).
- ✅ **No placeholders:** Every step has exact code or exact command.
- ✅ **Type consistency:** `formatFromParameter(mod: DateTime, justDate: Boolean): String` — same signature in test, helper definition, and call site. Property name `harvestDateOnly` consistent across NXProp, BoolField, prop read in PeriodicHarvest, JS field array, HTML model. `DsInfo.getBooleanProp(NXProp): Boolean` verified at `app/dataset/DsInfo.scala:865-866`.
