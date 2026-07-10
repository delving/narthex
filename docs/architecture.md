# Narthex Architecture Reference (July 2026)

The post-Fuseki, stage-based architecture. One page per question: what
stores exist, what each component should do, what every route serves.
Kept current manually — update when components move.

## Storage (the contract)

| Store | File | Truth for |
|---|---|---|
| Dataset registry | `<org>/datasets.db` (tables `datasets`, `dataset_props`, `dataset_prop_lists`) | dataset metadata, harvest config, delimiters, mapping source, operation/error/retry flags, DISABLED |
| Record registry | `<org>/datasets/<spec>/records.db` (`records`, `runs`, `run_stages`, `tombstones`) | per-record content hashes + sent state, every run with its persisted plan and stage trail, tombstones |
| Job queue | `<org>/queue.db` (`jobs`) | queued work + the one-leased-job-per-dataset invariant |
| Filesystem | `datasets/<spec>/{raw,source,processed,tree,sourceTree,mappings}`, org `sips/`, `raw/` (pockets + manifest) | all artifacts; the status projector reads presence/mtimes |
| Hub3 | bulk API (`index`/`drop_records`/`clear_orphans`) + webhook | the search index; the only external store |

Rules: SQLite files are read directly by tools/Go (WAL, schema-first).
Nothing writes lifecycle state — status is **projected** from these
stores. Fuseki is gone; a one-shot migration slice remains until every
org has cut over (see below).

## The pipeline

```
harvest ─► generate_sip ─► process ─► save ─► reconcile
   (delta zips)  (pockets+SIP)  (mapped RDF)  (Hub3 bulk)  (drops/sweep)
```

- **PipelinePlan** (`dataset/PipelinePlan.scala`) — the pure planner. Every
  chain is decided up front as an ordered stage list persisted on the run
  row. Plans: full/incremental harvest continuations, fast-save variants,
  processOnly, saveOnly, analyzeOnly, generateSipOnly (aux runs =
  `kind=task`, never adoptable).
- **Pipeline stages** (`dataset/pipeline/`) — synchronous, Akka-free units
  (`run(ctx): StageResult`): `GenerateSipStage` (pockets via manifest cache
  + SIP packaging + mapping heal), `ProcessStage` (mapping execution,
  registry stamping), `SaveStage` (chunked Hub3 send + confirms, then
  reconcile: missing-sweep, drop batches, optional clear_orphans, run
  completion). `MappingHeal` materializes tracked defaults; `PocketManifest`
  proves pocket inputs.
- **DatasetActor** (`dataset/DatasetActor.scala`) — per-dataset FSM: runs
  stages on worker threads (`runStageAsync`), owns retry + recovery
  (`CheckForStuckState`), broadcasts status documents on transitions.
  It is an executor, not a state store.
- **OrgActor / JobQueue** — org supervisor draining `queue.db`; a partial
  unique index enforces one leased job per dataset; leases are released
  on idle/terminate, stale ones reclaimed.

## Status / actions / counts (all projected)

