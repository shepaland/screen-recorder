-- Webhook subscriptions for CRM/ATS integrations
CREATE TABLE webhook_subscriptions (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL,
    url             VARCHAR(2048) NOT NULL,
    event_types     JSONB NOT NULL DEFAULT '[]',
    secret          VARCHAR(256),
    active          BOOLEAN NOT NULL DEFAULT true,
    created_ts      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_ts      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by      UUID
);
CREATE INDEX idx_webhook_subs_tenant ON webhook_subscriptions(tenant_id);
CREATE INDEX idx_webhook_subs_active ON webhook_subscriptions(tenant_id, active) WHERE active = true;

-- Webhook delivery log
CREATE TABLE webhook_deliveries (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    subscription_id UUID NOT NULL REFERENCES webhook_subscriptions(id),
    event_id        UUID NOT NULL,
    event_type      VARCHAR(64) NOT NULL,
    status          VARCHAR(20) NOT NULL DEFAULT 'pending',
    response_code   INTEGER,
    attempts        INTEGER NOT NULL DEFAULT 0,
    last_attempt_ts TIMESTAMPTZ,
    error_message   TEXT,
    created_ts      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_webhook_del_sub ON webhook_deliveries(subscription_id, created_ts DESC);

-- Permissions
INSERT INTO permissions (id, name, description) VALUES
    (gen_random_uuid(), 'WEBHOOKS:READ', 'View webhook subscriptions'),
    (gen_random_uuid(), 'WEBHOOKS:MANAGE', 'Create/update/delete webhook subscriptions');

-- Grant to ADMIN and SUPER_ADMIN
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r, permissions p
WHERE r.name IN ('ADMIN', 'SUPER_ADMIN')
  AND p.name IN ('WEBHOOKS:READ', 'WEBHOOKS:MANAGE')
ON CONFLICT DO NOTHING;
