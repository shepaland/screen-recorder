-- Создание первого тенанта и SUPER_ADMIN пользователя
DO $$
DECLARE
    v_tenant_id UUID;
    v_user_id UUID;
    v_role_id UUID;
    v_template_tenant UUID := '00000000-0000-0000-0000-000000000000';
    v_src_role RECORD;
    v_new_role_id UUID;
BEGIN
    -- 1. Создаём первый рабочий тенант
    INSERT INTO tenants (id, name, slug, is_active, settings)
    VALUES (
        gen_random_uuid(),
        'PRG Platform',
        'prg-platform',
        TRUE,
        '{"max_users": 1000, "max_retention_days": 365}'::jsonb
    )
    RETURNING id INTO v_tenant_id;

    -- 2. Копируем системные роли из template-тенанта в новый тенант
    FOR v_src_role IN
        SELECT r.id as src_role_id, r.code, r.name, r.description, r.is_system
        FROM roles r
        WHERE r.tenant_id = v_template_tenant
    LOOP
        INSERT INTO roles (id, tenant_id, code, name, description, is_system)
        VALUES (gen_random_uuid(), v_tenant_id, v_src_role.code, v_src_role.name, v_src_role.description, v_src_role.is_system)
        RETURNING id INTO v_new_role_id;

        -- Копируем permission bindings
        INSERT INTO role_permissions (role_id, permission_id)
        SELECT v_new_role_id, rp.permission_id
        FROM role_permissions rp
        WHERE rp.role_id = v_src_role.src_role_id;
    END LOOP;

    -- 3. Создаём SUPER_ADMIN пользователя
    -- Пароль: Admin@12345 (BCrypt hash с strength 12)
    INSERT INTO users (id, tenant_id, username, email, password_hash, first_name, last_name, is_active)
    VALUES (
        gen_random_uuid(),
        v_tenant_id,
        'superadmin',
        'admin@prg-platform.com',
        '$2a$12$LJ3m4ys3uz0vMbpSjrKVaOMBkPMgOoB1Yxj1m3KVZN..CxFRsCCOy',
        'Super',
        'Admin',
        TRUE
    )
    RETURNING id INTO v_user_id;

    -- 4. Назначаем роль SUPER_ADMIN
    SELECT id INTO v_role_id FROM roles WHERE tenant_id = v_tenant_id AND code = 'SUPER_ADMIN';

    INSERT INTO user_roles (user_id, role_id)
    VALUES (v_user_id, v_role_id);
END $$;
