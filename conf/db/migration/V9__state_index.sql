-- V9: Add index on dataset_state(state) for common filter patterns
-- Used by IndexStatsService (DISABLED filter) and DsInfoService list queries

CREATE INDEX idx_dataset_state_state ON dataset_state(state);
