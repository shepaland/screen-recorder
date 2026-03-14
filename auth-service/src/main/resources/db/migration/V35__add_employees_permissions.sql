-- V35__add_employees_permissions.sql
-- Add EMPLOYEES:READ and EMPLOYEES:MANAGE permissions

-- 1. Insert permissions
INSERT INTO permissions (id, code, name, resource, action, description)
VALUES
  (gen_random_uuid(), 'EMPLOYEES:READ', 'View Employees', 'EMPLOYEES', 'READ', 'View employee groups and group members'),
  (gen_random_uuid(), 'EMPLOYEES:MANAGE', 'Manage Employees', 'EMPLOYEES', 'MANAGE', 'Create, update, delete employee groups and manage group membership')
ON CONFLICT (code) DO NOTHING;

-- 2. Grant EMPLOYEES:READ to SUPER_ADMIN, OWNER, TENANT_ADMIN, MANAGER, SUPERVISOR
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
CROSS JOIN permissions p
WHERE r.code IN ('SUPER_ADMIN', 'OWNER', 'TENANT_ADMIN', 'MANAGER', 'SUPERVISOR')
  AND p.code = 'EMPLOYEES:READ'
ON CONFLICT DO NOTHING;

-- 3. Grant EMPLOYEES:MANAGE to SUPER_ADMIN, OWNER, TENANT_ADMIN
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
CROSS JOIN permissions p
WHERE r.code IN ('SUPER_ADMIN', 'OWNER', 'TENANT_ADMIN')
  AND p.code = 'EMPLOYEES:MANAGE'
ON CONFLICT DO NOTHING;
