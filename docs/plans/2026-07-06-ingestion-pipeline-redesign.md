# Ingestion Pipeline Redesign — diagnosis, target architecture, Go migration path

Date: 2026-07-06
Status: proposed
Inputs: as-is architecture audit, bug-history root-cause taxonomy (72 buglog
entries + 15 substantive fix commits), target-design study. Full agent
reports archived in session; this doc is the synthesis.

## 1. Diagnosis — the bugs are structural, confirmed

~20 substantive bugs from the last months reduce to **five missing
architectural properties**. The strongest evidence: trends was
redesigned/fixed four times before the underlying substrate was named as the
problem, and the incremental-harvest path was dead code (every incremental
silently degraded to a full 91k resend) because a continuation flag was
matched and then discarded.

The single business fact *"this run is a full/incremental sync of source X
with mapping Y, currently at stage Z"* has **no durable home**. It is
smeared across:

- a Fuseki timestamp race (`getState()` = whichever of 13 state props has
  the newest timestamp — DsInfo.scala:1046),
- four mutable actor booleans (`fastSaveScheduledOpt`,
  `fastSaveAfterProcessing`, `fastProcessOnly`,
  `autoProcessAfterFirstHarvest`) set in one handler, consumed in another,
  lost on restart,
- an Option-carrying message (`Scheduled(modifiedAfter, file)`) whose `file`
  field *changes meaning mid-flight* (source zip before processing,
  processed output after),
- a SQLite `kind` column, a cron string prop, and string-typed commands
  parsed with `startsWith`.

"Full vs incremental" is re-derived independently at **six+ places**
(PeriodicHarvest:97, DatasetActor HarvestComplete, DatasetActor Generating,
SourceProcessor:217, GraphSaver:216, GraphSaver adoption). Fifteen implicit
invariants are enforced by convention or not at all (inventory in §5).

### Root-cause taxonomy (bug classes → prevention property)

| # | Structural property missing | Bug class it kills (examples) |
|---|---|---|
| 1 | **Explicit persisted per-run pipeline state** | flag-chaining: dead incremental path (bug-061), deletes-only fallthrough (062), runs stuck 'running' (039), manual saves skipping confirms (db858b52) |
| 2 | **Outbox / confirm-after-ack around Hub3 effects** | sweep-at-save-start armed mass drop (033), torn SQLite batches (036), infinite re-drop (035), fake indexed counts |
| 3 | **Per-record events instead of totals subtraction** | the whole 4-round trends saga (built: RecordRegistry) |
| 4 | **Canonical id normalization at the ingest boundary** | drop_records never matching Hub3 (034/064); today bridged by a runtime heuristic |
| 5 | **One owner per fact + reconciliation loop for duplicates** | processedExternally outliving processed/ (3e83824e), registry-vs-Hub3 divergence (069/070), version-in-two-files (9 buglog entries) |

**The RecordRegistry + harvest_runs work is not another bug-squash round —
it is the first installed piece of the fix.** It already embodies: runs as
rows, pending-work by query, confirm-after-ack, tx rollback, schema
versioning with sequential migration, self-healing open runs, a
reconciliation health line, and a resync escape hatch. The redesign extends
this nucleus to own the whole pipeline. (An unimplemented March design doc,
2026-03-16-unified-state-management-design.md, diagnosed the same problem;
the registry is its lighter-weight realization.)

## 2. Target architecture

### 2.1 Runs with persisted plans

Every unit of work is a **run**: one row, one dataset, an ordered stage
list decided up front by a **pure planner**, executed stage-at-a-time by a
dumb engine persisting progress after each stage. `harvest_runs`
generalizes into this (same table, same run_id sequence — the registry's
`first_seen_run_id` FKs stay valid). Schema v4:

```sql
runs ( run_id PK, kind, trigger, plan JSON, current_stage, status,
       attempt, started_at, completed_at, note, seen/changed/deleted/added/sent counts )
run_stages ( run_id, stage, status, started_at, completed_at,
             input JSON, output JSON, error, PRIMARY KEY(run_id, stage) )
tombstones ( local_id PK /*normalized*/, raw_id, first_run_id, last_run_id, created_at )
```

Six idempotent stages with declared inputs/outputs:
**Harvest → Accumulate → GenerateSip → Process → Save → Reconcile**
(+ Analyze off the critical path). Resumability: `runs WHERE
status='running'` at startup → `interrupted` → per-kind policy (harvest
retries, save resumes from registry pending set, process restarts).

