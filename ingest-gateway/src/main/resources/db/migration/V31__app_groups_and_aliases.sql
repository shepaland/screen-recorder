-- V31__app_groups_and_aliases.sql
-- App groups, group items, and app aliases for catalogs/dashboard

-- 1. app_groups: grouping applications and websites
CREATE TABLE app_groups (
    id          UUID            NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    tenant_id   UUID            NOT NULL,
    group_type  VARCHAR(10)     NOT NULL CHECK (group_type IN ('APP', 'SITE')),
    name        VARCHAR(200)    NOT NULL,
    description VARCHAR(1000),
    color       VARCHAR(7),
    sort_order  INTEGER         NOT NULL DEFAULT 0,
    is_default  BOOLEAN         NOT NULL DEFAULT false,
    created_at  TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    created_by  UUID
);

CREATE UNIQUE INDEX idx_ag_tenant_type_name
    ON app_groups (tenant_id, group_type, LOWER(name));

CREATE INDEX idx_ag_tenant_type_sort
    ON app_groups (tenant_id, group_type, sort_order);

-- 2. app_group_items: patterns that belong to groups
CREATE TABLE app_group_items (
    id          UUID            NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    tenant_id   UUID            NOT NULL,
    group_id    UUID            NOT NULL REFERENCES app_groups(id) ON DELETE CASCADE,
    item_type   VARCHAR(10)     NOT NULL CHECK (item_type IN ('APP', 'SITE')),
    pattern     VARCHAR(512)    NOT NULL,
    match_type  VARCHAR(10)     NOT NULL DEFAULT 'EXACT' CHECK (match_type IN ('EXACT', 'SUFFIX', 'CONTAINS')),
    created_at  TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX idx_agi_tenant_type_pattern
    ON app_group_items (tenant_id, item_type, LOWER(pattern));

CREATE INDEX idx_agi_group
    ON app_group_items (group_id);

CREATE INDEX idx_agi_tenant_type
    ON app_group_items (tenant_id, item_type);

-- 3. app_aliases: human-readable display names for apps/sites
CREATE TABLE app_aliases (
    id           UUID            NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    tenant_id    UUID            NOT NULL,
    alias_type   VARCHAR(10)     NOT NULL CHECK (alias_type IN ('APP', 'SITE')),
    original     VARCHAR(512)    NOT NULL,
    display_name VARCHAR(200)    NOT NULL,
    icon_url     VARCHAR(1024),
    created_at   TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX idx_aa_tenant_type_original
    ON app_aliases (tenant_id, alias_type, LOWER(original));

CREATE INDEX idx_aa_tenant_type
    ON app_aliases (tenant_id, alias_type);
