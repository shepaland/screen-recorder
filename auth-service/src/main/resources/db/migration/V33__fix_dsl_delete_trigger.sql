-- V33: Fix missing DELETE trigger on device_status_log
-- Security finding: prevent_dsl_delete() function was created in V32 but trigger was not attached
-- Also adds CHECK constraint on previous_status for defense-in-depth

-- 1. Fix DELETE trigger: block all direct DELETEs (CASCADE from devices FK still works via ON DELETE CASCADE)
CREATE OR REPLACE FUNCTION prevent_dsl_delete()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'device_status_log is immutable: DELETE not allowed';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_dsl_no_delete
    BEFORE DELETE ON device_status_log
    FOR EACH ROW EXECUTE FUNCTION prevent_dsl_delete();

-- 2. Add CHECK constraint on previous_status (defense-in-depth)
ALTER TABLE device_status_log ADD CONSTRAINT chk_dsl_prev_status CHECK (
    previous_status IS NULL OR previous_status IN (
        'offline', 'online', 'recording', 'error',
        'starting', 'configuring', 'awaiting_user', 'idle', 'stopped'
    )
);
