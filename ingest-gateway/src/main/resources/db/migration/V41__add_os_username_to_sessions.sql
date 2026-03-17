-- V41: Add os_username to recording_sessions for tracking which OS user was recorded.
-- os_username = DOMAIN\user format from the Windows agent (e.g., "air911d\shepaland").

ALTER TABLE recording_sessions ADD COLUMN os_username VARCHAR(256);

-- Backfill from device_audit_events (best effort: closest audit event before session start)
UPDATE recording_sessions rs
SET os_username = (
    SELECT dae.username
    FROM device_audit_events dae
    WHERE dae.device_id = rs.device_id
      AND dae.tenant_id = rs.tenant_id
      AND dae.username IS NOT NULL
      AND dae.username != ''
      AND dae.event_ts <= rs.started_ts + INTERVAL '5 minutes'
    ORDER BY dae.event_ts DESC
    LIMIT 1
)
WHERE rs.os_username IS NULL;

CREATE INDEX idx_recording_sessions_os_username ON recording_sessions(tenant_id, os_username);
