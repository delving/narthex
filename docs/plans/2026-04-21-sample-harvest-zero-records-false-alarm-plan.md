# Sample Harvest "0 Records" False-Alarm Fix — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop the "Sample Harvest: 0 Records" dialog from firing after a successful PMH sample harvest against servers that omit the OAI-PMH `<resumptionToken completeListSize="...">` attribute (e.g., Brocade/Anet).

**Architecture:** Add a `recordCount: Int` field to `PMHHarvestPage` populated from the parsed XML record list during `fetchPMHPage`. The PMH sample handler uses a fallback expression so it reports the actual sampled count when `completeListSize` is absent or zero. No frontend changes.

**Tech Stack:** Scala 2.13, Play Framework 2.8.20, ScalaTest, Joda Time.

**Spec:** `docs/specs/2026-04-21-sample-harvest-zero-records-false-alarm-design.md`

---

## File Structure

| File | Responsibility | Action |
|------|----------------|--------|
| `app/harvest/Harvesting.scala` | OAI-PMH request + response model; `PMHHarvestPage` case class and `fetchPMHPage` constructor call | Modify (case class lines 98-109, constructor call lines 460-470) |
| `app/harvest/Harvester.scala` | PMH sample handler that calls `setAcquisitionCounts` | Modify (lines 743-748) |
| `test/harvest/PMHHarvestPageRecordCountSpec.scala` | New spec asserting `recordCount` population for both resumption-token and no-token cases | Create |

---

## Task 1: Add `recordCount` field to `PMHHarvestPage` + populate in `fetchPMHPage` — tests

**Files:**
- Modify: `app/harvest/Harvesting.scala` (case class lines 98-109; constructor call lines 460-470)
- Create: `test/harvest/PMHHarvestPageRecordCountSpec.scala`

Drive the change TDD: fixture OAI-PMH XML fed through the existing PMH parsing should produce a `PMHHarvestPage` with a truthful `recordCount`. The shortest path to test the parser end-to-end is to exercise `fetchPMHPage` via `play.api.test.MockWS`, but that drags in WS setup. Simpler: extract the record-counting into the same place where the XML is already parsed (line 437) and write an assertion against the final `PMHHarvestPage` via an integration-style test that constructs the class directly.

This plan avoids the MockWS rabbit hole by asserting `PMHHarvestPage.recordCount` construction in two ways:

1. Direct construction with the new field (confirms the case class grew the field).
2. A string-level parser helper exercised on two fixture XMLs: one with resumption token, one without. Copy the record-count expression into the test to document the expected parse.

### Step 1: Write the failing test

Create `test/harvest/PMHHarvestPageRecordCountSpec.scala`:

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

import scala.xml.XML
import org.scalatest.flatspec._
import org.scalatest.matchers._

import harvest.Harvesting.PMHHarvestPage

class PMHHarvestPageRecordCountSpec extends AnyFlatSpec with should.Matchers {

  private val pageWithoutResumption =
    <OAI-PMH>
      <ListRecords>
        <record><header><identifier>id-1</identifier></header></record>
        <record><header><identifier>id-2</identifier></header></record>
        <record><header><identifier>id-3</identifier></header></record>
        <record><header><identifier>id-4</identifier></header></record>
        <record><header><identifier>id-5</identifier></header></record>
      </ListRecords>
    </OAI-PMH>

  private val pageWithResumption =
    <OAI-PMH>
      <ListRecords>
        <record><header><identifier>id-1</identifier></header></record>
        <record><header><identifier>id-2</identifier></header></record>
        <record><header><identifier>id-3</identifier></header></record>
        <record><header><identifier>id-4</identifier></header></record>
        <record><header><identifier>id-5</identifier></header></record>
        <resumptionToken completeListSize="100" cursor="1">opaque</resumptionToken>
      </ListRecords>
    </OAI-PMH>

  "PMHHarvestPage" should "accept a recordCount field that reflects the actual record count" in {
    val page = PMHHarvestPage(
      records = pageWithoutResumption.toString,
      url = "https://example.org/oai",
      set = "",
      metadataPrefix = "edm",
      totalRecords = 0,
      strategy = dataset.DatasetActor.Sample,
      resumptionToken = None,
      deletedIds = List.empty,
      deletedCount = 0,
      recordCount = (pageWithoutResumption \ "ListRecords" \ "record").size
    )
    page.recordCount shouldBe 5
    page.totalRecords shouldBe 0
  }

