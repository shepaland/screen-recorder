ALTER TABLE users ALTER COLUMN password_hash DROP NOT NULL;
ALTER TABLE users ADD COLUMN IF NOT EXISTS auth_provider VARCHAR(20) NOT NULL DEFAULT 'password';
ALTER TABLE users ADD COLUMN IF NOT EXISTS settings JSONB NOT NULL DEFAULT '{}';
UPDATE users SET auth_provider = 'password' WHERE auth_provider IS NULL;
CREATE INDEX IF NOT EXISTS idx_users_auth_provider ON users (auth_provider);
