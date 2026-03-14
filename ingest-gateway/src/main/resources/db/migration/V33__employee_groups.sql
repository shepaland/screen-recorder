-- V33__employee_groups.sql
-- Employee groups for organizing users (employees) in the analytics section

-- 1. employee_groups: named groups with color
CREATE TABLE employee_groups (
    id          UUID            NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    tenant_id   UUID            NOT NULL,
    name        VARCHAR(200)    NOT NULL,
    description VARCHAR(1000),
    color       VARCHAR(7),
    sort_order  INTEGER         NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    created_by  UUID
);

CREATE UNIQUE INDEX idx_eg_tenant_name
    ON employee_groups (tenant_id, LOWER(name));

CREATE INDEX idx_eg_tenant_sort
    ON employee_groups (tenant_id, sort_order);

-- 2. employee_group_members: assignment of username to a group
--    UNIQUE on (tenant_id, username) ensures one employee belongs to at most one group
CREATE TABLE employee_group_members (
    id          UUID            NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    tenant_id   UUID            NOT NULL,
    group_id    UUID            NOT NULL REFERENCES employee_groups(id) ON DELETE CASCADE,
    username    VARCHAR(512)    NOT NULL,
    created_at  TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX idx_egm_tenant_username
    ON employee_group_members (tenant_id, LOWER(username));

CREATE INDEX idx_egm_group
    ON employee_group_members (group_id);
