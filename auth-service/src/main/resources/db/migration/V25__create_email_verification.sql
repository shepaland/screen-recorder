-- V25: Email verification support for OTP registration/login
-- Creates email_verification_code table, disposable_email_domain table,
-- and adds email_verified/is_password_set columns to users.

-- 1. Table: email_verification_code
CREATE TABLE email_verification_code (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    email           VARCHAR(255) NOT NULL,
    code_hash       VARCHAR(64)  NOT NULL,
    purpose         VARCHAR(20)  NOT NULL DEFAULT 'register',
    fingerprint     VARCHAR(255) NOT NULL,
    attempts        INT          NOT NULL DEFAULT 0,
    max_attempts    INT          NOT NULL DEFAULT 10,
    is_used         BOOLEAN      NOT NULL DEFAULT FALSE,
    is_blocked      BOOLEAN      NOT NULL DEFAULT FALSE,
    blocked_until   TIMESTAMPTZ,
    expires_at      TIMESTAMPTZ  NOT NULL,
    created_ts      TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT chk_evc_purpose CHECK (purpose IN ('register', 'login'))
);

CREATE INDEX idx_evc_email_created ON email_verification_code (email, created_ts DESC);
CREATE INDEX idx_evc_email_active  ON email_verification_code (email, is_used, expires_at)
    WHERE is_used = FALSE;
CREATE INDEX idx_evc_expires       ON email_verification_code (expires_at)
    WHERE is_used = FALSE;

COMMENT ON TABLE email_verification_code IS 'OTP codes for email verification. Stores HMAC hash of code, fingerprint binding, attempt counter.';
COMMENT ON COLUMN email_verification_code.code_hash IS 'HMAC-SHA256(code, server_secret) hash of the 6-digit OTP code. Plain code is never stored.';
COMMENT ON COLUMN email_verification_code.fingerprint IS 'SHA-256(otp_session cookie + ":" + User-Agent). Binds code to browser session.';
COMMENT ON COLUMN email_verification_code.purpose IS 'Verification purpose: register or login.';

-- 2. Table: disposable_email_domain
CREATE TABLE disposable_email_domain (
    domain      VARCHAR(255) PRIMARY KEY,
    added_ts    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

COMMENT ON TABLE disposable_email_domain IS 'Blocklist of disposable email domains. Seeded from external list during migration.';

-- Seed: commonly known disposable domains (full seed in V25_1)
INSERT INTO disposable_email_domain (domain) VALUES
    ('mailinator.com'),
    ('guerrillamail.com'),
    ('tempmail.com'),
    ('throwaway.email'),
    ('yopmail.com'),
    ('10minutemail.com'),
    ('trashmail.com'),
    ('sharklasers.com'),
    ('grr.la'),
    ('guerrillamailblock.com'),
    ('tempail.com'),
    ('dispostable.com'),
    ('mailnesia.com'),
    ('maildrop.cc'),
    ('fakeinbox.com'),
    ('temp-mail.org'),
    ('mohmal.com'),
    ('getnada.com')
ON CONFLICT (domain) DO NOTHING;

-- 3. Modify users table: add email_verified and is_password_set
ALTER TABLE users ADD COLUMN IF NOT EXISTS email_verified BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE users ADD COLUMN IF NOT EXISTS is_password_set BOOLEAN NOT NULL DEFAULT TRUE;

-- Backward compatibility: set correct defaults for existing users
UPDATE users SET email_verified = TRUE WHERE auth_provider = 'oauth';
UPDATE users SET is_password_set = FALSE WHERE password_hash IS NULL;

-- Index for case-insensitive email lookup
CREATE INDEX IF NOT EXISTS idx_users_email_lower ON users (LOWER(email)) WHERE is_active = true;

COMMENT ON COLUMN users.email_verified IS 'Email confirmed via OTP or OAuth provider';
COMMENT ON COLUMN users.is_password_set IS 'User has set a password (true for password-users, false for email/oauth without password)';
