-- V27: Add timezone column to devices and create indexes for recording archive queries

-- Add timezone column to devices table (default Europe/Moscow)
ALTER TABLE devices ADD COLUMN IF NOT EXISTS timezone VARCHAR(50) NOT NULL DEFAULT 'Europe/Moscow';

-- Add os_type column for device type icon (windows/macos/linux)
ALTER TABLE devices ADD COLUMN IF NOT EXISTS os_type VARCHAR(20);

-- Try to auto-detect os_type from existing os_version data
UPDATE devices SET os_type = CASE
    WHEN os_version ILIKE '%windows%' THEN 'windows'
    WHEN os_version ILIKE '%mac%' OR os_version ILIKE '%darwin%' THEN 'macos'
    WHEN os_version ILIKE '%linux%' OR os_version ILIKE '%ubuntu%' THEN 'linux'
    ELSE 'windows'
END WHERE os_type IS NULL;

-- Composite index for efficient day-grouped recording queries
CREATE INDEX IF NOT EXISTS idx_rs_device_tenant_started ON recording_sessions (device_id, tenant_id, started_ts DESC);

-- Index for segment lookup by session (already used but good to have explicit)
CREATE INDEX IF NOT EXISTS idx_seg_session_tenant_seq ON segments (session_id, tenant_id, sequence_num);
