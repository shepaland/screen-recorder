-- V26: Seed test user and test-org tenant for Windows Agent testing
-- Idempotent: checks for existing records before inserting

DO $$
DECLARE
    v_template_tenant UUID := '00000000-0000-0000-0000-000000000000';
    v_tenant_id UUID;
    v_user_id UUID;
    v_role_id UUID;
    v_src_role RECORD;
    v_new_role_id UUID;
BEGIN
    -- 1. Create tenant 'test-org' if not exists
    SELECT id INTO v_tenant_id FROM tenants WHERE slug = 'test-org';

    IF v_tenant_id IS NULL THEN
        INSERT INTO tenants (id, name, slug, is_active, settings)
        VALUES (
            gen_random_uuid(),
            'Test Organization',
            'test-org',
            TRUE,
            '{"max_users": 100, "max_retention_days": 90}'::jsonb
        )
        RETURNING id INTO v_tenant_id;

        -- 2. Copy system roles from template tenant into new tenant
        FOR v_src_role IN
            SELECT r.id AS src_role_id, r.code, r.name, r.description, r.is_system
            FROM roles r
            WHERE r.tenant_id = v_template_tenant
        LOOP
            -- Skip if role already exists in this tenant (defensive)
            IF NOT EXISTS (SELECT 1 FROM roles WHERE tenant_id = v_tenant_id AND code = v_src_role.code) THEN
                INSERT INTO roles (id, tenant_id, code, name, description, is_system)
                VALUES (gen_random_uuid(), v_tenant_id, v_src_role.code, v_src_role.name,
                        v_src_role.description, v_src_role.is_system)
                RETURNING id INTO v_new_role_id;

                -- Copy permission bindings
                INSERT INTO role_permissions (role_id, permission_id)
                SELECT v_new_role_id, rp.permission_id
                FROM role_permissions rp
                WHERE rp.role_id = v_src_role.src_role_id;
            END IF;
        END LOOP;

        RAISE NOTICE 'Created tenant test-org: id=%', v_tenant_id;
    ELSE
        RAISE NOTICE 'Tenant test-org already exists: id=%', v_tenant_id;
    END IF;

    -- 3. Create test user if not exists
    SELECT id INTO v_user_id FROM users WHERE tenant_id = v_tenant_id AND username = 'test';

    IF v_user_id IS NULL THEN
        -- Password: Test@12345 (BCrypt cost 12)
        INSERT INTO users (id, tenant_id, username, email, password_hash,
                           first_name, last_name, is_active,
                           auth_provider, email_verified, is_password_set)
        VALUES (
            gen_random_uuid(),
            v_tenant_id,
            'test',
            'test@test.com',
            '$2a$12$LJ3m4ys3GZb.ER2lPWMx0.VDfKGwHDgDGcRCxMqMb1pBqX3nBzlQm',
            'Test',
            'User',
            TRUE,
            'password',
            TRUE,
            TRUE
        )
        RETURNING id INTO v_user_id;

        -- 4. Assign OWNER role
        SELECT id INTO v_role_id FROM roles WHERE tenant_id = v_tenant_id AND code = 'OWNER';

        IF v_role_id IS NULL THEN
            -- Fallback: try TENANT_ADMIN if OWNER not available
            SELECT id INTO v_role_id FROM roles WHERE tenant_id = v_tenant_id AND code = 'TENANT_ADMIN';
        END IF;

        IF v_role_id IS NOT NULL THEN
            INSERT INTO user_roles (user_id, role_id)
            VALUES (v_user_id, v_role_id)
            ON CONFLICT DO NOTHING;
        ELSE
            RAISE WARNING 'No OWNER or TENANT_ADMIN role found for tenant test-org';
        END IF;

        RAISE NOTICE 'Created test user: id=%, role_id=%', v_user_id, v_role_id;
    ELSE
        RAISE NOTICE 'Test user already exists: id=%', v_user_id;
    END IF;
END $$;
