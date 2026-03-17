-- V40: Add recorded_at column to segments for accurate timeline positioning.
-- recorded_at = when the segment was actually recorded on the agent (from file creation time),
-- as opposed to created_ts which is when the server received the presign request.
-- For late uploads (pending segments uploaded after server recovery), recorded_at
-- preserves the original recording time.

ALTER TABLE segments ADD COLUMN recorded_at TIMESTAMPTZ;

-- Backfill: for existing segments, recorded_at = created_ts (best approximation)
UPDATE segments SET recorded_at = created_ts WHERE recorded_at IS NULL;
