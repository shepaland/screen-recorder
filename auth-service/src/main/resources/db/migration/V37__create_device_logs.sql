-- V37__create_device_logs.sql
-- Agent log collection: stores log content uploaded from agents

CREATE TABLE device_logs (
    id              UUID            NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    tenant_id       UUID            NOT NULL REFERENCES tenants(id),
    device_id       UUID            NOT NULL,
    log_type        VARCHAR(100)    NOT NULL,   -- e.g. 'kadero-agent', 'kadero-http', 'kadero-pipe'
    content         TEXT            NOT NULL,
    log_from_ts     TIMESTAMPTZ,                -- earliest log entry timestamp
    log_to_ts       TIMESTAMPTZ,                -- latest log entry timestamp
    uploaded_at     TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    requested_by    UUID            REFERENCES users(id) ON DELETE SET NULL
);

CREATE INDEX idx_device_logs_device ON device_logs (device_id, log_type);
CREATE INDEX idx_device_logs_tenant ON device_logs (tenant_id);

-- Add UPLOAD_LOGS to device_commands command_type CHECK constraint
ALTER TABLE device_commands DROP CONSTRAINT IF EXISTS device_commands_command_type_check;
ALTER TABLE device_commands ADD CONSTRAINT device_commands_command_type_check
    CHECK (command_type IN ('START_RECORDING', 'STOP_RECORDING', 'UPDATE_SETTINGS', 'RESTART_AGENT', 'UNREGISTER', 'UPLOAD_LOGS'));
