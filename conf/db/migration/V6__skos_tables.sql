-- V6: SKOS vocabulary and mapping tables
-- Replace in-memory Jena-based SKOS storage

CREATE TABLE vocabularies (
    spec            TEXT PRIMARY KEY,
    name            TEXT NOT NULL,
    owner           TEXT,
    upload_time     TIMESTAMPTZ,
    rdf_data        TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE skos_mappings (
    id              SERIAL PRIMARY KEY,
    source_uri      TEXT NOT NULL,
    target_uri      TEXT NOT NULL,
    vocabulary_uri  TEXT NOT NULL,
    dataset_uri     TEXT,
    mapping_type    TEXT,
    deleted         BOOLEAN DEFAULT false,
    synced          BOOLEAN DEFAULT false,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (source_uri, target_uri, vocabulary_uri)
);
