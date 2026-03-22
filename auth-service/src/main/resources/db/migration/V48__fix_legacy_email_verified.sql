-- Fix legacy users: set email_verified = true for all existing users
-- New email-wizard users will have email_verified = false until they click the link
UPDATE users SET email_verified = true WHERE email_verified = false OR email_verified IS NULL;
