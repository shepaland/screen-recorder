-- V24: Add soft delete support for devices
-- Allows devices to be "soft deleted" (hidden from default list) while preserving
-- all associated recordings. Auto-restores on heartbeat/login from agent.

-- 1. Add is_deleted flag and deleted_ts timestamp
ALTER TABLE devices
    ADD COLUMN is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN deleted_ts TIMESTAMPTZ;

-- 2. Add constraint: deleted_ts required when is_deleted=true
ALTER TABLE devices
    ADD CONSTRAINT chk_devices_deleted_ts
        CHECK (
            (is_deleted = FALSE AND deleted_ts IS NULL) OR
            (is_deleted = TRUE AND deleted_ts IS NOT NULL)
        );

-- 3. Partial index for fast filtering of non-deleted devices (default view)
CREATE INDEX idx_devices_tenant_not_deleted
    ON devices(tenant_id, created_ts DESC)
    WHERE is_deleted = FALSE;

-- 4. Partial index for listing only deleted devices
CREATE INDEX idx_devices_tenant_deleted
    ON devices(tenant_id, deleted_ts DESC)
    WHERE is_deleted = TRUE;

-- 5. Update existing tenant+status index to consider is_deleted
DROP INDEX IF EXISTS idx_devices_tenant_status;
CREATE INDEX idx_devices_tenant_status
    ON devices(tenant_id, status)
    WHERE is_deleted = FALSE;

-- 6. Index for finding devices by registration token (Feature 2: token-devices list)
CREATE INDEX idx_devices_registration_token
    ON devices(registration_token_id, tenant_id)
    WHERE registration_token_id IS NOT NULL;

COMMENT ON COLUMN devices.is_deleted IS 'Soft delete flag. TRUE = device hidden from default list. Auto-restored on heartbeat.';
COMMENT ON COLUMN devices.deleted_ts IS 'Timestamp of soft deletion. NULL when is_deleted=FALSE.';