  it should "accept a PMHHarvestPage where totalRecords reflects completeListSize and recordCount is the page size" in {
    val page = PMHHarvestPage(
      records = pageWithResumption.toString,
      url = "https://example.org/oai",
      set = "",
      metadataPrefix = "edm",
      totalRecords = 100,
      strategy = dataset.DatasetActor.Sample,
      resumptionToken = None,
      deletedIds = List.empty,
      deletedCount = 0,
      recordCount = (pageWithResumption \ "ListRecords" \ "record").size
    )
    page.recordCount shouldBe 5
    page.totalRecords shouldBe 100
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JDK_JAVA_OPTIONS="" sbt "testOnly harvest.PMHHarvestPageRecordCountSpec"`
Expected: FAIL with a compile error — something like `unknown parameter name: recordCount` on the `PMHHarvestPage` constructor call (or `too many arguments for PMHHarvestPage`).

- [ ] **Step 3: Add the field to `PMHHarvestPage`**

In `app/harvest/Harvesting.scala`, lines 98-109 currently read:

```scala
  case class PMHHarvestPage
  (
    records: String,
    url: String,
    set: String,
    metadataPrefix: String,
    totalRecords: Int,
    strategy: HarvestStrategy,
    resumptionToken: Option[PMHResumptionToken],
    deletedIds: List[String] = List.empty,    // Record IDs with status="deleted"
    deletedCount: Int = 0                     // Count of deleted records in this page
  )
```

Replace with:

```scala
  case class PMHHarvestPage
  (
    records: String,
    url: String,
    set: String,
    metadataPrefix: String,
    totalRecords: Int,
    strategy: HarvestStrategy,
    resumptionToken: Option[PMHResumptionToken],
    deletedIds: List[String] = List.empty,    // Record IDs with status="deleted"
    deletedCount: Int = 0,                    // Count of deleted records in this page
    recordCount: Int = 0                      // Actual number of <record> elements on this page
  )
```

- [ ] **Step 4: Populate `recordCount` in `fetchPMHPage`**

In `app/harvest/Harvesting.scala`, lines 460-470 currently construct the `PMHHarvestPage`:

```scala
            val harvestPage = PMHHarvestPage(
              records = xml.toString(),
              url = resolvedUrl,
              set = set,
              metadataPrefix = metadataPrefix,
              totalRecords = total,
              strategy,
              resumptionToken = newToken,
              deletedIds = deletedIds,
              deletedCount = deletedIds.size
```

Replace the closing parenthesis on the `deletedCount` line with a comma and add `recordCount = allRecords.size`:

```scala
            val harvestPage = PMHHarvestPage(
              records = xml.toString(),
              url = resolvedUrl,
              set = set,
              metadataPrefix = metadataPrefix,
              totalRecords = total,
              strategy,
              resumptionToken = newToken,
              deletedIds = deletedIds,
              deletedCount = deletedIds.size,
              recordCount = allRecords.size
```

`allRecords` is already defined at line 437: `val allRecords = xml \ "ListRecords" \ "record"`.

- [ ] **Step 5: Run test to verify it passes**

Run: `JDK_JAVA_OPTIONS="" sbt "testOnly harvest.PMHHarvestPageRecordCountSpec"`
Expected: PASS — both tests green.

- [ ] **Step 6: Compile full build to catch any other call sites**

Run: `JDK_JAVA_OPTIONS="" make compile`
Expected: BUILD SUCCESS. If any other file constructs `PMHHarvestPage` positionally (not by name), a compile error will surface; the new field has a default so this should not happen.

- [ ] **Step 7: Human diff review**

Reference skill: diff-review-before-task-commit
Wait for human acknowledgment before proceeding to commit.

- [ ] **Step 8: Commit**

```bash
git add app/harvest/Harvesting.scala test/harvest/PMHHarvestPageRecordCountSpec.scala
git commit -m "feat: add PMHHarvestPage.recordCount derived from parsed XML"
```

---

## Task 2: Use the fallback in the PMH sample handler

**Files:**
- Modify: `app/harvest/Harvester.scala` (lines 743-748)

The PMH sample handler currently passes `page.totalRecords` to `setAcquisitionCounts`. Replace with a fallback that prefers `totalRecords` (the catalog total from `completeListSize`) but uses the actual `recordCount` when `totalRecords` is zero.

- [ ] **Step 1: Read current code**

In `app/harvest/Harvester.scala`, lines 743-748 currently read:

```scala
              FileUtils.writeStringToFile(rawXml, page.records, "UTF-8")
              datasetContext.dsInfo.setAcquisitionCounts(
                acquired = page.totalRecords,
                deleted = 0,
                source = page.totalRecords,
                method = "pmh"
              )
```

- [ ] **Step 2: Replace with fallback expression**

Replace those lines with:

```scala
              FileUtils.writeStringToFile(rawXml, page.records, "UTF-8")
              val sampled = if (page.totalRecords > 0) page.totalRecords else page.recordCount
              datasetContext.dsInfo.setAcquisitionCounts(
                acquired = sampled,
                deleted = 0,
                source = sampled,
                method = "pmh"
              )
```

- [ ] **Step 3: Compile**

Run: `JDK_JAVA_OPTIONS="" make compile`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Run full harvest + dataset tests to catch regressions**

Run: `JDK_JAVA_OPTIONS="" sbt "testOnly harvest.* dataset.*"`
Expected: all tests PASS, including the new `PMHHarvestPageRecordCountSpec` and the existing `HarvestingFromFormatSpec` / `DsInfoSerializationSpec`.

- [ ] **Step 5: Human diff review**

Reference skill: diff-review-before-task-commit
Wait for human acknowledgment before proceeding to commit.

- [ ] **Step 6: Commit**

```bash
git add app/harvest/Harvester.scala
git commit -m "$(cat <<'EOF'
fix: use actual record count when OAI-PMH server omits completeListSize

PMH sample writes setAcquisitionCounts(acquired = page.totalRecords).
When the server returns no resumption token (sample response fits in
one page, as with Brocade/Anet), totalRecords defaults to 0 and the
frontend dialog misidentifies the successful sample as an empty result.

Fall back to the actual XML record count when totalRecords is zero.
Preserves catalog-total semantics when completeListSize is present.
EOF
)"
```

---

## Task 3: Manual smoke test (human only)

**Files:** none (verification only)

- [ ] **Step 1: Run the app locally**

Run: `JDK_JAVA_OPTIONS="" make run`
Wait for "Play - Application started (Dev)" log line.

- [ ] **Step 2: Trigger a sample harvest on the Brocade dataset**

Navigate to `http://localhost:9000/narthex/#/?dataset=brocade-cat-rsl` in a browser. Click the sample-harvest action (the page offers it alongside other harvest actions).

- [ ] **Step 3: Verify no false-alarm dialog**

After the sample completes, the "Sample Harvest: 0 Records" confirm dialog should NOT appear. The dataset detail panel should show a non-zero `acquiredRecordCount` that matches the number of records actually returned by the Brocade endpoint.

- [ ] **Step 4: Regression — sample against an endpoint with zero results**

Pick (or temporarily configure) a dataset whose OAI-PMH endpoint returns no records for the sample verb. Trigger the sample. The dialog should fire — this is the legitimate signal, preserved by this change.

If the regression case fails (dialog does not fire for genuinely empty results), stop. The XML record count fallback is firing when it should not.

---

## Self-Review Checklist (post-write)

- ✅ **Spec coverage:** All three file changes from the spec map to Tasks 1-2. Spec testing section (unit + manual smoke) maps to Task 1 (TDD) + Task 3 (manual smoke). Scope intentionally excludes Fix 2 per revised spec.
- ✅ **No placeholders:** Every step has exact code or exact command.
- ✅ **Type consistency:** `PMHHarvestPage.recordCount: Int` defined in Task 1, referenced as `page.recordCount` in Task 2. `allRecords.size` is the same value populating `recordCount` that drives the test assertions. `setAcquisitionCounts` signature matches the existing DsInfo method.
