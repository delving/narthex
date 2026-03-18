-- V1: Core dataset identity, configuration, state, and indexing tables
-- These tables replace the Fuseki triple store for dataset metadata

CREATE TABLE datasets (
    spec            TEXT PRIMARY KEY,
    org_id          TEXT NOT NULL,
    name            TEXT,
    description     TEXT,
    owner           TEXT,
    dataset_type    TEXT,
    character       TEXT,
    language        TEXT,
    rights          TEXT,
    tags            TEXT[] DEFAULT '{}',
    aggregator      TEXT,
    data_provider_url TEXT,
    edm_type        TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at      TIMESTAMPTZ
);

CREATE TABLE dataset_harvest_config (
    spec                TEXT PRIMARY KEY REFERENCES datasets(spec) ON DELETE CASCADE,
    harvest_type        TEXT,
    harvest_url         TEXT,
    harvest_dataset     TEXT,
    harvest_prefix      TEXT,
    harvest_record      TEXT,
    harvest_search      TEXT,
    harvest_download_url TEXT,
    harvest_username    TEXT,
    harvest_password    TEXT,
    harvest_api_key     TEXT,
    harvest_api_key_param TEXT,
    harvest_json        JSONB,
    source_type         TEXT,
    record_root         TEXT,
    unique_id           TEXT,
    record_container    TEXT,
    oai_source_id       TEXT,  -- FK added in V4 after oai_sources table exists
    continue_on_error   BOOLEAN DEFAULT false,
    error_threshold     INT,
    id_filter_type      TEXT,
    id_filter_expression TEXT,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE dataset_harvest_schedule (
    spec                    TEXT PRIMARY KEY REFERENCES datasets(spec) ON DELETE CASCADE,
    delay                   TEXT,
    delay_unit              TEXT,
    incremental             BOOLEAN DEFAULT false,
    previous_time           TIMESTAMPTZ,
    last_full_harvest       TIMESTAMPTZ,
    last_incremental_harvest TIMESTAMPTZ,
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE dataset_mapping_config (
    spec                    TEXT PRIMARY KEY REFERENCES datasets(spec) ON DELETE CASCADE,
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

CREATE TABLE dataset_state (
    spec                        TEXT PRIMARY KEY REFERENCES datasets(spec) ON DELETE CASCADE,
    state                       TEXT NOT NULL DEFAULT 'CREATED',
    state_changed_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    error_message               TEXT,
    error_time                  TIMESTAMPTZ,
    current_operation           TEXT,
    operation_start             TIMESTAMPTZ,
    operation_trigger           TEXT,
    record_count                INT DEFAULT 0,
    acquired_count              INT DEFAULT 0,
    deleted_count               INT DEFAULT 0,
    source_count                INT DEFAULT 0,
    processed_valid             INT DEFAULT 0,
    processed_invalid           INT DEFAULT 0,
    processed_incremental_valid INT DEFAULT 0,
    processed_incremental_invalid INT DEFAULT 0,
    acquisition_method          TEXT,
    delimiters_set              TIMESTAMPTZ,
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE dataset_indexing (
    spec              TEXT PRIMARY KEY REFERENCES datasets(spec) ON DELETE CASCADE,
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
