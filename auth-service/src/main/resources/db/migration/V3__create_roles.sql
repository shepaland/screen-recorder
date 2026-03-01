CREATE TABLE roles (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID        NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    code            VARCHAR(100) NOT NULL,
    name            VARCHAR(255) NOT NULL,
    description     TEXT,
    is_system       BOOLEAN     NOT NULL DEFAULT FALSE,
    created_ts      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_ts      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_roles_tenant_code UNIQUE (tenant_id, code)
);

CREATE INDEX idx_roles_tenant_id ON roles (tenant_id);

COMMENT ON TABLE roles IS 'Роли, привязанные к тенанту. is_system=true для предустановленных ролей';