### 2.2 The planner replaces the flags and command strings

```
plan(trigger, datasetFacts) -> Plan          // pure, table-testable
replan(stageOutcome, remainingPlan) -> Plan  // e.g. Harvest→NoRecords(tombstones=4) ⇒ [reconcile]
```

Every current command/flag combination becomes a plan literal:
scheduled incremental = `[harvest(delta), accumulate, generate_sip,
process(delta), save(delta), reconcile]`; fast-save from SOURCED =
`[generate_sip, process, save(full), reconcile]`; deletes-only = mid-run
replan to `[reconcile]`. All conditional logic lives in two pure functions
+ isolated stages. Bug-061's class (payload matched then dropped) becomes
unrepresentable: the delta file is a persisted stage input.

### 2.3 Manual mode — short runs chained by lineage, not an open run

Manual operation (user clicks each step; SIP-Creator uploads) is NOT a
long-lived run. Three rules:

1. **Each manual action is its own short run** — "start processing" =
   `[generate_sip, process]`, "start saving" = `[save, reconcile]`,
   fast-save = the full chain from one click. An open run awaiting human
   input would fight the run lifecycle (self-heal, interrupted-on-restart,
   one-job-per-dataset lease).
2. **Continuity is recorded lineage.** The planner resolves "which run's
   output does this act on" once, at plan time, and persists it as
   `source_run_id` in the stage's `input` JSON — replacing the implicit
   `latestCompletedFullRunId` adoption heuristic. The engine can validate
   the link before running (e.g. refuse a save whose processed/ is newer
   than the referenced run).
3. **SIP-Creator processed upload is a run** (`kind=external_process`,
   plan `[ingest_processed]`): the stage registers the uploaded output and
   stamps the registry (hash → upsertSeen), so later saves have a real run
   to reference. This retires the `processedExternally` marker and its
   timestamp-race workaround entirely.

Schema impact: none beyond enum values (`trigger='manual'`; kinds
`process_only`, `save_only`, `external_process`) and the lineage
convention in `run_stages.input`.

### 2.4 SaveMode — sweep XOR filtering as a type

```
SaveMode = FullSendWithSweep | DeltaSendRegistryOwned | IncrementalFileSend
```

Decided once by the planner, persisted in `run_stages.input`,
pattern-matched by Save and Reconcile. Filtering-while-sweeping (the
mass-deletion bug) is unrepresentable, and every save's mode is auditable
after the fact.

### 2.5 Where state lives

- **Per-dataset SQLite** (`records.db` → conceptually `dataset.db`): truth
  for records, runs, run_stages, tombstones. Single writer per dataset, WAL.
- **Org-level `queue.db`**: `jobs` table with lease semantics; "one running
  job per spec" = partial unique index `UNIQUE(spec) WHERE status='leased'`,
  replacing OrgActor's in-memory queue + both semaphores (~25 hand-placed
  release call sites) + the stuck-state poller + ForceReleaseAndReset.
- **Fuseki becomes a projection, not a driver.** Descriptive metadata and
  harvest config stay (UI + sip-creator read them). `DsState`, counts,
  error/retry become derived: one `DatasetStatusProjector` computes them
  from runs + fs facts and writes the RDF props solely for the existing UI.
  `getState()`-by-max-timestamp and the externallyProcessed re-stamp hack
  are deleted.

### 2.6 Invariants enforced structurally

- **RecordId newtype**: only constructor applies normalization; harvester
  normalizes tombstones off the wire into the `tombstones` table (raw_id
  kept for audit). `resolveTombstoneIds` demoted to a migration shim.
- **AckedBatch receipts**: registry confirm methods accept only the Hub3
  client's ack type — confirming without a 200 is inexpressible.
- **deleted.ids → tombstones table** (write-through file during migration
  for SourceRepo compatibility, then retired).
- **pockets.xml.gz gets a sidecar manifest** `{sourceZips, deletedCount,
  idFilterHash, generatorVersion}` — cache used only when the manifest
  matches current inputs. Rule: any derived file carries a token proving
  which inputs produced it.

## 3. Go translation map

Design rule: **the SQLite schemas ARE the contract.** Go and Scala can
operate the same db files (WAL multi-process; jobs lease = one writer).

