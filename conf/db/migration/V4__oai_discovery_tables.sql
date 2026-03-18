-- V4: OAI-PMH source discovery tables
-- Support automatic dataset discovery from OAI-PMH endpoints

CREATE TABLE oai_sources (
    id                      TEXT PRIMARY KEY,
    org_id                  TEXT NOT NULL,
    name                    TEXT NOT NULL,
    url                     TEXT NOT NULL,
    default_metadata_prefix TEXT DEFAULT 'oai_dc',
    default_aggregator      TEXT,
    default_prefix          TEXT DEFAULT 'edm',
    default_edm_type        TEXT,
    harvest_delay           INT,
    harvest_delay_unit      TEXT,
    harvest_incremental     BOOLEAN,
    mapping_rules           JSONB DEFAULT '[]',
    ignored_sets            TEXT[] DEFAULT '{}',
    enabled                 BOOLEAN DEFAULT true,
    last_checked            TIMESTAMPTZ,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE oai_source_set_counts (
    source_id       TEXT NOT NULL REFERENCES oai_sources(id) ON DELETE CASCADE,
    set_spec        TEXT NOT NULL,
    record_count    INT,
    error           TEXT,
    verified_at     TIMESTAMPTZ,
    PRIMARY KEY (source_id, set_spec)
);

-- Now that oai_sources exists, add the foreign key from dataset_harvest_config
ALTER TABLE dataset_harvest_config
    ADD CONSTRAINT fk_harvest_config_oai_source
    FOREIGN KEY (oai_source_id) REFERENCES oai_sources(id) ON DELETE SET NULL;
