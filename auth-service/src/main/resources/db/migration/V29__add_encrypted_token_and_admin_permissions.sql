-- 1. Add encrypted_token column for token reveal feature
ALTER TABLE device_registration_tokens
ADD COLUMN IF NOT EXISTS encrypted_token TEXT;

COMMENT ON COLUMN device_registration_tokens.encrypted_token
IS 'AES-256-GCM encrypted raw token for re-reveal. NULL for tokens created before V29.';

-- 2. Add new permissions for admin-only operations
INSERT INTO permissions (id, code, resource, action, description)
VALUES
  (gen_random_uuid(), 'DEVICE_TOKENS:REVEAL', 'DEVICE_TOKENS', 'REVEAL', 'Reveal raw device registration token'),
  (gen_random_uuid(), 'DEVICE_TOKENS:HARD_DELETE', 'DEVICE_TOKENS', 'HARD_DELETE', 'Permanently delete device registration token'),
  (gen_random_uuid(), 'USERS:HARD_DELETE', 'USERS', 'HARD_DELETE', 'Permanently delete user')
ON CONFLICT (code) DO NOTHING;

-- 3. Assign new permissions to SUPER_ADMIN, OWNER, TENANT_ADMIN roles
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
CROSS JOIN permissions p
WHERE r.code IN ('SUPER_ADMIN', 'OWNER', 'TENANT_ADMIN')
  AND p.code IN ('DEVICE_TOKENS:REVEAL', 'DEVICE_TOKENS:HARD_DELETE', 'USERS:HARD_DELETE')
ON CONFLICT DO NOTHING;

-- 4. Make device_registration_tokens.created_by nullable for cascade support
ALTER TABLE device_registration_tokens
ALTER COLUMN created_by DROP NOT NULL;
