-- V42__clipboard_content_hash.sql
-- Add content_hash column for clipboard event deduplication (SHA-256, sent by new agent)
ALTER TABLE user_input_events ADD COLUMN content_hash TEXT;

CREATE INDEX idx_input_events_clipboard_hash
  ON user_input_events (tenant_id, content_hash)
  WHERE event_type = 'clipboard' AND content_hash IS NOT NULL;