- **DatasetStatusProjector** — lifecycle states from artifacts + registry
  runs (saved requires a completed save/reconcile stage; processable = a
  current mapping for the dataset's prefix).
- **DatasetStatusDoc** — the status document served on the list, websocket
  and info endpoints: `phase` (queue+runs), `run` (stage trail), `actions[]`
  (the single affordance function; linear model — during a run only
  consumers of the running stage's output hide), `lastStep` (badge),
  `counts` (acquired/processed/lastIncrement/indexed — truth-only),
  `error` (latest failed run).
- Delta vocabulary everywhere: `+added ~changed −deleted` per run.

## Mapping (single-owner)

- **DatasetMappingRepo** (`datasets/<spec>/mappings/`) — THE mapping truth
  per dataset; versioned, hash-deduped, saving makes current, cross-prefix
  saves archive the old prefix.
- **DefaultMappingRepo** (`<org>/default-mappings/`) — shared defaults;
  versioned the same way; selection *copies* into the dataset folder;
  make-sip and processing re-materialize tracked "latest".
- **RepoSipMapper / PocketMappingEngine** — build the executing mapper
  from folder-current + RecDefRepo; the SIP zip is a derived artifact.
- **RecDefRepo** — versioned record definitions per prefix.

## Other core components

| Area | Components | Role |
|---|---|---|
| Harvesting | `Harvester` (actor), `Harvesting`, `PeriodicHarvest`, `PocketWriter` | OAI-PMH/AdLib/JSON paged harvests → source delta zips; periodic scans use the projector for harvestability |
| Source/processed | `SourceRepo`, `ProcessedRepo`, `PocketParser`, `SourceProcessor` | accumulated source zips + id/act files; processed chunked output; source adoption |
| Analysis | `Analyzer` (actor), `NodeRepo`, `TreeNode`, `ValueStats`, `ViolationIndex` | field tree/histograms/quality over raw/source/processed |
| Services | `ActivityLogger`, `ProgressReporter`, `TrendTrackingService`, `IndexStatsService`, `QualitySummaryService`, `ViolationRecordService`, `CredentialEncryption`, `MailService` (error mail), `MemoryMonitorService` | support |
| Controllers | `AppController` (UI API), `APIController` (artifact downloads), `SipAppController` (SIP-Creator), `DiscoveryController`, `WebhookController` (Hub3 indexing callbacks), `WebSocketController`, `PreviewController`, `InfoController`/`MetricsController` (ops) | HTTP surface |
| Frontend | AngularJS modules: common, datasetList, dataset, datasetDelimiter, defaultMappings, recdefs, stats, discovery, indexStats, trends; plus the Svelte mapping editor (`public/editor/`) | all reachable |

## Migration slice (temporary, delete at D4b)

`services/FusekiMigration` + `triplestore/TripleStore.query/dataGet` +
`Sparql.selectDatasetSpecsQ`. Runs once at startup while `datasets.db` is
empty AND the optional `triple-store` config key is set. Per server:
deploy → verify "Fuseki migration complete: N datasets" → stop Fuseki →
remove the key. When every org has migrated, delete the slice.

## Route map (audited 2026-07-10)

Full audit: every route verified against its consumer (Angular jsRoutes /
hardcoded fetches, the Svelte editor bundle, SIP-Creator's
`NetworkClient.java`, Hub3 webhook).

- **UI API** (`/narthex/app/...`): dataset lifecycle/commands, analysis +
  quality, mappings/recdefs/default-mappings, editor endpoints
  (mappings-json, preview-mapping*, save-mapping, generate-*), stats/
  trends/index-stats/memory, discovery — all LIVE (consumers named in the
  audit).
- **SIP-Creator** (EXTERNAL): `GET /narthex/sip-app`,
  `GET /narthex/sip-app/:spec`, `POST /narthex/sip-app/:spec/:zip`,
  `POST /narthex/app/dataset/:spec/upload-processed`. Nothing else — the
  `/narthex/api/*` endpoints are frontend-only download links.
- **Hub3** (EXTERNAL): `POST /narthex/webhook/indexing`.
- **Infra**: SPA/assets/jsRoutes/websocket/monitoring endpoints.

## Known cruft (identified 2026-07-10; items 1,2,4–8 removed same day — only item 3 remains, deliberately, until D4b)

1. **Workflow-persistence machinery** — `WorkflowPersistenceActor`,
   `WorkflowEvent`, `WorkflowDatabase`/`GlobalWorkflowDatabase` (+ spec):
   write-only (only `Started` is ever sent; nothing queries it);
   superseded by ActivityLogger + the status projection.
2. **Dead routes**: `GET /app/index-stats` (superseded by
   `index-stats-with-trends`), `GET /app/dataset/:spec/preview-samples/:count`
   (editor uses preview-mapping*).
3. **Dead TripleStore surface**: `sparqlUpdate/dataPost/dataPutXMLFile/`
   `dataPutGraph/ask/batchCheckGraphExistence` — no callers; goes with D4b.
4. **DsInfo `implicit ts` threading** — parameter noise, unused since D2.
5. **MailService.sendProcessingCompleteMessage** + `processingComplete`/
   `emailNotFound` templates — zero callers.
6. **Orphan templates**: `skos-list.html`, `category-monitor.html`,
   `sip-files.html`.
7. **Dead client leftovers**: `toggleSkosifiedField` calls in
   dataset-services/controllers (route gone), `$location.path('/categories'|
   '/thesaurus')` navigations, `apiMappings` URL with no route,
   `state-skosifying/categorizing` labels.
8. **Scratch/fixtures**: `Sparql.scala` `Q"gumby"` demo lines; stale
   `test/resources/{skos,categories,crm}` fixtures; commented-out route
   lines; stale `.worktrees/record-registry` checkout.
