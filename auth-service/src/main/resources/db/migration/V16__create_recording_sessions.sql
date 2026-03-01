-- V16: Recording sessions - tracks start/stop of continuous recording periods

CREATE TABLE recording_sessions (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL REFERENCES tenants(id),
    device_id           UUID NOT NULL REFERENCES devices(id),
    user_id             UUID REFERENCES users(id),

    status              VARCHAR(20) NOT NULL DEFAULT 'active'
        CHECK (status IN ('active', 'completed', 'failed', 'interrupted')),

    started_ts          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    ended_ts            TIMESTAMPTZ,

    segment_count       INTEGER NOT NULL DEFAULT 0,
    total_bytes         BIGINT NOT NULL DEFAULT 0,
    total_duration_ms   BIGINT NOT NULL DEFAULT 0,

    metadata            JSONB NOT NULL DEFAULT '{}'::jsonb,

    created_ts          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_ts          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_rs_tenant_id ON recording_sessions(tenant_id);
CREATE INDEX idx_rs_device_id ON recording_sessions(device_id);
CREATE INDEX idx_rs_tenant_status ON recording_sessions(tenant_id, status);
CREATE INDEX idx_rs_started ON recording_sessions(started_ts);
CREATE INDEX idx_rs_device_active ON recording_sessions(device_id, status)
    WHERE status = 'active';

COMMENT ON TABLE recording_sessions IS 'Recording sessions: continuous recording period on a device';
COMMENT ON COLUMN recording_sessions.metadata IS 'Session metadata: resolution, fps, codec';
