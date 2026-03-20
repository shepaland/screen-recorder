-- V45: Invitations — invite users to tenant by email

CREATE TABLE invitations (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    email       VARCHAR(255) NOT NULL,
    role_id     UUID NOT NULL REFERENCES roles(id),
    token       VARCHAR(255) NOT NULL UNIQUE,
    invited_by  UUID NOT NULL REFERENCES users(id),
    accepted_ts TIMESTAMPTZ,
    expires_at  TIMESTAMPTZ NOT NULL,
    created_ts  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_invitations_token ON invitations(token) WHERE accepted_ts IS NULL;
CREATE INDEX idx_invitations_tenant ON invitations(tenant_id);

COMMENT ON TABLE invitations IS 'Email invitations to join a tenant. Token-based, expires in 7 days.';
