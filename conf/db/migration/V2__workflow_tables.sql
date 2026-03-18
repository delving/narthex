-- V2: Workflow tracking tables
-- Replace Akka Persistence + SQLite for workflow state management

CREATE TABLE workflows (
    id              TEXT PRIMARY KEY,
    spec            TEXT NOT NULL REFERENCES datasets(spec) ON DELETE CASCADE,
    trigger         TEXT,
    status          TEXT NOT NULL DEFAULT 'pending',
    retry_count     INT NOT NULL DEFAULT 0,
    next_retry_at   TIMESTAMPTZ,
    error_message   TEXT,
    started_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at    TIMESTAMPTZ
);

CREATE TABLE workflow_steps (
    id                  SERIAL PRIMARY KEY,
    workflow_id         TEXT NOT NULL REFERENCES workflows(id) ON DELETE CASCADE,
    step_name           TEXT NOT NULL,
    status              TEXT NOT NULL DEFAULT 'pending',
    records_processed   INT DEFAULT 0,
    error_message       TEXT,
    metadata            JSONB,
    started_at          TIMESTAMPTZ,
    completed_at        TIMESTAMPTZ
);
