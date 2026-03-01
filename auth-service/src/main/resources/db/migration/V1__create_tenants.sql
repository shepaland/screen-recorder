CREATE TABLE tenants (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    name            VARCHAR(255) NOT NULL,
    slug            VARCHAR(100) NOT NULL UNIQUE,
    is_active       BOOLEAN     NOT NULL DEFAULT TRUE,
    settings        JSONB       NOT NULL DEFAULT '{}',
    created_ts      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_ts      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_tenants_slug ON tenants (slug);
CREATE INDEX idx_tenants_is_active ON tenants (is_active) WHERE is_active = TRUE;

COMMENT ON TABLE tenants IS 'Организации-арендаторы платформы';
COMMENT ON COLUMN tenants.slug IS 'URL-safe идентификатор тенанта (например, company-abc)';
COMMENT ON COLUMN tenants.settings IS 'Настройки тенанта: max_users, max_retention_days, features{}';
