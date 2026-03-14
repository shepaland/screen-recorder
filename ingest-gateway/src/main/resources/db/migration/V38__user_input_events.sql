-- V38: User input events table for behavior audit (metadata enrichment Phase 1)
-- Stores mouse clicks, keyboard metrics, scroll events, clipboard events
-- Partitioned monthly by created_ts (same pattern as app_focus_intervals)

-- 1. Create partitioned table
CREATE TABLE user_input_events (
    id UUID NOT NULL,
    created_ts TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    tenant_id UUID NOT NULL,
    device_id UUID NOT NULL,
    username VARCHAR(256) NOT NULL,
    session_id UUID,

    -- Event identification
    event_type VARCHAR(30) NOT NULL,
    event_ts TIMESTAMPTZ NOT NULL,
    event_end_ts TIMESTAMPTZ,

    -- Mouse click fields (null for other types)
    click_x INTEGER,
    click_y INTEGER,
    click_button VARCHAR(10),
    click_type VARCHAR(10),

    -- UI element context (null for non-click events)
    ui_element_type VARCHAR(50),
    ui_element_name VARCHAR(200),
    ui_element_class VARCHAR(100),
    ui_automation_id VARCHAR(200),

    -- Keyboard metric fields
    keystroke_count INTEGER,
    has_typing_burst BOOLEAN,

    -- Scroll fields
    scroll_direction VARCHAR(10),
    scroll_total_delta INTEGER,
    scroll_event_count INTEGER,

    -- Clipboard fields
    clipboard_action VARCHAR(10),
    clipboard_content_type VARCHAR(20),
    clipboard_content_length INTEGER,

    -- Context
    process_name VARCHAR(512),
    window_title VARCHAR(2048),

    -- Video timecode binding
    segment_id UUID,
    segment_offset_ms INTEGER,

    -- Correlation
    correlation_id UUID,

    PRIMARY KEY (id, created_ts),

    CONSTRAINT chk_uie_event_type
        CHECK (event_type IN ('mouse_click', 'keyboard_metric', 'scroll', 'clipboard'))
) PARTITION BY RANGE (created_ts);

-- 2. Create monthly partitions (2026-03 through 2027-01)
CREATE TABLE user_input_events_2026_03 PARTITION OF user_input_events
    FOR VALUES FROM ('2026-03-01') TO ('2026-04-01');
CREATE TABLE user_input_events_2026_04 PARTITION OF user_input_events
    FOR VALUES FROM ('2026-04-01') TO ('2026-05-01');
CREATE TABLE user_input_events_2026_05 PARTITION OF user_input_events
    FOR VALUES FROM ('2026-05-01') TO ('2026-06-01');
CREATE TABLE user_input_events_2026_06 PARTITION OF user_input_events
    FOR VALUES FROM ('2026-06-01') TO ('2026-07-01');
CREATE TABLE user_input_events_2026_07 PARTITION OF user_input_events
    FOR VALUES FROM ('2026-07-01') TO ('2026-08-01');
CREATE TABLE user_input_events_2026_08 PARTITION OF user_input_events
    FOR VALUES FROM ('2026-08-01') TO ('2026-09-01');
CREATE TABLE user_input_events_2026_09 PARTITION OF user_input_events
    FOR VALUES FROM ('2026-09-01') TO ('2026-10-01');
CREATE TABLE user_input_events_2026_10 PARTITION OF user_input_events
    FOR VALUES FROM ('2026-10-01') TO ('2026-11-01');
CREATE TABLE user_input_events_2026_11 PARTITION OF user_input_events
    FOR VALUES FROM ('2026-11-01') TO ('2026-12-01');
CREATE TABLE user_input_events_2026_12 PARTITION OF user_input_events
    FOR VALUES FROM ('2026-12-01') TO ('2027-01-01');
CREATE TABLE user_input_events_2027_01 PARTITION OF user_input_events
    FOR VALUES FROM ('2027-01-01') TO ('2027-02-01');

-- 3. Indexes (on parent, inherited by partitions)
CREATE INDEX idx_uie_tenant_user_ts ON user_input_events (tenant_id, username, event_ts DESC, created_ts);
CREATE INDEX idx_uie_device_ts ON user_input_events (device_id, event_ts DESC, created_ts);
CREATE INDEX idx_uie_tenant_type_ts ON user_input_events (tenant_id, event_type, event_ts DESC, created_ts);
CREATE INDEX idx_uie_session_ts ON user_input_events (session_id, event_ts DESC, created_ts) WHERE session_id IS NOT NULL;
CREATE INDEX idx_uie_segment ON user_input_events (segment_id, segment_offset_ms, created_ts) WHERE segment_id IS NOT NULL;

-- 4. Extend app_focus_intervals with window geometry
ALTER TABLE app_focus_intervals
    ADD COLUMN IF NOT EXISTS window_x INTEGER,
    ADD COLUMN IF NOT EXISTS window_y INTEGER,
    ADD COLUMN IF NOT EXISTS window_width INTEGER,
    ADD COLUMN IF NOT EXISTS window_height INTEGER,
    ADD COLUMN IF NOT EXISTS is_maximized BOOLEAN DEFAULT false,
    ADD COLUMN IF NOT EXISTS is_fullscreen BOOLEAN DEFAULT false,
    ADD COLUMN IF NOT EXISTS monitor_index INTEGER DEFAULT 0;
