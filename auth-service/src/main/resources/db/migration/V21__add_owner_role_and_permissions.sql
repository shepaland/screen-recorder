-- Add OWNER role and new tenant management permissions
INSERT INTO permissions (id, code, name, description, resource, action, created_ts) VALUES
    (gen_random_uuid(), 'TENANTS:DELETE', 'Delete tenants', 'Permanently delete a tenant and all its data', 'TENANTS', 'DELETE', NOW()),
    (gen_random_uuid(), 'TENANTS:TRANSFER_OWNERSHIP', 'Transfer tenant ownership', 'Transfer OWNER role to another user', 'TENANTS', 'TRANSFER_OWNERSHIP', NOW())
ON CONFLICT (code) DO NOTHING;

DO $$
DECLARE
    v_template_tenant UUID := '00000000-0000-0000-0000-000000000000';
    v_role_id UUID;
BEGIN
    INSERT INTO roles (id, tenant_id, code, name, description, is_system)
    VALUES (gen_random_uuid(), v_template_tenant, 'OWNER', 'Tenant Owner',
            'Full access within own tenant including tenant settings and ownership transfer. One per tenant.', TRUE)
    RETURNING id INTO v_role_id;

    INSERT INTO role_permissions (role_id, permission_id)
    SELECT v_role_id, id FROM permissions
    WHERE code NOT IN ('TENANTS:CREATE')
    ON CONFLICT DO NOTHING;

    -- Add new permissions to SUPER_ADMIN template
    INSERT INTO role_permissions (role_id, permission_id)
    SELECT r.id, p.id
    FROM roles r, permissions p
    WHERE r.tenant_id = v_template_tenant
      AND r.code = 'SUPER_ADMIN'
      AND p.code IN ('TENANTS:DELETE', 'TENANTS:TRANSFER_OWNERSHIP')
    ON CONFLICT DO NOTHING;
END $$;

-- Create OWNER role in all existing tenants
DO $$
DECLARE
    v_template_tenant UUID := '00000000-0000-0000-0000-000000000000';
    v_tenant RECORD;
    v_new_role_id UUID;
    v_template_owner_role_id UUID;
BEGIN
    SELECT id INTO v_template_owner_role_id
    FROM roles WHERE tenant_id = v_template_tenant AND code = 'OWNER';

    IF v_template_owner_role_id IS NULL THEN
        RAISE EXCEPTION 'Template OWNER role not found';
    END IF;

    FOR v_tenant IN
        SELECT id FROM tenants WHERE id != v_template_tenant AND is_active = TRUE
    LOOP
        INSERT INTO roles (id, tenant_id, code, name, description, is_system)
        SELECT gen_random_uuid(), v_tenant.id, 'OWNER', 'Tenant Owner',
               'Full access within own tenant including tenant settings and ownership transfer. One per tenant.', TRUE
        WHERE NOT EXISTS (
            SELECT 1 FROM roles WHERE tenant_id = v_tenant.id AND code = 'OWNER'
        )
        RETURNING id INTO v_new_role_id;

        IF v_new_role_id IS NOT NULL THEN
            INSERT INTO role_permissions (role_id, permission_id)
            SELECT v_new_role_id, rp.permission_id
            FROM role_permissions rp
            WHERE rp.role_id = v_template_owner_role_id
            ON CONFLICT DO NOTHING;
        END IF;

        INSERT INTO role_permissions (role_id, permission_id)
        SELECT r.id, p.id
        FROM roles r, permissions p
        WHERE r.tenant_id = v_tenant.id
          AND r.code = 'SUPER_ADMIN'
          AND p.code IN ('TENANTS:DELETE', 'TENANTS:TRANSFER_OWNERSHIP')
        ON CONFLICT DO NOTHING;
    END LOOP;
END $$;
