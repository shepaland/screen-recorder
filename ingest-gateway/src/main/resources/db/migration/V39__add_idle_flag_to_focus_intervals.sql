-- V39: Add is_idle flag to focus intervals for phantom activity detection
-- A focus interval is "idle" if no user input events (clicks, keyboard, scroll)
-- occurred during its started_at..ended_at window.

ALTER TABLE app_focus_intervals ADD COLUMN IF NOT EXISTS is_idle BOOLEAN DEFAULT false;

-- Index for efficient filtering of active-only intervals
CREATE INDEX IF NOT EXISTS idx_afi_tenant_user_idle ON app_focus_intervals (tenant_id, username, is_idle, started_at DESC, created_ts)
    WHERE is_idle = false;

-- Backfill: mark existing intervals as idle if no input events overlap
-- This runs once for historical data; new intervals will be marked at insert time
UPDATE app_focus_intervals afi
SET is_idle = true
WHERE NOT EXISTS (
    SELECT 1 FROM user_input_events uie
    WHERE uie.username = afi.username
      AND uie.device_id = afi.device_id
      AND uie.event_ts >= afi.started_at
      AND uie.event_ts < COALESCE(afi.ended_at, afi.started_at + interval '1 minute')
);
