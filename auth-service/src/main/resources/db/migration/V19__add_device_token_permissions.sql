-- V19: Add new permissions for device registration tokens and recording management

INSERT INTO permissions (id, code, name, description, resource, action, created_ts) VALUES
    (gen_random_uuid(), 'DEVICE_TOKENS:CREATE', 'Create Device Registration Tokens',
     'Generate registration tokens for device enrollment', 'DEVICE_TOKENS', 'CREATE', NOW()),
    (gen_random_uuid(), 'DEVICE_TOKENS:READ', 'View Device Registration Tokens',
     'View existing registration tokens', 'DEVICE_TOKENS', 'READ', NOW()),
    (gen_random_uuid(), 'DEVICE_TOKENS:DELETE', 'Revoke Device Registration Tokens',
     'Deactivate registration tokens', 'DEVICE_TOKENS', 'DELETE', NOW()),
    (gen_random_uuid(), 'RECORDINGS:MANAGE', 'Manage Recording Sessions',
     'Start/stop recording sessions on devices', 'RECORDINGS', 'MANAGE', NOW());

-- Assign DEVICE_TOKENS:* to SUPER_ADMIN, TENANT_ADMIN, MANAGER in template tenant
DO $$
DECLARE
    v_template_tenant UUID := '00000000-0000-0000-0000-000000000000';
    v_perm_dt_create UUID;
    v_perm_dt_read UUID;
    v_perm_dt_delete UUID;
    v_perm_rec_manage UUID;
    v_role_id UUID;
BEGIN
    SELECT id INTO v_perm_dt_create FROM permissions WHERE code = 'DEVICE_TOKENS:CREATE';
    SELECT id INTO v_perm_dt_read FROM permissions WHERE code = 'DEVICE_TOKENS:READ';
    SELECT id INTO v_perm_dt_delete FROM permissions WHERE code = 'DEVICE_TOKENS:DELETE';
    SELECT id INTO v_perm_rec_manage FROM permissions WHERE code = 'RECORDINGS:MANAGE';

    -- SUPER_ADMIN gets all new permissions
    SELECT id INTO v_role_id FROM roles WHERE tenant_id = v_template_tenant AND code = 'SUPER_ADMIN';
    IF v_role_id IS NOT NULL THEN
        INSERT INTO role_permissions (role_id, permission_id)
        VALUES (v_role_id, v_perm_dt_create), (v_role_id, v_perm_dt_read),
               (v_role_id, v_perm_dt_delete), (v_role_id, v_perm_rec_manage)
        ON CONFLICT DO NOTHING;
    END IF;

    -- TENANT_ADMIN gets all new permissions
    SELECT id INTO v_role_id FROM roles WHERE tenant_id = v_template_tenant AND code = 'TENANT_ADMIN';
    IF v_role_id IS NOT NULL THEN
        INSERT INTO role_permissions (role_id, permission_id)
        VALUES (v_role_id, v_perm_dt_create), (v_role_id, v_perm_dt_read),
               (v_role_id, v_perm_dt_delete), (v_role_id, v_perm_rec_manage)
        ON CONFLICT DO NOTHING;
    END IF;

    -- MANAGER gets all new permissions
    SELECT id INTO v_role_id FROM roles WHERE tenant_id = v_template_tenant AND code = 'MANAGER';
    IF v_role_id IS NOT NULL THEN
        INSERT INTO role_permissions (role_id, permission_id)
        VALUES (v_role_id, v_perm_dt_create), (v_role_id, v_perm_dt_read),
               (v_role_id, v_perm_dt_delete), (v_role_id, v_perm_rec_manage)
        ON CONFLICT DO NOTHING;
    END IF;

    -- SUPERVISOR gets DEVICE_TOKENS:READ and RECORDINGS:MANAGE
    SELECT id INTO v_role_id FROM roles WHERE tenant_id = v_template_tenant AND code = 'SUPERVISOR';
    IF v_role_id IS NOT NULL THEN
        INSERT INTO role_permissions (role_id, permission_id)
        VALUES (v_role_id, v_perm_dt_read), (v_role_id, v_perm_rec_manage)
        ON CONFLICT DO NOTHING;
    END IF;

    -- Also update roles in existing tenants (prg-platform)
    FOR v_role_id IN
        SELECT r.id FROM roles r
        JOIN tenants t ON r.tenant_id = t.id
        WHERE t.slug != 'template' AND r.code IN ('SUPER_ADMIN', 'TENANT_ADMIN', 'MANAGER')
    LOOP
        INSERT INTO role_permissions (role_id, permission_id)
        VALUES (v_role_id, v_perm_dt_create), (v_role_id, v_perm_dt_read),
               (v_role_id, v_perm_dt_delete), (v_role_id, v_perm_rec_manage)
        ON CONFLICT DO NOTHING;
    END LOOP;

    FOR v_role_id IN
        SELECT r.id FROM roles r
        JOIN tenants t ON r.tenant_id = t.id
        WHERE t.slug != 'template' AND r.code = 'SUPERVISOR'
    LOOP
        INSERT INTO role_permissions (role_id, permission_id)
        VALUES (v_role_id, v_perm_dt_read), (v_role_id, v_perm_rec_manage)
        ON CONFLICT DO NOTHING;
    END LOOP;
END $$;
