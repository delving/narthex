# PostgreSQL Migration Design

**Date:** 2026-03-18  
**Status:** Accepted  
**Scope:** Replace Fuseki triple store and SQLite with PostgreSQL as single data store

## Problem

Dataset state and metadata is scattered across four stores:

1. **Fuseki triple store** — 102 NXProp properties stored as RDF triples. Used as a key-value store with SPARQL syntax. No graph reasoning, no inference.
2. **SQLite** — Workflow tracking (added in 0.8.7.16).
3. **Actor memory** — FSM state, retry count, workflow ID. Lost on restart, restored from triple store.
4. **File system** — Source repos, SIP files, activity logs.

This causes: dual sources of truth for retry state, SPARQL queries for what should be simple SELECTs, caching layers to work around triple store latency, fragile state restoration on actor restart, and no audit trail.

## Research Findings

An inventory of all 102 NXProp properties found:

- **51 operational state** properties (error tracking, retry, operation tracking, record counts, indexing results, state timestamps, sync flags). These are the hot path — read and written on every workflow step.
- **42 configuration/metadata** properties (harvest URL, credentials, scheduling, mapping config, publish flags). Set by users, read during processing.
- **9 domain data** properties (record linking, SKOS vocabularies, terminology mappings).

Key discoveries:

- **Records are NOT stored in Fuseki.** The code that added `belongsTo`, `hubId`, `localId`, `saveTime`, `contentHash` triples is entirely commented out. Records go to Hub3 via bulk API as JSON-LD.
- **No SPARQL reasoning or inference.** No RDFS/OWL prefixes, no property paths, no transitive closures.
- **SKOS vocabulary navigation is done in-memory** by loading the full RDF graph into a Jena Model, not via SPARQL.
- **Hub3 does not query Fuseki.** It receives data exclusively via its REST bulk API.
- **The retry mechanism** stores state in both FSM data and triple store properties, with a SPARQL poll every minute to find datasets needing retry. This is the most fragile part.
- **`getState()` picks the latest timestamp** across 12 separate NXProp timestamp fields. In SQL this is a single `state` column.

## Decision

Replace Fuseki and SQLite with PostgreSQL as the single data store.

**Why PostgreSQL over SQLite:**
- Multiple services need to query dataset state (Hub3, monitoring, reporting).
- Proper concurrent access without `synchronized` blocks.
- Audit triggers, JSONB, and mature tooling.

**Why fully replace Fuseki:**
- No graph features are used. All queries are flat key-value lookups.
- SKOS data can be stored as RDF text and loaded into Jena memory (same as now, different persistence layer).
- Eliminates a separate service dependency.

## Schema Design

The single `datasets` mega-table is split by concern. Each table has a clear change frequency and audit requirement.

### Core Tables

#### `datasets` — Identity and ownership

Rarely changes. No audit needed.

```sql
CREATE TABLE datasets (
    spec              TEXT PRIMARY KEY,
    org_id            TEXT NOT NULL,
    name              TEXT,
    description       TEXT,
    owner             TEXT,
    dataset_type      TEXT,
    character         TEXT,
    language          TEXT,
    rights            TEXT,
    tags              TEXT[],
    aggregator        TEXT,
    data_provider_url TEXT,
    edm_type          TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at        TIMESTAMPTZ
);
```

#### `dataset_harvest_config` — Harvest settings

Changes when user edits settings. Audited so you know what config was active when a harvest ran.

