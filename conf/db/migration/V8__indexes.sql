-- V8: Performance indexes
-- Optimized for common query patterns in Narthex

-- Workflow indexes
CREATE INDEX idx_workflows_spec ON workflows(spec);
CREATE INDEX idx_workflows_status ON workflows(status);
CREATE INDEX idx_workflows_retry ON workflows(next_retry_at) WHERE status = 'retry';
CREATE INDEX idx_workflow_steps_wfid ON workflow_steps(workflow_id);

-- Dataset indexes
CREATE INDEX idx_datasets_active ON datasets(org_id) WHERE deleted_at IS NULL;

-- SKOS mapping indexes
CREATE INDEX idx_skos_mappings_vocab ON skos_mappings(vocabulary_uri) WHERE NOT deleted;

-- Trend indexes
CREATE INDEX idx_trend_snapshots_spec ON trend_snapshots(spec, captured_at DESC);
CREATE INDEX idx_trend_daily_spec ON trend_daily_summaries(spec, summary_date DESC);

-- OAI source indexes
CREATE INDEX idx_oai_sources_org ON oai_sources(org_id);
CREATE INDEX idx_oai_set_counts_verified ON oai_source_set_counts(verified_at);

-- Mapping version indexes
CREATE INDEX idx_default_mapping_versions ON default_mapping_versions(prefix, name, created_at DESC);
CREATE INDEX idx_dataset_mapping_versions ON dataset_mapping_versions(spec, created_at DESC);

-- Harvest config index for OAI source lookups
CREATE INDEX idx_harvest_config_source ON dataset_harvest_config(oai_source_id) WHERE oai_source_id IS NOT NULL;

-- Audit index for querying change history by dataset
CREATE INDEX idx_audit_spec ON audit_history(spec, table_name, changed_at DESC);
