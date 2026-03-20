-- V39: Add recording_disabled and desktop_unavailable to devices.status CHECK constraint
-- Supports new agent state machine: DesktopUnavailable (gdigrab error 5) and RecordingDisabled (admin toggle)

ALTER TABLE devices DROP CONSTRAINT IF EXISTS devices_status_check;
ALTER TABLE devices ADD CONSTRAINT devices_status_check
    CHECK (status IN (
        'offline', 'online', 'recording', 'error',
        'starting', 'configuring', 'awaiting_user', 'idle', 'stopped',
        'recording_disabled', 'desktop_unavailable'
    ));

-- Also extend device_status_log CHECK constraints (both previous and new status)
ALTER TABLE device_status_log DROP CONSTRAINT IF EXISTS chk_dsl_prev_status;
ALTER TABLE device_status_log ADD CONSTRAINT chk_dsl_prev_status CHECK (
    previous_status IS NULL OR previous_status IN (
        'offline', 'online', 'recording', 'error',
        'starting', 'configuring', 'awaiting_user', 'idle', 'stopped',
        'recording_disabled', 'desktop_unavailable'
    )
);

ALTER TABLE device_status_log DROP CONSTRAINT IF EXISTS chk_dsl_new_status;
ALTER TABLE device_status_log ADD CONSTRAINT chk_dsl_new_status CHECK (
    new_status IN (
        'offline', 'online', 'recording', 'error',
        'starting', 'configuring', 'awaiting_user', 'idle', 'stopped',
        'recording_disabled', 'desktop_unavailable'
    )
);
