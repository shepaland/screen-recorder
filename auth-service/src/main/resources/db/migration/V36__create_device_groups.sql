-- V36__create_device_groups.sql
-- Device groups with 2-level nesting

-- 1. Create device_groups table
CREATE TABLE device_groups (
    id              UUID            NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    tenant_id       UUID            NOT NULL REFERENCES tenants(id),
    parent_id       UUID            REFERENCES device_groups(id) ON DELETE CASCADE,
    name            VARCHAR(200)    NOT NULL,
    description     VARCHAR(1000),
    color           VARCHAR(7),
    sort_order      INTEGER         NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    created_by      UUID            REFERENCES users(id) ON DELETE SET NULL
);

-- 2. Indexes
CREATE INDEX idx_dg_tenant_parent ON device_groups (tenant_id, parent_id);
CREATE INDEX idx_dg_tenant_sort ON device_groups (tenant_id, sort_order);

-- Unique name within root groups (parent_id IS NULL)
CREATE UNIQUE INDEX uq_dg_tenant_name_root
    ON device_groups (tenant_id, LOWER(name))
    WHERE parent_id IS NULL;

-- Unique name within subgroups of the same parent
CREATE UNIQUE INDEX uq_dg_tenant_parent_name
    ON device_groups (tenant_id, parent_id, LOWER(name))
    WHERE parent_id IS NOT NULL;

-- 3. Depth constraint trigger: only 2 levels allowed
CREATE OR REPLACE FUNCTION check_device_group_depth()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.parent_id IS NOT NULL THEN
        IF EXISTS (
            SELECT 1 FROM device_groups
            WHERE id = NEW.parent_id AND parent_id IS NOT NULL
        ) THEN
            RAISE EXCEPTION 'Device group nesting depth cannot exceed 2 levels'
                USING ERRCODE = 'check_violation';
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_check_device_group_depth
    BEFORE INSERT OR UPDATE ON device_groups
    FOR EACH ROW
    EXECUTE FUNCTION check_device_group_depth();

-- 4. Add device_group_id FK to devices
ALTER TABLE devices ADD COLUMN device_group_id UUID REFERENCES device_groups(id) ON DELETE SET NULL;
CREATE INDEX idx_devices_group ON devices (device_group_id) WHERE device_group_id IS NOT NULL;

-- 5. Add DEVICES:MANAGE permission
INSERT INTO permissions (id, code, name, resource, action, description)
VALUES (gen_random_uuid(), 'DEVICES:MANAGE', 'Manage Device Groups', 'DEVICES', 'MANAGE',
        'Create, update, delete device groups and assign devices to groups')
ON CONFLICT (code) DO NOTHING;

-- 6. Grant DEVICES:MANAGE to SUPER_ADMIN, OWNER, TENANT_ADMIN
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
CROSS JOIN permissions p
WHERE r.code IN ('SUPER_ADMIN', 'OWNER', 'TENANT_ADMIN')
  AND p.code = 'DEVICES:MANAGE'
ON CONFLICT DO NOTHING;

-- 7. updated_at trigger
CREATE OR REPLACE FUNCTION update_device_groups_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_device_groups_updated_at
    BEFORE UPDATE ON device_groups
    FOR EACH ROW
    EXECUTE FUNCTION update_device_groups_updated_at();
