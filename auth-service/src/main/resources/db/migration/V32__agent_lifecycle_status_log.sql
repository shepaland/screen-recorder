-- V32: Agent lifecycle -- device status log + extended status enum
-- Extends devices.status CHECK constraint with new agent lifecycle states
-- Creates device_status_log table for immutable status transition history

-- 1. Extend devices.status CHECK constraint with new lifecycle states
ALTER TABLE devices DROP CONSTRAINT IF EXISTS devices_status_check;
ALTER TABLE devices ADD CONSTRAINT devices_status_check
    CHECK (status IN (
        'offline', 'online', 'recording', 'error',
        'starting', 'configuring', 'awaiting_user', 'idle', 'stopped'
    ));

-- 2. Device status log table (immutable, INSERT-only)
CREATE TABLE device_status_log (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL,
    device_id       UUID NOT NULL,
    previous_status VARCHAR(20),
    new_status      VARCHAR(20) NOT NULL,
    changed_ts      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    trigger         VARCHAR(30) NOT NULL DEFAULT 'heartbeat',
    details         JSONB,

    CONSTRAINT fk_dsl_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id),
    CONSTRAINT fk_dsl_device FOREIGN KEY (device_id) REFERENCES devices(id) ON DELETE CASCADE,
    CONSTRAINT chk_dsl_trigger CHECK (
        trigger IN ('heartbeat', 'command', 'session_event', 'system', 'admin')
    ),
    CONSTRAINT chk_dsl_new_status CHECK (
        new_status IN (
            'offline', 'online', 'recording', 'error',
            'starting', 'configuring', 'awaiting_user', 'idle', 'stopped'
        )
    )
);

-- Primary query: device detail page, sorted by time desc
CREATE INDEX idx_dsl_device_ts ON device_status_log(device_id, changed_ts DESC);

-- Admin query: all status changes for a tenant
CREATE INDEX idx_dsl_tenant_ts ON device_status_log(tenant_id, changed_ts DESC);

-- Monitoring: find error transitions
CREATE INDEX idx_dsl_errors ON device_status_log(tenant_id, changed_ts DESC)
    WHERE new_status = 'error';

-- Immutable trigger: prevent UPDATE on device_status_log
CREATE OR REPLACE FUNCTION prevent_dsl_update()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'device_status_log is immutable: UPDATE not allowed';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_dsl_no_update
    BEFORE UPDATE ON device_status_log
    FOR EACH ROW EXECUTE FUNCTION prevent_dsl_update();

-- Immutable trigger: prevent DELETE on device_status_log (except CASCADE from devices)
CREATE OR REPLACE FUNCTION prevent_dsl_delete()
RETURNS TRIGGER AS $$
BEGIN
    -- Allow CASCADE deletes from devices FK
    IF TG_ARGV[0] = 'allow_cascade' THEN
        RETURN OLD;
    END IF;
    RAISE EXCEPTION 'device_status_log is immutable: DELETE not allowed';
END;
$$ LANGUAGE plpgsql;

COMMENT ON TABLE device_status_log IS 'Immutable log of device status transitions, written on each heartbeat status change';
COMMENT ON COLUMN device_status_log.trigger IS 'What caused the transition: heartbeat, command, session_event, system, admin';
COMMENT ON COLUMN device_status_log.details IS 'Optional JSON: agent_version, session_locked, error_message, command_id';
