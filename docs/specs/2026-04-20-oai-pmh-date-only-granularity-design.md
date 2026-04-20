# OAI-PMH Date-Only Granularity per Dataset

**Status:** Designed, not yet implemented
**Date:** 2026-04-20
**Related:** Planio DataHub #2553 (Brocade harvest error), #2768 (Brocade authorities harvest stops)

## Problem

Some OAI-PMH servers — notably Brocade at `anet.be/oai/lvd/` — only support
date-level granularity (`YYYY-MM-DD`) for the `from=` parameter on
`ListRecords`. Sending a full ISO 8601 timestamp
(`YYYY-MM-DDTHH:MM:SSZ`) returns an error or empty result. The OAI-PMH spec
requires servers to declare their supported granularity in their `Identify`
response (`<granularity>YYYY-MM-DD</granularity>` vs full timestamp).

Narthex currently determines the `from` format via a heuristic that conflates
two unrelated concerns: harvest-cron frequency and server timestamp
granularity. At `app/harvest/PeriodicHarvest.scala:96`:

```scala
val justDate = harvestCron.unit == DelayUnit.WEEKS
```

A weekly-cron dataset always sends day-only timestamps; any other cadence
always sends full timestamps. The heuristic is wrong: cadence and server
capability are orthogonal.

## Goal

Add an explicit per-dataset boolean controlling whether the OAI-PMH `from=`
query parameter is sent as date-only (`YYYY-MM-DD`) or full timestamp
(`YYYY-MM-DDTHH:MM:SSZ`). Remove the WEEKS heuristic. Default off — full
timestamp — matching what most OAI-PMH endpoints expect.

## Non-goals

- AdLib harvesting: ignores `justDate` already (different format)
- JSON harvesting: no date-based strategy
- `until=` parameter: not currently sent; not adding
- Internal `harvestPreviousTime` storage format: unchanged (full precision)
- Auto-detection via `Identify` response: out of scope for this iteration

## Approach

Standalone NXProp `harvestDateOnly` (boolean), persisted as RDF triple per
dataset. Read at the single OAI-PMH strategy construction site
(`PeriodicHarvest.scala:97`). Wires through the existing unchanged `justDate`
flag on `ModifiedAfter` into the existing format branch in `Harvesting.scala`.

The `HarvestCron` case class is **not** modified. Date-only is a transmission
concern, not a schedule concern, and bundling them would conflate domains.

## File changes

| File | Change |
|------|--------|
| `app/triplestore/GraphProperties.scala` | Add `val harvestDateOnly = NXProp("harvestDateOnly", booleanProp)` next to other harvest props (~line 129) |
| `app/harvest/PeriodicHarvest.scala:96-97` | Replace WEEKS heuristic with prop read: `val dateOnly = info.getBooleanProp(harvestDateOnly).getOrElse(false); val strategy = if (harvestCron.incremental) ModifiedAfter(harvestCron.previous, dateOnly) else FromScratchIncremental` |
| `app/dataset/DsInfo.scala` (~line 283, near other `BoolField` harvest entries) | Add `BoolField("harvestDateOnly", harvestDateOnly)` to the `fieldSpecs` list so the UI sees the value on dataset GET |
| `public/templates/dataset-list.html` (~line 1530, near incremental radio) | Add checkbox: `<input type="checkbox" data-ng-model="dataset.edit.harvestDateOnly">` with label "Send date-only timestamp (`YYYY-MM-DD`) — required for servers with day-level OAI-PMH granularity" |
| `app/assets/javascripts/datasetList/dataset-list-controllers.js:1215` | Add `"harvestDateOnly"` to `harvestCronFields` array so save button persists it |

No change to `Harvesting.scala` — the `justDate` branch is already wired.

## Data flow

1. User opens dataset config in UI → existing dataset GET returns
   `harvestDateOnly` (default `false` if absent).
2. User toggles checkbox → clicks Save on the cron section → POST sets the
   `harvestDateOnly` triple alongside other cron fields.
3. Periodic scheduler fires on interval → for each dataset due → reads
   `harvestDateOnly` from triplestore via `getBooleanProp`.
4. Builds `ModifiedAfter(harvestCron.previous, dateOnly)` strategy → kicks
   off harvest.
5. `Harvesting.fetchPMHPage` matches `ModifiedAfter(mod, justDate)` → formats
   `from=` accordingly → sends OAI-PMH request.

## Error handling

- Missing `harvestDateOnly` triple → `getBooleanProp.getOrElse(false)` →
  full timestamp sent.
- Malformed value (cannot occur via UI) → `booleanProp` parser returns None
  → defaults to false.
- No new error paths. The setting only affects query string format; OAI-PMH
  server response handling is unchanged.

## Behavioral change for existing datasets

Removing the WEEKS heuristic means any existing weekly-cron dataset will
start sending full timestamps after deployment. Per project owner, this
matches the desired default — weekly cron should send timestamps anyway. No
migration is needed. Datasets that genuinely need day-only (Brocade)
must have the checkbox enabled by an operator.

## Testing

- **Unit:** `Harvesting.fetchPMHPage` request construction with
  `ModifiedAfter(t, true)` → asserts `from=YYYY-MM-DD` (no `T`).
- **Unit:** same with `ModifiedAfter(t, false)` → asserts
  `from=YYYY-MM-DDTHH:MM:SSZ`.
- **Integration:** dataset with `harvestDateOnly=true` + WEEKS cron → still
  day-only (heuristic gone, prop drives it).
- **Integration:** dataset with `harvestDateOnly=false` + WEEKS cron → full
  timestamp (regression check — would have been day-only before).
- **Manual smoke:** weekly Brocade dataset with checkbox enabled →
  `https://anet.be/oai/lvd/?verb=ListRecords&metadataPrefix=catxml&set=cxlib:RUB&from=YYYY-MM-DD`
  returns records.
