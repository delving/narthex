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

### 2.3 SaveMode — sweep XOR filtering as a type

```
SaveMode = FullSendWithSweep | DeltaSendRegistryOwned | IncrementalFileSend
```

Decided once by the planner, persisted in `run_stages.input`,
pattern-matched by Save and Reconcile. Filtering-while-sweeping (the
mass-deletion bug) is unrepresentable, and every save's mode is auditable
after the fact.

### 2.4 Where state lives

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

### 2.5 Invariants enforced structurally

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
- **A4 (~1–1.5 wk)** DatasetStatusProjector (single writer of RDF state
  props); RecordId newtype; tombstones table becomes read path; pockets
  manifest.

After Phase A the Scala app already is the maintainable system: explicit
persisted state machine, no flag choreography, every branch unit-testable,
every schema/file a language-neutral contract.

### Phase B — per-stage Go extraction

- **B1 Harvester (~2–4 wk).** Zero JVM deps, IO-bound, flakiest component;
  ships as a `narthex-harvester` binary exec'd as the Harvest stage,
  results in the shared SQLite. Lowest-stakes validation of the interop
  contract.
- **B2 Save + Reconcile (~3–4 wk).** Hub3 is Go — same team both ends;
  inputs are JSON-lines + registry; highest-invariant-density code moves
  early, validated by re-running the Scala test scenarios against
  production `records.db` copies.
- **B3 Orchestrator + API (~4–6 wk).** Go owns queue, engine, cron, status
  API; Scala shrinks to a mapper/SIP sidecar + legacy UI.
- **B4 Mapping engine — open-ended, explicitly out of scope.** JVM sidecar
  persists until sip-core is ported/replaced; the stage boundary
  quarantines it.

## 5. What NOT to rebuild

RecordRegistry (it's the template); SourceRepo's on-disk format (quirky but
correct and sip-creator-compatible — port code, keep format); processed
output format (.xml.zst + JSON-lines bulkactions — exactly what a Go Save
wants); SIP zip format / sip-creator interop (external contract, freeze);
analyzer/skosification/vocab tooling (off critical path, become extra stage
types later); trends/activity JSONL (recently fixed, UI-only); AngularJS UI
+ its Fuseki read model (that's why the projector exists); Fuseki as
descriptive-metadata store (only its pipeline-driver role is removed).

## 6. Key code references for implementation

- DatasetActor.scala: flags 217–227, command dispatch 479–708, chaining
  handlers 867–1172 — the code A2/A3 dissolves
- RecordRegistry.scala: schema + migration pattern to extend (239–318)
- GraphSaver.scala: boolean lattice → SaveMode (209–290), sweep placement
  (348–404)
- SourceProcessor.scala: stage split point (GenerateSipZip vs Process)
- SourceRepo.scala: deleted.ids 306–321, pockets cache 480–545
- DsInfo.scala: getState max-timestamp mechanism to retire (1046–1145)

## 7. Full invariant inventory (from the as-is audit)

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
