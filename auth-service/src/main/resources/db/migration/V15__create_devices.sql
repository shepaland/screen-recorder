-- V15: Device registry - tracks all enrolled agent machines

CREATE TABLE devices (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id               UUID NOT NULL REFERENCES tenants(id),
    user_id                 UUID REFERENCES users(id),
    registration_token_id   UUID REFERENCES device_registration_tokens(id),

    hostname                VARCHAR(255) NOT NULL,
    os_version              VARCHAR(255),
    agent_version           VARCHAR(50),
    hardware_id             VARCHAR(255),

    status                  VARCHAR(20) NOT NULL DEFAULT 'offline'
        CHECK (status IN ('offline', 'online', 'recording', 'error')),

    last_heartbeat_ts       TIMESTAMPTZ,
    last_recording_ts       TIMESTAMPTZ,

    ip_address              VARCHAR(45),

    settings                JSONB NOT NULL DEFAULT '{}'::jsonb,

    is_active               BOOLEAN NOT NULL DEFAULT TRUE,

    created_ts              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_ts              TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    UNIQUE(tenant_id, hardware_id)
);

CREATE INDEX idx_devices_tenant_id ON devices(tenant_id);
CREATE INDEX idx_devices_tenant_status ON devices(tenant_id, status);
CREATE INDEX idx_devices_user_id ON devices(user_id);
CREATE INDEX idx_devices_heartbeat ON devices(last_heartbeat_ts)
    WHERE status != 'offline';

COMMENT ON TABLE devices IS 'Registry of enrolled agent machines (Windows/macOS)';
COMMENT ON COLUMN devices.hardware_id IS 'Unique hardware fingerprint: MB serial + CPU id + disk serial';
COMMENT ON COLUMN devices.settings IS 'Device-specific settings: capture_fps, segment_duration_sec, quality';
COMMENT ON COLUMN devices.status IS 'offline=no heartbeat, online=connected, recording=actively capturing, error=malfunction';
