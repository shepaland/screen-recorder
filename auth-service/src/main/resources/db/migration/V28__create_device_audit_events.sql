-- V28__create_device_audit_events.sql
-- Device user activity audit events (lock/unlock, logon/logoff, process start/stop)

CREATE TABLE device_audit_events (
    id              UUID            NOT NULL DEFAULT gen_random_uuid(),
    created_ts      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    tenant_id       UUID            NOT NULL,
    device_id       UUID            NOT NULL,
    session_id      UUID,
    event_type      VARCHAR(30)     NOT NULL
        CHECK (event_type IN (
            'SESSION_LOCK', 'SESSION_UNLOCK',
            'SESSION_LOGON', 'SESSION_LOGOFF',
            'PROCESS_START', 'PROCESS_STOP'
        )),
    event_ts        TIMESTAMPTZ     NOT NULL,
    details         JSONB           NOT NULL DEFAULT '{}'::jsonb,
    correlation_id  UUID,
    PRIMARY KEY (id, created_ts)
) PARTITION BY RANGE (created_ts);

-- Monthly partitions for 2026
CREATE TABLE device_audit_events_2026_01 PARTITION OF device_audit_events FOR VALUES FROM ('2026-01-01') TO ('2026-02-01');
CREATE TABLE device_audit_events_2026_02 PARTITION OF device_audit_events FOR VALUES FROM ('2026-02-01') TO ('2026-03-01');
CREATE TABLE device_audit_events_2026_03 PARTITION OF device_audit_events FOR VALUES FROM ('2026-03-01') TO ('2026-04-01');
CREATE TABLE device_audit_events_2026_04 PARTITION OF device_audit_events FOR VALUES FROM ('2026-04-01') TO ('2026-05-01');
CREATE TABLE device_audit_events_2026_05 PARTITION OF device_audit_events FOR VALUES FROM ('2026-05-01') TO ('2026-06-01');
CREATE TABLE device_audit_events_2026_06 PARTITION OF device_audit_events FOR VALUES FROM ('2026-06-01') TO ('2026-07-01');
CREATE TABLE device_audit_events_2026_07 PARTITION OF device_audit_events FOR VALUES FROM ('2026-07-01') TO ('2026-08-01');
CREATE TABLE device_audit_events_2026_08 PARTITION OF device_audit_events FOR VALUES FROM ('2026-08-01') TO ('2026-09-01');
CREATE TABLE device_audit_events_2026_09 PARTITION OF device_audit_events FOR VALUES FROM ('2026-09-01') TO ('2026-10-01');
CREATE TABLE device_audit_events_2026_10 PARTITION OF device_audit_events FOR VALUES FROM ('2026-10-01') TO ('2026-11-01');
CREATE TABLE device_audit_events_2026_11 PARTITION OF device_audit_events FOR VALUES FROM ('2026-11-01') TO ('2026-12-01');
CREATE TABLE device_audit_events_2026_12 PARTITION OF device_audit_events FOR VALUES FROM ('2026-12-01') TO ('2027-01-01');

-- Indexes
CREATE INDEX idx_dae_device_event_ts ON device_audit_events (device_id, event_ts DESC, created_ts);
CREATE INDEX idx_dae_tenant_event_ts ON device_audit_events (tenant_id, event_ts DESC, created_ts);
CREATE INDEX idx_dae_device_type ON device_audit_events (device_id, event_type, event_ts DESC, created_ts);
CREATE UNIQUE INDEX idx_dae_id_unique ON device_audit_events (id, created_ts);
CREATE INDEX idx_dae_details_gin ON device_audit_events USING GIN (details jsonb_path_ops);
