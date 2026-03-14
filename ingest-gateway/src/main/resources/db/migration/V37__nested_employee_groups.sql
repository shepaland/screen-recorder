-- V37__nested_employee_groups.sql
-- Add parent_id for 2-level hierarchy in employee_groups
-- Allow employees to belong to multiple groups

-- 1. Add parent_id for nesting (max depth: 2 levels)
ALTER TABLE employee_groups ADD COLUMN parent_id UUID REFERENCES employee_groups(id) ON DELETE CASCADE;
CREATE INDEX idx_eg_parent ON employee_groups (parent_id) WHERE parent_id IS NOT NULL;

-- 2. Drop old unique constraint (one employee = one group)
DROP INDEX IF EXISTS idx_egm_tenant_username;

-- 3. New: one employee per group (but can be in multiple groups)
CREATE UNIQUE INDEX idx_egm_tenant_group_username
    ON employee_group_members (tenant_id, group_id, LOWER(username));

-- 4. Unique name per level: root groups unique per tenant, child groups unique per parent
DROP INDEX IF EXISTS idx_eg_tenant_name;
CREATE UNIQUE INDEX idx_eg_tenant_name_root
    ON employee_groups (tenant_id, LOWER(name)) WHERE parent_id IS NULL;
CREATE UNIQUE INDEX idx_eg_tenant_name_child
    ON employee_groups (parent_id, LOWER(name)) WHERE parent_id IS NOT NULL;
