-- V44: Tenant memberships — one user can belong to multiple tenants
-- Each membership has its own set of roles within the tenant

-- 1. Memberships table
CREATE TABLE tenant_memberships (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    tenant_id   UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    is_active   BOOLEAN NOT NULL DEFAULT TRUE,
    is_default  BOOLEAN NOT NULL DEFAULT FALSE,
    joined_ts   TIMESTAMPTZ NOT NULL DEFAULT now(),
    invited_by  UUID REFERENCES users(id),
    created_ts  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_ts  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, tenant_id)
);

CREATE INDEX idx_tm_user_active ON tenant_memberships(user_id) WHERE is_active = TRUE;
CREATE INDEX idx_tm_tenant_active ON tenant_memberships(tenant_id) WHERE is_active = TRUE;

-- 2. Membership roles (replaces user_roles for multi-tenant)
CREATE TABLE membership_roles (
    membership_id UUID NOT NULL REFERENCES tenant_memberships(id) ON DELETE CASCADE,
    role_id       UUID NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
    PRIMARY KEY (membership_id, role_id)
);

-- 3. Migrate existing data: each active user gets a membership in their tenant
INSERT INTO tenant_memberships (user_id, tenant_id, is_default, is_active, joined_ts)
SELECT u.id, u.tenant_id, TRUE, TRUE, u.created_ts
FROM users u
WHERE u.is_active = TRUE
  AND u.tenant_id IS NOT NULL;

-- 4. Migrate user_roles → membership_roles
INSERT INTO membership_roles (membership_id, role_id)
SELECT tm.id, ur.role_id
FROM user_roles ur
JOIN users u ON ur.user_id = u.id
JOIN tenant_memberships tm ON tm.user_id = u.id AND tm.tenant_id = u.tenant_id;

-- 5. Comments
COMMENT ON TABLE tenant_memberships IS 'Links users to tenants with per-tenant role assignments';
COMMENT ON COLUMN tenant_memberships.is_default IS 'Default tenant shown after login (one per user)';
