-- V7: Audit history infrastructure
-- Automatically track changes to critical tables

CREATE TABLE audit_history (
    id              BIGSERIAL PRIMARY KEY,
    table_name      TEXT NOT NULL,
    spec            TEXT,
    old_row         JSONB,
    new_row         JSONB,
    changed_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    changed_by      TEXT
);

-- Generic audit trigger function
CREATE OR REPLACE FUNCTION audit_trigger()
RETURNS TRIGGER AS $$
BEGIN
    INSERT INTO audit_history(table_name, spec, old_row, new_row, changed_by)
    VALUES (
        TG_TABLE_NAME,
        COALESCE(NEW.spec, OLD.spec),
        CASE WHEN TG_OP = 'INSERT' THEN NULL ELSE to_jsonb(OLD) END,
        CASE WHEN TG_OP = 'DELETE' THEN NULL ELSE to_jsonb(NEW) END,
        current_setting('app.current_user', true)
    );
    RETURN COALESCE(NEW, OLD);
END;
$$ LANGUAGE plpgsql;

-- Attach audit triggers to critical tables
CREATE TRIGGER audit_dataset_state
    AFTER INSERT OR UPDATE OR DELETE ON dataset_state
    FOR EACH ROW EXECUTE FUNCTION audit_trigger();

CREATE TRIGGER audit_dataset_harvest_config
    AFTER INSERT OR UPDATE OR DELETE ON dataset_harvest_config
    FOR EACH ROW EXECUTE FUNCTION audit_trigger();

CREATE TRIGGER audit_dataset_harvest_schedule
    AFTER INSERT OR UPDATE OR DELETE ON dataset_harvest_schedule
    FOR EACH ROW EXECUTE FUNCTION audit_trigger();

CREATE TRIGGER audit_dataset_indexing
    AFTER INSERT OR UPDATE OR DELETE ON dataset_indexing
    FOR EACH ROW EXECUTE FUNCTION audit_trigger();
