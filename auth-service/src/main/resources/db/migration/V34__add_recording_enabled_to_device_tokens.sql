-- V34: Add recording_enabled to device_registration_tokens + DEVICE_TOKENS:UPDATE permission

-- 1. Add recording_enabled column
ALTER TABLE device_registration_tokens
ADD COLUMN IF NOT EXISTS recording_enabled BOOLEAN NOT NULL DEFAULT TRUE;

-- 2. Add DEVICE_TOKENS:UPDATE permission
INSERT INTO permissions (id, code, name, resource, action, description)
VALUES (gen_random_uuid(), 'DEVICE_TOKENS:UPDATE', 'Update Device Token', 'DEVICE_TOKENS', 'UPDATE',
        'Update device registration token settings including recording toggle')
ON CONFLICT (code) DO NOTHING;

-- 3. Assign to SUPER_ADMIN, OWNER, TENANT_ADMIN
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE r.code IN ('SUPER_ADMIN', 'OWNER', 'TENANT_ADMIN')
  AND p.code = 'DEVICE_TOKENS:UPDATE'
ON CONFLICT DO NOTHING;
