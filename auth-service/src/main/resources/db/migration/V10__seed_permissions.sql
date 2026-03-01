-- 27 атомарных разрешений
INSERT INTO permissions (id, code, name, resource, action) VALUES
-- Users
(gen_random_uuid(), 'USERS:CREATE',      'Create users',         'USERS',      'CREATE'),
(gen_random_uuid(), 'USERS:READ',        'View users',           'USERS',      'READ'),
(gen_random_uuid(), 'USERS:UPDATE',      'Update users',         'USERS',      'UPDATE'),
(gen_random_uuid(), 'USERS:DELETE',      'Delete users',         'USERS',      'DELETE'),
-- Roles
(gen_random_uuid(), 'ROLES:CREATE',      'Create roles',         'ROLES',      'CREATE'),
(gen_random_uuid(), 'ROLES:READ',        'View roles',           'ROLES',      'READ'),
(gen_random_uuid(), 'ROLES:UPDATE',      'Update roles',         'ROLES',      'UPDATE'),
(gen_random_uuid(), 'ROLES:DELETE',      'Delete roles',         'ROLES',      'DELETE'),
-- Devices
(gen_random_uuid(), 'DEVICES:CREATE',    'Register devices',     'DEVICES',    'CREATE'),
(gen_random_uuid(), 'DEVICES:READ',      'View devices',         'DEVICES',    'READ'),
(gen_random_uuid(), 'DEVICES:UPDATE',    'Update devices',       'DEVICES',    'UPDATE'),
(gen_random_uuid(), 'DEVICES:DELETE',    'Delete devices',       'DEVICES',    'DELETE'),
(gen_random_uuid(), 'DEVICES:COMMAND',   'Send commands',        'DEVICES',    'COMMAND'),
-- Recordings
(gen_random_uuid(), 'RECORDINGS:READ',   'View recordings',      'RECORDINGS', 'READ'),
(gen_random_uuid(), 'RECORDINGS:PLAY',   'Play recordings',      'RECORDINGS', 'PLAY'),
(gen_random_uuid(), 'RECORDINGS:DELETE', 'Delete recordings',    'RECORDINGS', 'DELETE'),
(gen_random_uuid(), 'RECORDINGS:EXPORT', 'Export recordings',    'RECORDINGS', 'EXPORT'),
-- Policies
(gen_random_uuid(), 'POLICIES:CREATE',   'Create policies',      'POLICIES',   'CREATE'),
(gen_random_uuid(), 'POLICIES:READ',     'View policies',        'POLICIES',   'READ'),
(gen_random_uuid(), 'POLICIES:UPDATE',   'Update policies',      'POLICIES',   'UPDATE'),
(gen_random_uuid(), 'POLICIES:DELETE',   'Delete policies',      'POLICIES',   'DELETE'),
(gen_random_uuid(), 'POLICIES:PUBLISH',  'Publish policies',     'POLICIES',   'PUBLISH'),
-- Audit
(gen_random_uuid(), 'AUDIT:READ',        'View audit logs',      'AUDIT',      'READ'),
-- Tenants
(gen_random_uuid(), 'TENANTS:CREATE',    'Create tenants',       'TENANTS',    'CREATE'),
(gen_random_uuid(), 'TENANTS:READ',      'View tenants',         'TENANTS',    'READ'),
(gen_random_uuid(), 'TENANTS:UPDATE',    'Update tenants',       'TENANTS',    'UPDATE'),
-- Dashboard
(gen_random_uuid(), 'DASHBOARD:VIEW',    'View dashboard',       'DASHBOARD',  'VIEW')
ON CONFLICT (code) DO NOTHING;
