-- V31__add_catalogs_permissions.sql
-- Add CATALOGS:READ and CATALOGS:MANAGE permissions

-- 1. Insert permissions
INSERT INTO permissions (id, code, name, resource, action, description)
VALUES
  (gen_random_uuid(), 'CATALOGS:READ', 'View Catalogs', 'CATALOGS', 'READ', 'View application/site groups and aliases'),
  (gen_random_uuid(), 'CATALOGS:MANAGE', 'Manage Catalogs', 'CATALOGS', 'MANAGE', 'Create, update, delete application/site groups and aliases')
ON CONFLICT (code) DO NOTHING;

-- 2. Grant CATALOGS:READ to SUPER_ADMIN, OWNER, TENANT_ADMIN, MANAGER, SUPERVISOR
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
CROSS JOIN permissions p
WHERE r.code IN ('SUPER_ADMIN', 'OWNER', 'TENANT_ADMIN', 'MANAGER', 'SUPERVISOR')
  AND p.code = 'CATALOGS:READ'
ON CONFLICT DO NOTHING;

-- 3. Grant CATALOGS:MANAGE to SUPER_ADMIN, OWNER, TENANT_ADMIN
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
CROSS JOIN permissions p
WHERE r.code IN ('SUPER_ADMIN', 'OWNER', 'TENANT_ADMIN')
  AND p.code = 'CATALOGS:MANAGE'
ON CONFLICT DO NOTHING;
