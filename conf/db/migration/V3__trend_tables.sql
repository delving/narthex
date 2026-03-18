-- V3: Trend tracking tables
-- Replace ActivityLogger JSONL files with structured trend data

CREATE TABLE trend_snapshots (
    id                  BIGSERIAL PRIMARY KEY,
    spec                TEXT NOT NULL REFERENCES datasets(spec) ON DELETE CASCADE,
    snapshot_type       TEXT NOT NULL,
    source_records      INT DEFAULT 0,
    acquired_records    INT DEFAULT 0,
    deleted_records     INT DEFAULT 0,
    valid_records       INT DEFAULT 0,
    invalid_records     INT DEFAULT 0,
    indexed_records     INT DEFAULT 0,
    captured_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE trend_daily_summaries (
    spec                TEXT NOT NULL REFERENCES datasets(spec) ON DELETE CASCADE,
    summary_date        DATE NOT NULL,
    source_records      INT DEFAULT 0,
    acquired_records    INT DEFAULT 0,
    deleted_records     INT DEFAULT 0,
    valid_records       INT DEFAULT 0,
    invalid_records     INT DEFAULT 0,
    indexed_records     INT DEFAULT 0,
    delta_source        INT DEFAULT 0,
    delta_valid         INT DEFAULT 0,
    delta_indexed       INT DEFAULT 0,
    event_count         INT DEFAULT 0,
    PRIMARY KEY (spec, summary_date)
);
