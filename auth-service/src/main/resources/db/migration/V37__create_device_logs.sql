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
