-- V43: Username = Email, глобальная уникальность
-- ПРЕДУСЛОВИЕ: все дубликаты разрешены вручную (active duplicates = 0)

-- 1. Нормализация: username = LOWER(TRIM(email)) для всех пользователей
UPDATE users SET
    username = LOWER(TRIM(email)),
    email = LOWER(TRIM(email));

-- 2. Удалить per-tenant constraints
ALTER TABLE users DROP CONSTRAINT IF EXISTS uq_users_tenant_username;
ALTER TABLE users DROP CONSTRAINT IF EXISTS uq_users_tenant_email;

-- 3. Глобальный UNIQUE на username (= email)
ALTER TABLE users ADD CONSTRAINT uq_users_username_global UNIQUE (username);

-- 4. Глобальный UNIQUE на email
ALTER TABLE users ADD CONSTRAINT uq_users_email_global UNIQUE (email);

-- 5. CHECK: username всегда = email
ALTER TABLE users ADD CONSTRAINT chk_username_is_email CHECK (username = email);
