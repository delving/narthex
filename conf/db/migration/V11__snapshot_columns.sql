-- V11: Add columns needed for DsInfo PostgreSQL snapshot
-- Retry state (was in Fuseki, now in dataset_state)
ALTER TABLE dataset_state ADD COLUMN retry_message TEXT;
ALTER TABLE dataset_state ADD COLUMN in_retry BOOLEAN DEFAULT false;
ALTER TABLE dataset_state ADD COLUMN retry_count INT DEFAULT 0;
ALTER TABLE dataset_state ADD COLUMN last_retry_at TIMESTAMPTZ;

-- Operation status (was in Fuseki, now in dataset_state)
ALTER TABLE dataset_state ADD COLUMN operation_status TEXT;

-- Processed externally flag (was in Fuseki, now in mapping config)
ALTER TABLE dataset_mapping_config ADD COLUMN processed_externally TEXT;
