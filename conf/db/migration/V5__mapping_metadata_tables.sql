-- V5: Mapping metadata tables
-- Track default and dataset-specific mapping versions

CREATE TABLE default_mappings (
    prefix          TEXT NOT NULL,
    name            TEXT NOT NULL,
    org_id          TEXT NOT NULL,
    display_name    TEXT,
    current_version INT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (prefix, name)
);

CREATE TABLE default_mapping_versions (
    id              SERIAL PRIMARY KEY,
    prefix          TEXT NOT NULL,
    name            TEXT NOT NULL,
    hash            TEXT NOT NULL,
    filename        TEXT,
    source          TEXT,
    source_dataset  TEXT,
    notes           TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (prefix, name) REFERENCES default_mappings(prefix, name) ON DELETE CASCADE,
    UNIQUE (prefix, name, hash)
);

CREATE TABLE dataset_mappings (
    spec            TEXT PRIMARY KEY REFERENCES datasets(spec) ON DELETE CASCADE,
    prefix          TEXT,
    current_version INT,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE dataset_mapping_versions (
    id              SERIAL PRIMARY KEY,
    spec            TEXT NOT NULL REFERENCES datasets(spec) ON DELETE CASCADE,
    hash            TEXT NOT NULL,
    filename        TEXT,
    source          TEXT,
    source_default  TEXT,
    description     TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (spec, hash)
);
