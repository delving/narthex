# Trends: true added/removed deltas — review findings and plan

Date: 2026-07-02
Status: proposed
Prior work: 2026-02-19 redesign, 2026-04-21 fixes, 2026-05-04 lag-and-search fixes

## Requirement

"The user expects that they can understand the changes in records — added,
removed — both in the day and over time."

## Root-cause verdict

Two independent problem layers:

1. **Mechanical bugs** in the snapshot pipeline make even the *net* numbers
   wrong or inconsistent (duplicate/out-of-order daily rows, one-day indexed
   lag, gap days stretching the "24h" window, datasets dropped from org
   totals). Fixable within the current design.
2. **A fundamental semantic limit**: trends stores end-of-day totals and
   deltas are subtraction of totals. "Added" and "removed" are not derivable
   from totals — +100/−100 in one day nets to 0 and shows as "stable". Three
   plan iterations fixed capture/timing bugs but could never fix this. The
   new RecordRegistry (per-record ground truth per run) is the missing event
   source.

## Mechanical findings (current pipeline)

Severity-ordered; file references verified 2026-07-02 on main.

1. **HIGH — `aggregateDay` appends per call, never upserts**
   (TrendTrackingService.scala:751-805; callers DatasetActor:1199,
   OrgContext:171, AppController:378, bootstrap). N saves per day = N rows
   for the same date with fragmented intra-day deltas; the 00:30 cron can
   append *yesterday's* row after today's rows → `summaries.last` is
   yesterday: UI "Current" counts regress and delta24h computes from the
   wrong row. Charts slice rows, not days (`slice(-7)`, `takeRight(30)`),
   so "7 days" of a busy dataset shows mostly today with duplicate x labels.
2. **HIGH — indexed reconciliation lands on D+1**
   (OrgContext.scala:153-171 + aggregateDay date filter). Cron snapshot is
   timestamped 00:30 of D+1, so day D's summary never gets the reconciled
   indexed count. Customers systematically see "source +5355, indexed +0"
   on harvest day and "source +0, indexed +5355" the next day.
3. **HIGH — gap days stretch windows** (TrendTrackingService.scala:819;
   OrgContext.scala:139). Baseline = newest row ≤ cutoff; a missed cron
   night (Hub3 unreachable aborts aggregation for ALL datasets; no backfill)
   makes "24 Hours" actually span 2+ days.
