-- V32: Add is_browser_group flag to app_groups
-- Browser groups show site_groups instead of apps in timeline view.
-- This replaces the hardcoded browser detection with a configurable flag.

ALTER TABLE app_groups
    ADD COLUMN IF NOT EXISTS is_browser_group BOOLEAN NOT NULL DEFAULT false;

COMMENT ON COLUMN app_groups.is_browser_group IS
    'If true, this APP group is a browser group: timeline shows site_groups (visited pages) instead of individual apps';

-- Partial index for quick lookup of browser groups per tenant
CREATE INDEX IF NOT EXISTS idx_app_groups_browser
    ON app_groups (tenant_id, is_browser_group)
    WHERE is_browser_group = true;

-- Auto-mark existing "Browsers" seed groups as browser groups
UPDATE app_groups
SET is_browser_group = true
WHERE group_type = 'APP'
  AND LOWER(name) IN ('browsers', 'браузеры')
  AND is_browser_group = false;