```sql
CREATE TABLE dataset_harvest_config (
    spec                 TEXT PRIMARY KEY REFERENCES datasets(spec),
    harvest_type         TEXT,
    harvest_url          TEXT,
    harvest_dataset      TEXT,
    harvest_prefix       TEXT,
    harvest_record       TEXT,
    harvest_search       TEXT,
    harvest_download_url TEXT,
    harvest_username     TEXT,
    harvest_password     TEXT,           -- encrypted
    harvest_api_key      TEXT,           -- encrypted
    harvest_api_key_param TEXT,
    harvest_json         JSONB,          -- itemsPath, idPath, totalPath, pageParam, etc.
    record_root          TEXT,
    unique_id            TEXT,
    continue_on_error    BOOLEAN DEFAULT false,
    error_threshold      INT,
    id_filter_type       TEXT,
    id_filter_expression TEXT,
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

#### `dataset_harvest_schedule` — Cron and timing

Changes when schedule is set. Audited.

```sql
CREATE TABLE dataset_harvest_schedule (
    spec                     TEXT PRIMARY KEY REFERENCES datasets(spec),
    delay                    TEXT,
    delay_unit               TEXT,
    incremental              BOOLEAN DEFAULT false,
    previous_time            TIMESTAMPTZ,
    last_full_harvest        TIMESTAMPTZ,
    last_incremental_harvest TIMESTAMPTZ,
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

#### `dataset_mapping_config` — Mapping and publish settings

Changes when mappings are configured. Not audited (low frequency, less interesting).

```sql
CREATE TABLE dataset_mapping_config (
    spec                    TEXT PRIMARY KEY REFERENCES datasets(spec),
    map_to_prefix           TEXT,
    mapping_source          TEXT,
    default_mapping_prefix  TEXT,
    default_mapping_name    TEXT,
    default_mapping_version TEXT,
    publish_oaipmh          BOOLEAN DEFAULT true,
    publish_index           BOOLEAN DEFAULT true,
    publish_lod             BOOLEAN DEFAULT true,
    categories_include      BOOLEAN DEFAULT false,
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

#### `dataset_state` — Operational state

Changes every workflow step. The hot table. Audited — state progression over time is the primary audit interest.

```sql
CREATE TABLE dataset_state (
    spec                          TEXT PRIMARY KEY REFERENCES datasets(spec),
    state                         TEXT NOT NULL DEFAULT 'CREATED',
    state_changed_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    error_message                 TEXT,
    error_time                    TIMESTAMPTZ,
    current_operation             TEXT,
    operation_start               TIMESTAMPTZ,
    operation_trigger             TEXT,
    record_count                  INT DEFAULT 0,
    acquired_count                INT DEFAULT 0,
    deleted_count                 INT DEFAULT 0,
    source_count                  INT DEFAULT 0,
    processed_valid               INT DEFAULT 0,
    processed_invalid             INT DEFAULT 0,
    processed_incremental_valid   INT DEFAULT 0,
    processed_incremental_invalid INT DEFAULT 0,
    acquisition_method            TEXT,
    delimiters_set                TIMESTAMPTZ,
    updated_at                    TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

#### `dataset_indexing` — Hub3 webhook results

Written by Hub3 indexing completion webhooks. Audited to correlate indexing results with state changes.

```sql
CREATE TABLE dataset_indexing (
    spec              TEXT PRIMARY KEY REFERENCES datasets(spec),
    records_indexed   INT,
    records_expected  INT,
    orphans_deleted   INT,
    error_count       INT,
    last_status       TEXT,
    last_message      TEXT,
    last_timestamp    TIMESTAMPTZ,
    last_revision     INT,
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

### Workflow Tables

Workflows are immutable once completed. They ARE the audit trail for operations.

```sql
CREATE TABLE workflows (
    id            TEXT PRIMARY KEY,
    spec          TEXT NOT NULL REFERENCES datasets(spec),
    trigger       TEXT NOT NULL,       -- manual, periodic, retry
    status        TEXT NOT NULL DEFAULT 'running',
    retry_count   INT DEFAULT 0,
    next_retry_at TIMESTAMPTZ,        -- replaces harvestInRetry + timer polling
    error_message TEXT,
    started_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at  TIMESTAMPTZ
);

CREATE TABLE workflow_steps (
    id                SERIAL PRIMARY KEY,
    workflow_id       TEXT NOT NULL REFERENCES workflows(id),
    step_name         TEXT NOT NULL,
    status            TEXT NOT NULL DEFAULT 'running',
    records_processed INT DEFAULT 0,
    error_message     TEXT,
    metadata          JSONB,
    started_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at      TIMESTAMPTZ
);
```

### SKOS Tables

```sql
CREATE TABLE vocabularies (
    spec        TEXT PRIMARY KEY,
    name        TEXT NOT NULL,
    owner       TEXT,
    upload_time TIMESTAMPTZ,
    rdf_data    TEXT,               -- raw RDF/XML, loaded into Jena memory for search
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE skos_mappings (
    id              SERIAL PRIMARY KEY,
    source_uri      TEXT NOT NULL,
    target_uri      TEXT NOT NULL,
    vocabulary_uri  TEXT NOT NULL,
    dataset_uri     TEXT,
    mapping_type    TEXT NOT NULL,   -- exactMatch, belongsToCategory
    deleted         BOOLEAN DEFAULT false,
    synced          BOOLEAN DEFAULT false,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(source_uri, target_uri, vocabulary_uri)
);
```

### Audit Infrastructure

A single generic trigger captures row snapshots on audited tables.

```sql
CREATE TABLE audit_history (
    id          BIGSERIAL PRIMARY KEY,
    table_name  TEXT NOT NULL,
    spec        TEXT NOT NULL,
    old_row     JSONB,
    new_row     JSONB,
    changed_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    changed_by  TEXT
);

CREATE INDEX idx_audit_spec ON audit_history(spec, table_name, changed_at DESC);

CREATE OR REPLACE FUNCTION audit_trigger() RETURNS TRIGGER AS $$
BEGIN
    INSERT INTO audit_history(table_name, spec, old_row, new_row, changed_by)
    VALUES (
        TG_TABLE_NAME,
        COALESCE(NEW.spec, OLD.spec),
        CASE WHEN TG_OP = 'INSERT' THEN NULL ELSE to_jsonb(OLD) END,
        CASE WHEN TG_OP = 'DELETE' THEN NULL ELSE to_jsonb(NEW) END,
        current_setting('app.current_user', true)
    );
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER audit_dataset_state
    AFTER INSERT OR UPDATE OR DELETE ON dataset_state
    FOR EACH ROW EXECUTE FUNCTION audit_trigger();

CREATE TRIGGER audit_harvest_config
    AFTER INSERT OR UPDATE OR DELETE ON dataset_harvest_config
    FOR EACH ROW EXECUTE FUNCTION audit_trigger();

CREATE TRIGGER audit_harvest_schedule
    AFTER INSERT OR UPDATE OR DELETE ON dataset_harvest_schedule
    FOR EACH ROW EXECUTE FUNCTION audit_trigger();

CREATE TRIGGER audit_indexing
    AFTER INSERT OR UPDATE OR DELETE ON dataset_indexing
    FOR EACH ROW EXECUTE FUNCTION audit_trigger();
```

### Indexes

```sql
CREATE INDEX idx_workflows_spec ON workflows(spec);
CREATE INDEX idx_workflows_status ON workflows(status);
CREATE INDEX idx_workflows_retry ON workflows(status, next_retry_at)
    WHERE status = 'retry';
CREATE INDEX idx_workflow_steps_wfid ON workflow_steps(workflow_id);
CREATE INDEX idx_datasets_state ON datasets(deleted_at)
    WHERE deleted_at IS NULL;
CREATE INDEX idx_skos_mappings_vocab ON skos_mappings(vocabulary_uri)
    WHERE NOT deleted;
```

## What Gets Eliminated

| Before | After |
|--------|-------|
| Fuseki triple store (separate service) | Gone |
| SQLite workflow database | Gone (merged into PostgreSQL) |
| 102 NXProp definitions in `GraphProperties.scala` | Typed columns |
| SPARQL queries in `Sparql.scala` | SQL queries |
| `DsInfo` triple store caching layer | Direct SQL reads |
| `setSingularLiteralProps` + `updateSyncedFalseQ` write amplification | Single `UPDATE` |
| Retry state in triple store + FSM `InRetry` data type | `workflows.status = 'retry'` |
| PeriodicHarvest SPARQL poll for retry datasets | `SELECT FROM workflows WHERE status='retry' AND next_retry_at < now()` |
| 12 state timestamp properties + latest-timestamp-wins logic | `state` column + `state_changed_at` |

## Retry Mechanism (Redesigned)

The retry mechanism moves from triple store flags to workflows:

1. Harvest fails → workflow `status = 'retry'`, `next_retry_at = now() + backoff(retry_count)`
2. PeriodicHarvest queries: `SELECT * FROM workflows WHERE status = 'retry' AND next_retry_at < now()`
3. On retry start → `UPDATE workflows SET retry_count = retry_count + 1, next_retry_at = ...`
4. On success → `status = 'completed'`
5. On max retries → `status = 'failed'`, send email

Backoff formula: `min(retry_count * 60, 480)` minutes (1h, 2h, 3h, ... 8h max).

This eliminates: `harvestInRetry`, `harvestRetryCount`, `harvestLastRetryTime`, `harvestRetryMessage` NXProps, `InRetry` FSM data type, `setInRetry()`/`clearRetryState()`/`incrementRetryCount()`/`isInRetry`/`getRetryCount` methods, `selectDatasetsInRetryQ` SPARQL query, and dual source-of-truth bugs.

## Migration Strategy

Incremental, not big-bang. Each phase is independently deployable.

**Phase 1: Add PostgreSQL alongside Fuseki.**
- Add PostgreSQL dependency and connection pooling (HikariCP or similar).
- Create all tables.
- New `DatasetRepository` service with SQL read/write methods.
- Migration command that reads all NXProps from triple store and writes to PostgreSQL.
- Run both stores in parallel, verify consistency.

**Phase 2: Switch reads to PostgreSQL.**
- `DsInfo` reads from PostgreSQL, writes to both PostgreSQL and triple store.
- Replace SPARQL SELECT queries with SQL queries.
- Frontend reads from PostgreSQL via existing controllers.

**Phase 3: Switch writes to PostgreSQL only.**
- `DsInfo` writes only to PostgreSQL.
- Remove SPARQL UPDATE queries.
- Fuseki becomes read-only for any remaining SKOS data.

**Phase 4: Migrate SKOS data, remove Fuseki.**
- Move vocabulary RDF to `vocabularies.rdf_data`.
- Move mappings to `skos_mappings` table.
- Remove Fuseki dependency, `TripleStore` service, `Sparql.scala`.

## Skosification

Skosification ran SPARQL queries across record graphs in Fuseki. Since records are no longer stored in Fuseki (code is commented out), skosification is likely partially broken or operating on legacy data.

Options:
- **Drop it** if no longer used.
- **Reimplement** against processed XML files if still needed.
- **Defer** — assess actual usage before deciding.

This is out of scope for the PostgreSQL migration and should be evaluated separately.

## Example Queries

```sql
-- List all active datasets with state
SELECT d.spec, d.name, ds.state, ds.record_count, ds.error_message
FROM datasets d
JOIN dataset_state ds ON d.spec = ds.spec
WHERE d.deleted_at IS NULL
ORDER BY ds.updated_at DESC;

-- Find datasets needing retry
SELECT w.id, w.spec, w.retry_count, w.next_retry_at
FROM workflows w
WHERE w.status = 'retry' AND w.next_retry_at < now();

-- Dataset state history for the last week
SELECT changed_at,
       old_row->>'state' AS from_state,
       new_row->>'state' AS to_state,
       new_row->>'record_count' AS records
FROM audit_history
WHERE spec = 'museum-objects' AND table_name = 'dataset_state'
  AND changed_at > now() - interval '7 days'
ORDER BY changed_at;

-- What harvest config was active when a workflow ran?
SELECT new_row FROM audit_history
WHERE spec = 'museum-objects' AND table_name = 'dataset_harvest_config'
  AND changed_at <= (SELECT started_at FROM workflows WHERE id = 'wf-123')
ORDER BY changed_at DESC LIMIT 1;
```
