-- V10: Add updated_at column to oai_sources for consistency with other mutable tables

ALTER TABLE oai_sources ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT now();