4. **HIGH — org totals silently drop datasets** (AppController.scala:276-310).
   Datasets without trend files are excluded (totals disagree with dataset
   list); deleted datasets vanish with no negative delta ("total went down
   50k, net change says +12").
5. **HIGH (latent) — Hub3 facet `getOrElse(spec, 0)`**
   (IndexStatsService.scala:109-110). Spec absent from facet (limit 1000,
   rename) → nightly reconciliation writes indexed=0 → fake "−22361".
6. **MEDIUM — "24 Hours" number vs chart use different data**
   (trends-controllers.js:319-333 vs service :850/:871-874). Column is a
   day-boundary diff; chart is last-48h raw event snapshots *excluding*
   `"daily"` reconciliation snapshots (where indexed moves); falls back to
   3 daily rows silently.
7. **MEDIUM — short history: 24h = 7d = 30d** (service :819 `orElse
   headOption`); single-row datasets show "-" in all windows and land in
   "Stable" right after a 10k-record first harvest (`initializing` flag only
   covers zero rows).
8. **MEDIUM — UTC calendar-day semantics**: "24h" resets at 01:00/02:00
   Amsterdam; a 23:00 UTC harvest disappears from the window an hour later.
9. **MEDIUM — JS re-categorization ignores `valid` deltas**
   (trends-controllers.js:82-88), making backend categorization dead code;
   valid-count movement is invisible (no column).
10. **MEDIUM — "Current" differs between org list and detail view**
    (service :844-849 uses `dailySummaries.last`; detail uses
    `getLastSnapshot(trendsLog)`).
11. **MEDIUM — `formatDelta(0)` renders "-"** — confirmed "+0" is
    indistinguishable from "no data"/initializing.
12. **MEDIUM — manual snapshot trigger attributes indexed to today** while
    the cron attributes to D+1 → day comparisons operator-dependent.
13. **LOW — `cleanupOldSnapshots` never called**; `trends.jsonl` grows
    unbounded and is fully re-read on every save and detail call.
14. **LOW — stable-tab empty state text inverted**; header "Net Change" is
    indexed-only while tabs bucket on source+indexed.

## Fundamental gaps (unanswerable from snapshots, in principle)

| Question | Answerable now? |
|---|---|
| How many records were added today? | No — only net |
| How many were removed today? | No — `deletedRecords` stored but never diffed, and it's a cumulative counter |
| Same-day add+remove churn | No — nets to 0, dataset shows "stable" |
| Records changed in place | No — totals don't move |
| Per-run attribution (2 harvests in a day) | No — one row per day |
| Failed run vs no run | No — failed saves capture nothing |
| Records pending Hub3 sync | No — only reconciled totals |

RecordRegistry (merged 2026-07-02, `narthex.registry.enabled` default false)
answers all of these per record: `records` (local_id, content_hash, status,
run ids, timestamps) + `harvest_runs` (kind, status, started/completed).

## Plan

### Phase 1 — fix the net numbers + honest labels (no registry needed, ~1-2 days)

1. `aggregateDay` upserts by date (rewrite `trends-daily.jsonl` keeping one
   row per date, sorted). Fixes findings 1, and de-risks 10.
2. Date-aware chart windows (7/30 distinct days, not rows).
3. Reconciliation snapshot timestamped for the day being reconciled (or
   aggregateDay(D) includes reconciliation rows ≤ D+1 00:30). Fixes 2, 12.
4. Backfill: cron aggregates every missing date since last row
   (carry-forward), per dataset, not just yesterday. Fixes 3.
5. Org summary: include datasets without trend files (as untracked), keep a
   tombstone row when a dataset is deleted so totals move visibly. Fixes 4.
6. Facet-miss guard: `reachable=true` + spec absent from facet → skip that
   spec's reconciliation, log; never write 0. Fixes 5.
7. UI honesty: label "Net change" + tooltip ("adds and removes on the same
   day can cancel out"); render "+0" for confirmed no-change; align JS
   categorization with backend (include valid) or delete one of the two;
   fix stable-tab text. Fixes 7, 9, 11, 14 partially.

### Phase 2 — registry-backed true deltas (behind flag, ~2-3 days)

1. Registry schema v2: `records.first_seen_run_id`, `records.last_changed_run_id`
   (+ indexes), `harvest_runs.added_count`; v1→v2 ALTER steps in `migrate()`
   before the fail-loud version check.
2. Real per-run diffs at `completeRun` (shared `computeRunDiff(runId)`):
   added = first_seen this run; changed = hash changed this run and not new;
   deleted = tombstoned this run. Replaces the current whole-table
   `changed = seen` placeholder — hard prerequisite before any UI exposure.
3. Read API: `listRuns(spec, sinceDays)`, `dailyRunDiffs(spec, sinceDays)`.
4. AppController: additive JSON (`runs`, `dailyDiffs`, `registry: true`)
   when flag on; org endpoint sums per day.
5. Baseline handling: a full run into an empty records table is a
   "baseline import: N records", never "+N added" (prevents every dataset
   showing +90k the day the flag turns on).

### Phase 3 — UI (~2 days)

1. Daily view: per-run rows — `14:32 incremental — +12 added, ~3 changed,
   −1 removed (completed)`; failed runs shown red, not absent.
2. Over time: added-vs-removed bars per day/week from run diffs; keep the
   totals line from snapshots alongside, each labeled by source.
3. Pending-sync indicator: "N records pending Hub3 sync" from registry
   pending counts — explains indexed lag to the user directly.
4. Graceful degradation: `registry: false` → Phase 1 net-change display.

## Risks

- Never wire current `harvest_runs` counts to UI (changed==seen aggregate).
- Registry gross counts vs snapshot totals will disagree during Hub3 sync
  lag — label sources, show pending-sync count.
- Registry history starts at flag-enable date; snapshots remain the only
  pre-enablement history.
- Registry "removed" = tombstoned in Narthex; Hub3 drop is async.
- Phase 1 upsert changes `trends-daily.jsonl` semantics — one-time
  normalization pass over existing files (dedupe by date, keep last row).
