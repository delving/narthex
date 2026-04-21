# Sample Harvest "0 Records" False-Alarm Dialog

**Status:** Designed, not yet implemented
**Date:** 2026-04-21
**Branch:** `fix/sample-harvest-zero-records-false-alarm`

## Problem

The dataset list UI shows a "Sample Harvest: 0 Records" confirmation dialog
asking the operator whether to reset record counts to zero. The dialog fires
even when the sample harvest succeeded with data.

`Harvester.scala:743-748` (PMH sample handler) writes
`setAcquisitionCounts(acquired = page.totalRecords, ...)`. The
`totalRecords` value comes from the OAI-PMH `<resumptionToken
completeListSize="...">` attribute. When the sample response fits in one
page and the server omits the resumption token (Brocade/Anet on small
samples), `Harvesting.scala:431-434` defaults `total = 0`. Sample writes
`acquiredRecordCount = 0` even when records were returned.

The frontend dialog at `dataset-list-controllers.js:204-208` checks
`acquiredRecordCount === 0` (among other conditions) and fires a confirm
dialog. Since sample succeeded but the count is wrong, the dialog
mischaracterises a successful sample as an empty result.

## Goal

Stop the false-alarm dialog without removing the legitimate signal — sample
that genuinely returned zero records.

## Non-goals

- AdLib sample (already correct via `diagnostic.totalItems`)
- JSON harvest (no sample path)
- Frontend dialog logic itself (correct behaviour given correct data)
- The "reset counts" command path or any other `setState(RAW)` site
- A separate, unverified report of the dialog firing on "manual reharvest"
  — `setState(RAW)` is not called by the FromScratch full-harvest path, so
  that report cannot be triggered by FromScratch directly. If it reproduces
  after this fix, investigate separately.

## Approach

Add `recordCount: Int` field to `PMHHarvestPage`, populated from the parsed
XML record list during `fetchPMHPage`. Symmetric with the existing
`deletedCount` field, and the right single source of truth for "how many
records did this page actually contain."

The PMH sample handler picks the count via fallback:

```scala
val sampled = if (page.totalRecords > 0) page.totalRecords else page.recordCount
```

`completeListSize` (when present) is preferred because it reflects the
catalog total, useful for progress display elsewhere. The XML record count
is the truthful fallback when `completeListSize` is absent or zero.

## File changes

| File | Change |
|---|---|
| `app/harvest/Harvesting.scala` (`PMHHarvestPage` lines 98-109) | Add `recordCount: Int = 0` field |
| `app/harvest/Harvesting.scala` (`fetchPMHPage` constructor call lines 460-470) | Set `recordCount = allRecords.size` from the already-parsed value at line 437 |
| `app/harvest/Harvester.scala:743-748` | Compute `val sampled = if (page.totalRecords > 0) page.totalRecords else page.recordCount`; pass `sampled` as both `acquired` and `source` to `setAcquisitionCounts` |

## Data flow

### Sample with data (Brocade scenario, currently broken)

1. User triggers sample → `Harvester` issues `fetchPMHPage`
2. `fetchPMHPage` parses 5 records, no resumption token →
   `PMHHarvestPage(records, ..., totalRecords = 0, ..., recordCount = 5)`
3. PMH sample handler computes `sampled = 5`, writes
   `setAcquisitionCounts(5, 0, 5, "pmh")`
4. `DatasetActor` Sample case sets state RAW (unchanged)
5. `WebSocketIdleBroadcast` fires after `clearOperation`. Frame carries
   `stateRaw = set, acquiredRecordCount = 5, currentOperation = null`
6. Frontend: `noRecordsAcquired = false` → dialog skipped ✅

### Sample with truly 0 records (legitimate signal, must still fire)

1. `noRecordsMatch` path runs (`Harvester.scala:750-752`); no count call
2. `DatasetActor` Sample case sets state RAW
3. WebSocket frame: `stateRaw = set, acquiredRecordCount = 0,
   stateSourced = null, currentOperation = null`
4. All three conditions true → dialog fires correctly ✅

## Error handling

- XML parse failures still propagate through the existing `Try` wrappers in
  `fetchPMHPage`; no new error paths.
- `recordCount` defaults to 0 — same as today's behavior for any caller that
  does not explicitly read it. Backward-compatible field addition.

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
- Regression: sample on a fresh test dataset against an OAI-PMH endpoint
  that returns zero records → dialog still fires (correct behavior).
- Operator reports of the dialog firing on "manual reharvest" should be
  re-verified after this fix lands. If it still reproduces, capture the
  WebSocket frame at the moment of firing (the broadcast carries
  `stateRaw`, `stateSourced`, `acquiredRecordCount`, `currentOperation`)
  and identify which state path triggered RAW (Sample? upload? "reset
  counts"? retry?). Address as a separate spec.
