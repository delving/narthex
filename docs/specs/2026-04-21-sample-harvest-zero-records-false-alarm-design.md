# Sample Harvest "0 Records" False-Alarm Dialog

**Status:** Designed, not yet implemented
**Date:** 2026-04-21
**Branch:** `fix/sample-harvest-zero-records-false-alarm`

## Problem

The dataset list UI shows a "Sample Harvest: 0 Records" confirmation dialog
that asks the operator whether to reset record counts to zero. The dialog
fires when, in fact, the harvest succeeded with data. Two distinct triggers,
same root cause (frontend infers "harvest empty" from incomplete state):

### Trigger 1 — Sample harvest on any dataset

`Harvester.scala:743-748` (PMH sample handler) writes
`setAcquisitionCounts(acquired = page.totalRecords, ...)`. The
`totalRecords` value comes from the OAI-PMH `<resumptionToken
completeListSize="...">` attribute. When the sample response fits in one page
and the server omits the resumption token (Brocade/Anet on small samples),
`Harvesting.scala:420-423` defaults `total = 0`. Sample writes
`acquiredRecordCount = 0` even though the response contained records.

The frontend dialog at `dataset-list-controllers.js:204-208` checks
`acquiredRecordCount === 0` (among others) and fires a confirm dialog.

### Trigger 2 — First-ever full harvest on a fresh dataset

State changes propagate through WebSocket as separate frames:

1. Harvest starts → `currentOperation = "HARVESTING"`, `stateRaw` set → frame 1
2. Records ingest → `acquiredRecordCount` written → frame 2
3. Harvest completes → `currentOperation` cleared, `stateSourced` set → frame 3

For a never-sourced dataset, frame 1 hits the frontend with
`previousStateRaw = null`, `acquiredRecordCount = 0`, `stateSourced = null`
— all three current dialog conditions are transiently true → dialog fires
mid-harvest, then later frames clear the conditions but the dialog already
opened.

## Goal

Stop the false-alarm dialog without removing the legitimate signal (sample
that genuinely returned zero records). Two complementary fixes addressing
the two triggers.

## Non-goals

- AdLib sample (already correct via `diagnostic.totalItems`)
- JSON harvest (no sample path)
- Restructuring the WebSocket message protocol
- Changing the dialog text or its "reset counts to 0" callback

## Approach

### Fix 1 — Backend: derive accurate sample count

Add `recordCount: Int` field to `PMHHarvestPage`, populated from the parsed
XML record list during `fetchPMHPage`. Symmetric with the existing
`deletedCount` field, single source of truth for "how many records did this
page actually contain."

The PMH sample handler picks the count via fallback:

```scala
val sampled = if (page.totalRecords > 0) page.totalRecords else page.recordCount
```

`completeListSize` (when present) is preferred because it reflects the
catalog total, useful for progress display elsewhere. The XML record count
is the truthful fallback when `completeListSize` is absent or zero.

### Fix 2 — Frontend: gate dialog on operation completion

Add a fourth condition to the dialog check at
`dataset-list-controllers.js:204-208`:

```js
var noOperationInProgress = !existingDataset.currentOperation;
```

Dialog only fires when an operation has fully completed (no
`currentOperation` set). Eliminates the first-full-harvest race because
mid-harvest WebSocket frames carry `currentOperation = "HARVESTING"` and
fail the new gate.

The fix preserves the legitimate trigger: when sample genuinely returns zero
records, the `noRecordsMatch` path in `Harvester.scala:750-752` does not
write counts; `DatasetActor.scala:889-893` sets state RAW (Sample bypasses
the workflow state machine, so `currentOperation` is not set during sample);
all four conditions are true → dialog fires correctly.

## File changes

| File | Change |
|---|---|
| `app/harvest/Harvesting.scala` (`PMHHarvestPage` ~line 98-109) | Add `recordCount: Int = 0` field |
| `app/harvest/Harvesting.scala` (`fetchPMHPage` ~line 449) | Set `recordCount = allRecords.size` from already-parsed `xml \ "ListRecords" \ "record"` |
| `app/harvest/Harvester.scala:743-748` | Compute `val sampled = if (page.totalRecords > 0) page.totalRecords else page.recordCount`; pass `sampled` as both `acquired` and `source` to `setAcquisitionCounts` |
| `app/assets/javascripts/datasetList/dataset-list-controllers.js:204-208` | Add `var noOperationInProgress = !existingDataset.currentOperation;` and append `&& noOperationInProgress` to the `if` condition |

## Data flow

### Sample with data (Brocade scenario, currently broken)

1. User triggers sample → `Harvester` issues `fetchPMHPage`
2. `fetchPMHPage` parses 5 records, no resumption token →
   `PMHHarvestPage(records, ..., totalRecords = 0, ..., recordCount = 5)`
3. PMH sample handler computes `sampled = 5`, writes
   `setAcquisitionCounts(5, 0, 5, "pmh")`
4. `DatasetActor` Sample case sets state RAW (unchanged)
5. WebSocket frame: `stateRaw = set, acquiredRecordCount = 5,
   currentOperation = null`
6. Frontend: `noRecordsAcquired = false` → dialog skipped ✅

### First full harvest on a fresh dataset (currently broken)

1. Harvest starts → `currentOperation = "HARVESTING"`, `stateRaw` set →
   WebSocket frame 1
2. Frontend: `noOperationInProgress = false` → dialog skipped ✅
3. Records ingest → counts set → frame 2; still mid-harvest
4. Harvest completes → `currentOperation` cleared, `stateSourced` set →
   frame 3
5. Frontend: `notSourced = false` → dialog skipped ✅

### Sample with truly 0 records (legitimate signal, must still fire)

1. `noRecordsMatch` path runs (`Harvester.scala:750-752`); no count call
2. `DatasetActor` Sample case sets state RAW
3. WebSocket frame: `stateRaw = set, acquiredRecordCount = 0,
   stateSourced = null, currentOperation = null`
4. All four conditions true → dialog fires correctly ✅

## Error handling

- XML parse failures still propagate through the existing `Try` wrappers in
  `fetchPMHPage`; no new error paths.
- `recordCount` defaults to 0 — same as today's behavior for any caller that
  does not explicitly read it. Backward-compatible field addition.
- If `currentOperation` is `undefined` on a stale dataset object,
  `!undefined === true` → dialog can fire if the other conditions match.
  Matches today's behavior (no regression).

## Testing

### Unit (Scala)

- `HarvestingSpec` (new or extended): fixture OAI-PMH XML with 5 records and
  no resumption token → assert `PMHHarvestPage.recordCount == 5,
  totalRecords == 0`.
- Same fixture with `<resumptionToken completeListSize="100">` → assert
  `totalRecords == 100, recordCount == 5`.

### Manual smoke

- Sample harvest on `brocade-cat-rsl` → no dialog, dataset detail panel
  shows non-zero record count after sample completes.
- First-ever full harvest on a fresh test dataset → no transient dialog
  during harvest progression.
- Regression: sample on a fresh test dataset against an OAI-PMH endpoint
  that returns zero records → dialog still fires (correct behavior).
