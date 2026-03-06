-- V26_1: Fix test user password hash
-- The BCrypt hash in V26 was invalid for password Test@12345
-- This migration updates it with a correct hash

UPDATE users
SET password_hash = '$2b$12$FnZz0b.pme8TK7uwoTEnVu3HHGuH5y73dkHfDx.RUKJvWupunD85q'
WHERE username = 'test'
  AND email = 'test@test.com'
  AND EXISTS (SELECT 1 FROM tenants WHERE id = users.tenant_id AND slug = 'test-org');