| Component | Go shape |
|---|---|
| Engine + planner | worker pool polling `queue.db`; ~300 lines over database/sql; context cancellation replaces interrupt flags. Write the Scala engine *synchronously* (no actor choreography) so it transliterates |
| Stage contract | `type Stage interface { Run(ctx, *RunContext) (StageResult, error) }` — Scala mirror is a synchronous trait, no Akka types in signatures |
| RecordRegistry | schema-first port, zero redesign (`modernc.org/sqlite`) |
| SourceRepo | plain package; on-disk format unchanged (it's the contract) |
| Harvester | net/http + resumable page loop; the flakiest prod component gets Go's better HTTP/context story |
| Hub3 client | plain HTTP; bulkaction files are JSON-lines → Go Save needs no RDF library; Hub3 team co-owns both ends |
| Fuseki/projection | plain SPARQL-over-HTTP; optional once the UI is replaced |
| **Mapping engine (Groovy sip-core)** | **the one true JVM dependency** — quarantined behind the Process stage as a JVM sidecar (pockets in → processed+bulkactions out); never lets it block the rest |

## 4. Migration — strangler-fig, every step deployable

### Phase A — inside Scala (~6–8 weeks total, incremental deploys)

- **A1 (~1–1.5 wk)** Schema v4 + write-through: runs/run_stages/tombstones
  added; existing call sites also write stage rows and the plan they would
  have followed. Zero behavior change; immediate audit trail.
- **A2 (~1.5–2 wk, highest value)** Planner extraction: `plan`/`replan`
  with exhaustive tests reproducing every command branch and buglog
  scenario; DatasetActor consults persisted plan instead of the four flags;
  flags deleted; SaveMode enum lands.
- **A3 (~2–3 wk, riskiest — optionally behind per-dataset opt-in)** Stage
  interfaces + thin driver: Harvester / GenerateSip / Process / Save /
  Reconcile behind the synchronous Stage trait on worker threads;
  `queue.db` lease replaces OrgActor queue + semaphores; stuck-state
  poller and ForceReleaseAndReset deleted.
- **A4a Mapping single-owner (~5–7 days, flag-gated).** DatasetMappingRepo
  becomes the ONLY mapping truth (audit 2026-07-07: three stores + a
  pointer, nine divergence paths — incl. web SIP upload never writing the
  repo, editor saves invisible to processing/preview, default selection
  writing nothing). Processing builds its SipMapper from repo-current +
  RecDefRepo (no zip); uploads ingest into the repo unconditionally;
  default selection COPIES into the repo with provenance; SIP zips become
  fully derived exchange artifacts (download/generate package
  repo-current); editor saves via RecMapping serialization (the
  string-templated XML may not round-trip to SIP-Creator). Cutover script
  ingests zip-newer-than-repo mappings first. Repo-backed mapper ships
  behind a config flag with the zip path as fallback for one release.
- **A4b Status projector + API swap (~3–4 days).** DatasetStatusProjector
  computes dataset status from runs + run_stages + filesystem + mapping
  repo and feeds the EXISTING dataset-list JSON API directly (same field
  names → minimal AngularJS churn); the RDF state props are never written
  again — no projector-to-RDF intermediate era. "processable" = "a mapping
  exists for the current prefix", derived fresh (kills the stale-state
  false-positive class); the API also serves planner-derived available
  actions and run_stages-based live progress (rebirths the archived PG
  branch's displayLabel/nextCheckpoint work). RecordId newtype; tombstones
  table becomes read path; pockets manifest.

After Phase A the Scala app already is the maintainable system: explicit
persisted state machine, no flag choreography, every branch unit-testable,
every schema/file a language-neutral contract.

### Phase B — per-stage Go extraction

- **B1 Harvester (~1–3 wk, revised).** A full Go harvesting stack already
  exists (Delving/Hub3 ecosystem) — B1 is *integration*, not a rewrite:
  wrap the existing Go harvester as the Harvest stage implementation
  (stage input in → accepted zip + tombstone rows + run_stages rows out,
  written to the shared SQLite). Still the lowest-stakes validation of the
  interop contract. The Go rewrite overall therefore focuses on the parts
  Narthex uniquely owns: user actions/commands, automation (planner +
  engine + queue), and tracking (runs/registry/status).
- **B2 Save + Reconcile (~3–4 wk).** Hub3 is Go — same team both ends;
  inputs are JSON-lines + registry; highest-invariant-density code moves
  early, validated by re-running the Scala test scenarios against
  production `records.db` copies.
- **B3 Orchestrator + API (~4–6 wk).** Go owns queue, engine, cron, status
  API; Scala shrinks to a mapper/SIP sidecar + legacy UI.
- **B4 Mapping engine — open-ended, explicitly out of scope.** JVM sidecar
  persists until sip-core is ported/replaced; the stage boundary
  quarantines it.

## 4a. Phase C — one state model, one affordance function (designed 2026-07-07)

### Diagnosis

The app answers three different questions with tangled machinery:

| Question | Where it lives today | Health |
|---|---|---|
| What does the dataset HAVE? | DatasetStatusProjector (fs + registry + mapping folder) | fixed by A4b |
| What is HAPPENING now? | Akka FSM state + queue.db lease + open run + websocket progress — four sources | fragmented |
| What CAN the user DO next? | ~20 `ng-show="dataset.stateXxx"` conditionals + client-side `delimitersValid` in AngularJS | **nowhere in the backend** |

Question 3 is the free-for-all: the backend owns the workflow (planner,
stage vocabulary) but the UI re-derives affordances by hand from state
timestamps. Every affordance bug so far (offering process without a
mapping, fast-save from impossible states, buttons during runs) is this
duplication drifting.

### Target model — the dataset status document

ONE backend-computed JSON per dataset, served identically by the list
endpoint, the detail endpoint, and every websocket push. The UI renders
it and decides nothing.

```json
{
  "spec": "brocade-cat-mas",
  "phase": "idle | queued | running | error | retry | disabled",
  "run":   { "id": 42, "kind": "incremental", "trigger": "periodic",
             "stage": "process",
             "stages": [ {"id": "harvest", "status": "completed"}, ... ],
             "progress": {"count": 61, "of": "percent"} },
  "artifacts": {
    "raw":       {"at": "..."},
    "source":    {"at": "...", "records": 2814, "deleted": 3},
    "sip":       {"at": "..."},
    "mapping":   {"prefix": "edm", "hash": "dad0faea", "at": "..."},
    "processed": {"at": "...", "valid": 2814, "invalid": 0},
    "analysis":  {"at": "...", "of": "processed"},
    "saved":     {"at": "...", "runId": 41, "sent": 2814}
  },
  "lastStep": "processed",
  "actions":  ["harvest", "analyze_source", "generate_sip", "process",
               "save", "fast_save", "disable"],
  "error":    null
}
```

- `artifacts` = the projector, renamed from state-timestamps to the nouns
  they actually are. `lastStep` = newest artifact (the badge, per the
  2026-07-07 decision: display shows the last step; capability ordering
  is backend-only).
- `phase` + `run` come from queue.db (queued/leased) + the open run row +
  run_stages. The Akka FSM stops being a state source — it is only an
  executor.
- `actions` is computed by ONE pure backend function (the affordance
  function): `actions(artifacts, phase, config) -> Set[Action]`. ~20
  lines, exhaustively unit-tested, same vocabulary as the planner. The
  ng-show forest in dataset-list.html is replaced by
  `ng-show="dataset.actions.includes('process')"`.

### Workflow rules (the free-for-all ends here)

1. **Every action is a job; every job executes as a planned run.** Today
   analysis and standalone make-sip run outside the run model, so they
   are invisible to history and to "busy" detection. New rule: even
   single-stage work gets a run row. Then phase/queued/running/stage are
   pure DB reads — no actor state consulted anywhere.
2. **The planner is the only author of plans.** Free-text command strings
   ("start fast save from stateX") die; jobs carry typed payloads that
   map 1:1 to planner entry points (JobPayload already does this).
3. **Stage vocabulary is closed**: harvest, generate_sip, process, save,
   reconcile, analyze. A new stage = a schema-visible decision, not a
   string.
4. **Errors are failed runs, not sticky props.** `phase=error` ⇔ latest
   run failed and no later run succeeded; the error message is the failed
   stage's error column. Retry = a scheduled retry job + counter on the
   run row. datasetErrorMessage/harvestInRetry props retire with this.
5. **Single writer per dataset** stays the lease (queue.db partial unique
   index); progress updates are run-row/stage-row updates the websocket
   relays.

### Storage (unchanged pieces stay)

- `records.db` (conceptually dataset.db): records, runs, run_stages,
  tombstones — already the shape Go wants (schema IS the contract).
- `queue.db`: jobs + lease — already right.
- Filesystem artifacts: already the truth the projector reads.
- Fuseki props: shrink to metadata + harvest config + DISABLED until B3
  moves them to dataset_props; nothing in Phase C adds a prop.

### Go translation

The status document is a pure function:
`status(spec) = f(dir listing, records.db, queue.db, dataset_props)`.
In Go: one `Status(spec string) (StatusDoc, error)` over os.Stat +
database/sql — no actor, no RDF. The affordance function transliterates
line-for-line. The websocket becomes "push the status doc on run-row
change", which a Go worker can do with a NOTIFY-style poll.

### Decisions (2026-07-07, with Sjoerd)

1. **Full cancel is a first-class action**: cancel removes queued jobs AND
   interrupts running runs (run marked failed, note "cancelled", lease
   freed). Replaces the half-working interrupt.
2. **Errors = failed runs, no sticky prop.** One clean run clears the
   error; old prop-based errors simply stop being shown at C3.
3. **C1 ships as additive fields** on the existing light JSON; UI migrates
   widget by widget; old state* fields retire when unread.
4. **Global `datasets` registry table stays B3** (after C1–C3). Noted:
   the migration is trivial — dump the Fuseki dataset list JSON once and
   insert rows; no elaborate migration machinery needed.

### Phasing (each step deployable)

- **C1 — status document + affordance function.** New endpoint shape (or
  additive fields on the existing light JSON), backend `actions[]`,
  UI renders buttons/badge/progress from it. Biggest confusion-killer,
  no storage change.
- **C2 — every action a run.** Analysis + standalone generate-sip get
  planned runs; phase derived from queue+runs; active-datasets endpoint
  and actor-state reads retire.
- **C3 — errors/retry as run outcomes.** Error/retry props retire;
  recovery UI reads failed runs.
- B3 (dataset_props) then strands Fuseki entirely (with SKOS already
  decided out).

## 4b. Phase D — counts clarity + total Fuseki removal (designed 2026-07-09)

Decision (Sjoerd): no legacy fallbacks — Fuseki dies completely; one-shot
migration, not compat layers.

### D0 findings (three inventories, 2026-07-09)

**Counts.** Truth already exists outside Fuseki for nearly everything:
registry `runs` (added/changed/deleted/seen/sent per run), `run_stages`
process output (`{"valid":N,"invalid":N}` since A3c-2), SourceRepo `.act`
+ `deleted.ids`, Hub3 facet counts. The Trends expanded section (runs
table, per-day diff chart, pending sync) is already the reference
implementation. Gaps/warts found:
- "Indexed" truth is Hub3-only; registry offers sent/confirmed + pending
  as the Narthex-side proxy.
- `harvesting-info.html` is a DEAD ng-include — the detail Record Counts
  panel renders nothing today.
- `acquisitionMethod` written as harvest/upload on the full path but
  adlib/pmh/json by sample harvests — templates only know the former.
- OrgActor `completedOperations.recordCount` is structurally always None.
- The (Δ57) incremental badge and the 24h trend have ambiguous
  definitions (valid-of-delta vs registry added+changed; end-of-day net
  vs per-run sums) — decide once in D1.

**Fuseki.** Only two things live there: the per-dataset info graph
(≈70 NXProps in ~10 concern groups → one `datasets` row) and the SKOS
graphs (dropped wholesale — explicit death list of files, props, queries,
routes, UI captured in D0). Record graphs no longer touch Fuseki at all.
Only stateSaved/stateIncrementalSaved/stateDisabled props still matter
(projector fallbacks); the other state props are dead. Infra to delete:
TripleStore/Sparql/GraphProperties(mostly), Fuseki healthcheck+bindings,
config blocks in every org conf, fuseki.ttl + deploy/fuseki/.

**DsInfo.** ~90% of the class is scalar KV over the graph — a straight
table swap behind unchanged signatures. Hairy 10%: the JSON-LD writer
(exactly ONE consumer: the single-dataset info endpoint — flatten it),
the three-layer existence/model cache (becomes a row read; preserve the
EMPTY-vs-DISABLED existence rule), list-valued props (4 callers → JSON
column), createDsInfo (→ INSERT). SkosGraph inheritance dies with SKOS.

### D1 design

**`datasets.db`** (org-level SQLite, sibling of queue.db): one `datasets`
table, spec PK; real columns for everything the light list and pipeline
read (metadata, harvest config incl. credentials, delimiters, mapping
source, operation tracking, error+retry, disabled, publish flags, id
filter); one `props_json` TEXT column for the long tail (json-harvest
cluster, list-valued props). WAL; same file-is-the-contract rule as
records.db — Go reads it directly.

**Counts document** (truth-only, part of the status doc):
```json
"counts": {
  "acquired":  {"records": N, "deleted": N, "method": "harvest",
                 "at": ts},                         // registry seen+deleted / .act
  "processed": {"valid": N, "invalid": N, "runId": R, "at": ts},
                                                    // latest process stage output
  "lastIncrement": {"added": N, "changed": N, "deleted": N,
                     "sent": N, "runId": R, "at": ts},  // last incremental run
  "indexed":   {"hub3": N, "sent": N, "pendingIndex": N,
                 "pendingDrops": N, "at": ts}       // Hub3 facet + registry
}
```
Total-correctness rule: totals ALWAYS from full-truth sources; increments
always deltas, never totals.

**One-shot migration** (`migrate-fuseki` admin task): dump every dataset
graph → INSERT rows; seed a synthetic baseline run in records.db for
datasets with prior stateSaved but no registry history (honest saved
status from day one); then Fuseki is never read again.

### D1 decisions (2026-07-10, with Sjoerd)

1. **Δ badge = registry diffs**: added(+) / changed(~, content-hash
   differs) / deleted(−) of the last incremental run — one vocabulary
   across badge, runs table and trends.
2. **24h trend split**: source-side from registry dailyRunDiffs;
   indexed-side keeps the nightly Hub3 snapshot (Hub3 has no history API).
3. **Migration seeds baseline runs** for datasets with prior stateSaved
   but no registry history.
4. **Single-dataset endpoint flattened** to plain JSON — the last
   JSON-LD/getModel consumer dies.

### Phasing

- **D2 — datasets.db + DsInfo strangler.** Table + row-backed prop
  get/set behind unchanged DsInfo signatures; light list becomes a SQL
  SELECT; JSON-LD endpoint flattened; migration task; deploy runs the
  migration once at startup when the table is empty.
- **D3 — counts + surfaces.** Counts block in the status doc; dataset
  list columns, detail panel (resurrect the dead include as a counts
  panel), trends list/header, activity + completion stats (populate
  recordCount from runs), index stats valid-vs-Hub3 from registry.
  Normalize acquisitionMethod. Decide Δ-badge + trend-source semantics.
- **D4 — the bonfire.** Delete SKOS subsystem (death list), TripleStore/
  Sparql/healthcheck/bindings/config, deploy files; decommission the
  Fuseki service on datahub (brabantcloud at its next major deploy).

## 5. What NOT to rebuild

RecordRegistry (it's the template); SourceRepo's on-disk format (quirky but
correct and sip-creator-compatible — port code, keep format); processed
output format (.xml.zst + JSON-lines bulkactions — exactly what a Go Save
wants); SIP zip format / sip-creator interop (external contract, freeze);
analyzer/skosification/vocab tooling (off critical path, become extra stage
types later); trends/activity JSONL (recently fixed, UI-only); AngularJS UI
+ its Fuseki read model (that's why the projector exists); Fuseki as
descriptive-metadata store (only its pipeline-driver role is removed).

## 5a. Fuseki end-state (decided 2026-07-06)

Fuseki has four tenants; all have exits, so Fuseki is fully droppable:

1. **Pipeline state driver** (DsState timestamp props, counts, retry/
   operation) — dies in A4 (projector); role fully gone once the UI reads a
   JSON status endpoint instead of the projected RDF props.
2. **Descriptive metadata + harvest config** — B3: `dataset_props` table
   (the archived PG branch's V1 schema is the column inventory).
3. **Record graphs** — already gone; records flow to Hub3 only.
4. **SKOS subsystem** (vocabularies, terminology mappings, skosification)
   — product owner decision: **drop it, do not migrate it.** Removal is its
   own change (Skosifier, PeriodicSkosifyCheck, VocabInfo, SkosMappingStore,
   TermMappingStore, the skos/terms UI pages, skosification stage hooks),
   scheduled after the A-phases. Consequences to accept: existing term
   mappings are not carried forward; already-skosified records in Hub3 are
   untouched; the terms/vocab UI disappears.

Exit criteria for decommissioning Fuseki on the servers: A4 shipped + UI
status endpoint, B3 dataset_props, SKOS removal merged. Then the Fuseki
service (2.4.1/5.6.0 pair) is removed from deployment entirely.

## 6. Key code references for implementation

- DatasetActor.scala: flags 217–227, command dispatch 479–708, chaining
  handlers 867–1172 — the code A2/A3 dissolves
- RecordRegistry.scala: schema + migration pattern to extend (239–318)
- GraphSaver.scala: boolean lattice → SaveMode (209–290), sweep placement
  (348–404)
- SourceProcessor.scala: stage split point (GenerateSipZip vs Process)
- SourceRepo.scala: deleted.ids 306–321, pockets cache 480–545
- DsInfo.scala: getState max-timestamp mechanism to retire (1046–1145)

## 7. Appendix — feat/postgresql-migration salvage

Reviewed 2026-07-06 (tip badcc809, diverged 1a3d43df, +8,359/−1,007 over 57
files). Decision: **do not merge or rebase — archive and mine.** Rationale:
it replaces Fuseki with an org-level PostgreSQL (competing with the
per-dataset-SQLite + Fuseki-as-projection direction), predates the
RecordRegistry and all incremental/trends fixes (~3,900 lines it never saw;
auto-merge is semantically broken — unmapped props read as absent),
verification (its Task 8) was never finished, and it carries seam defects:
broken migration bootstrapping bypass, no PG provisioning for new datasets
(FK violation on first write, unguarded), stale-snapshot kill-switch gap,
12 state timestamps squeezed into one column, and a PeriodicHarvest rewrite
that silently dropped the ModifiedAfter strategy.

Salvage into Phase A as fresh commits (~3–5 days total):

| Item (branch source) | Use | Phase |
|---|---|---|
| `PropertySnapshot.scala` NXProp↔column mapping | the projector's pre-debugged prop inventory (descriptive vs config vs derived) | A4 |
| V1 `dataset_state`/V11 columns + V2 `workflows`/`workflow_steps` DDL | column checklists for `runs`/`run_stages` schema | A1 |
| `GlobalFusekiWrites` kill-switch pattern | rewritten as "only the projector writes state props" guard | A4 |
| DsInfoService state-transition test scenarios (~1,900 spec lines) | planner test cases (drop the embedded-PG infra) | A2 |
| `displayLabel`/`nextCheckpoint` status-bar API (e203ea48, 684b75f5) | re-implement fresh against current UI | A3/A4 |
| `FusekiMigration` backfill (idempotent upsert + per-dataset report) | archived template if descriptive metadata leaves Fuseki | post-B3 |

Dropped: PG repository layer + Flyway/Hikari deps, embedded-PG test infra,
V7 audit triggers, V3 trend tables (target the pre-overhaul trends), the
workflow-to-PG move (superseded by runs in records.db).

Secondary value: independent validation — a second attempt at the same root
problem got stuck on exactly the consistency seams (dual-write, bootstrap,
state squeeze) that the runs+projector design avoids by construction.

Branch tagged `archive/postgresql-migration` and removed.

## 8. Full invariant inventory (from the as-is audit)

Fifteen implicit invariants currently enforced by convention; the design
maps each to its structural home. Abbreviated:

1. processed/ ≙ latest completed full run (→ run_stages input/output link)
2. processed/ ≙ current SIP mapping + source (→ mapping hash in stage input)
3. deleted.ids id space ≙ pocket ids (→ RecordId at boundary)
4. pockets cache ≙ full source iff ≤1 zip (→ manifest)
5. confirm-after-ack (→ AckedBatch type)
6. sweep XOR filter (→ SaveMode)
7. increment_revision before first chunk (→ stage ordering)
8. one job per dataset (→ lease index)
9. OrgActor/DatasetActor heavy-command lists match (→ deleted with queue.db)
10. harvestPreviousTime advance vs failure replay (→ run row carries window)
11. getState timestamp races (→ projector, single writer)
12. DsInfo cache coherence (→ props become projection, no driver reads)
13. processedInvalid reflects the run being saved (→ per-run count in runs)
14. Scheduled.file meaning shift (→ typed stage inputs/outputs)
15. registry mirrors Hub3 only if nothing else writes Hub3 (→ health line + reset command, already shipped)
