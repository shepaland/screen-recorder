-- Fix SUPER_ADMIN password hash (Admin@12345, BCrypt cost 12)
UPDATE users
SET password_hash = '$2a$12$KxoJ5ozMA1KKNJxQkT/XtOQ4JSH9rV3HrnT.QAp5eGiSxrxJsQeJa'
WHERE username = 'superadmin'
  AND tenant_id = (SELECT id FROM tenants WHERE slug = 'prg-platform');
