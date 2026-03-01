-- V18: Command queue for devices (HTTP polling fallback, NATS in V2)

CREATE TABLE device_commands (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL REFERENCES tenants(id),
    device_id           UUID NOT NULL REFERENCES devices(id),

    command_type        VARCHAR(50) NOT NULL
        CHECK (command_type IN (
            'START_RECORDING', 'STOP_RECORDING',
            'UPDATE_SETTINGS', 'RESTART_AGENT', 'UNREGISTER'
        )),

    payload             JSONB NOT NULL DEFAULT '{}'::jsonb,

    status              VARCHAR(20) NOT NULL DEFAULT 'pending'
        CHECK (status IN ('pending', 'delivered', 'acknowledged', 'failed', 'expired')),

    created_by          UUID REFERENCES users(id),
    delivered_ts        TIMESTAMPTZ,
    acknowledged_ts     TIMESTAMPTZ,
    result              JSONB,
    expires_at          TIMESTAMPTZ DEFAULT (NOW() + INTERVAL '24 hours'),

    created_ts          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_dc_device_pending ON device_commands(device_id, status, created_ts)
    WHERE status = 'pending';
CREATE INDEX idx_dc_device_id ON device_commands(device_id, created_ts);
CREATE INDEX idx_dc_tenant ON device_commands(tenant_id, created_ts);

COMMENT ON TABLE device_commands IS 'Command queue: server-to-device commands delivered via heartbeat polling';
COMMENT ON COLUMN device_commands.expires_at IS 'Commands expire after 24h by default if not delivered';
