-- V14: Device registration tokens for agent enrollment
-- Admin generates tokens in web-dashboard, agents use them for first-time registration

CREATE TABLE device_registration_tokens (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES tenants(id),
    token_hash      VARCHAR(255) NOT NULL UNIQUE,
    name            VARCHAR(255) NOT NULL,
    max_uses        INTEGER,
    current_uses    INTEGER NOT NULL DEFAULT 0,
    expires_at      TIMESTAMPTZ,
    is_active       BOOLEAN NOT NULL DEFAULT TRUE,
    created_by      UUID NOT NULL REFERENCES users(id),
    created_ts      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_ts      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_drt_tenant_id ON device_registration_tokens(tenant_id);
CREATE INDEX idx_drt_token_hash ON device_registration_tokens(token_hash);
CREATE INDEX idx_drt_active ON device_registration_tokens(tenant_id, is_active)
    WHERE is_active = TRUE;

COMMENT ON TABLE device_registration_tokens IS 'Tokens for enrolling new devices/agents into a tenant';
COMMENT ON COLUMN device_registration_tokens.token_hash IS 'SHA-256 hash of the raw token. Raw token shown only at creation time';
COMMENT ON COLUMN device_registration_tokens.max_uses IS 'NULL means unlimited uses';
COMMENT ON COLUMN device_registration_tokens.expires_at IS 'NULL means never expires';
