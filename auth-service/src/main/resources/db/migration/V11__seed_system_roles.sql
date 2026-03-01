DO $$
DECLARE
    v_template_tenant UUID := '00000000-0000-0000-0000-000000000000';
    v_role_id UUID;
BEGIN
    -- Создаём template-тенант для хранения шаблонов ролей
    INSERT INTO tenants (id, name, slug, is_active)
    VALUES (v_template_tenant, 'System Template', 'system-template', FALSE)
    ON CONFLICT DO NOTHING;

    -- SUPER_ADMIN: все 27 разрешений
    INSERT INTO roles (id, tenant_id, code, name, description, is_system)
    VALUES (gen_random_uuid(), v_template_tenant, 'SUPER_ADMIN', 'Super Administrator',
            'Full system access. Cross-tenant management.', TRUE)
    RETURNING id INTO v_role_id;
    INSERT INTO role_permissions (role_id, permission_id)
    SELECT v_role_id, id FROM permissions;

    -- TENANT_ADMIN: все кроме TENANTS:CREATE
    INSERT INTO roles (id, tenant_id, code, name, description, is_system)
    VALUES (gen_random_uuid(), v_template_tenant, 'TENANT_ADMIN', 'Tenant Administrator',
            'Full access within own tenant.', TRUE)
    RETURNING id INTO v_role_id;
    INSERT INTO role_permissions (role_id, permission_id)
    SELECT v_role_id, id FROM permissions WHERE code != 'TENANTS:CREATE';

    -- MANAGER: users read, roles read, devices CRUD+command, recordings all, policies all, audit, dashboard
    INSERT INTO roles (id, tenant_id, code, name, description, is_system)
    VALUES (gen_random_uuid(), v_template_tenant, 'MANAGER', 'Manager',
            'Manages devices, policies, recordings. Read-only users/roles.', TRUE)
    RETURNING id INTO v_role_id;
    INSERT INTO role_permissions (role_id, permission_id)
    SELECT v_role_id, id FROM permissions
    WHERE code IN (
        'USERS:READ', 'ROLES:READ',
        'DEVICES:CREATE', 'DEVICES:READ', 'DEVICES:UPDATE', 'DEVICES:DELETE', 'DEVICES:COMMAND',
        'RECORDINGS:READ', 'RECORDINGS:PLAY', 'RECORDINGS:DELETE', 'RECORDINGS:EXPORT',
        'POLICIES:CREATE', 'POLICIES:READ', 'POLICIES:UPDATE', 'POLICIES:DELETE', 'POLICIES:PUBLISH',
        'AUDIT:READ', 'DASHBOARD:VIEW'
    );

    -- SUPERVISOR: devices read+command, recordings read+play+export, policies read, audit read, dashboard
    INSERT INTO roles (id, tenant_id, code, name, description, is_system)
    VALUES (gen_random_uuid(), v_template_tenant, 'SUPERVISOR', 'Supervisor',
            'Supervises operators. Views recordings, sends commands.', TRUE)
    RETURNING id INTO v_role_id;
    INSERT INTO role_permissions (role_id, permission_id)
    SELECT v_role_id, id FROM permissions
    WHERE code IN (
        'DEVICES:READ', 'DEVICES:COMMAND',
        'RECORDINGS:READ', 'RECORDINGS:PLAY', 'RECORDINGS:EXPORT',
        'POLICIES:READ', 'AUDIT:READ', 'DASHBOARD:VIEW'
    );

    -- OPERATOR: devices read (own), recordings read+play (own), dashboard
    INSERT INTO roles (id, tenant_id, code, name, description, is_system)
    VALUES (gen_random_uuid(), v_template_tenant, 'OPERATOR', 'Operator',
            'Contact center operator. Views own recordings.', TRUE)
    RETURNING id INTO v_role_id;
    INSERT INTO role_permissions (role_id, permission_id)
    SELECT v_role_id, id FROM permissions
    WHERE code IN (
        'DEVICES:READ', 'RECORDINGS:READ', 'RECORDINGS:PLAY', 'DASHBOARD:VIEW'
    );

    -- VIEWER: recordings read+play, dashboard
    INSERT INTO roles (id, tenant_id, code, name, description, is_system)
    VALUES (gen_random_uuid(), v_template_tenant, 'VIEWER', 'Viewer',
            'Read-only access to recordings and dashboard.', TRUE)
    RETURNING id INTO v_role_id;
    INSERT INTO role_permissions (role_id, permission_id)
    SELECT v_role_id, id FROM permissions
    WHERE code IN (
        'RECORDINGS:READ', 'RECORDINGS:PLAY', 'DASHBOARD:VIEW'
    );
END $$;
