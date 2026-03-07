-- V29__user_activity_tracking.sql
-- User Activity Tracking: focus intervals, user sessions, extended audit events

-- 1. device_user_sessions
CREATE TABLE device_user_sessions (
    id              UUID            NOT NULL DEFAULT gen_random_uuid(),
    tenant_id       UUID            NOT NULL,
    device_id       UUID            NOT NULL REFERENCES devices(id),
    username        VARCHAR(256)    NOT NULL,
    windows_domain  VARCHAR(256),
    display_name    VARCHAR(512),
    first_seen_ts   TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    last_seen_ts    TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    is_active       BOOLEAN         NOT NULL DEFAULT true,
    created_ts      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_ts      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    PRIMARY KEY (id)
);

CREATE UNIQUE INDEX idx_dus_device_user_active
    ON device_user_sessions (tenant_id, device_id, username)
    WHERE is_active = true;

CREATE INDEX idx_dus_tenant_username
    ON device_user_sessions (tenant_id, username, last_seen_ts DESC);

CREATE INDEX idx_dus_tenant_last_seen
    ON device_user_sessions (tenant_id, last_seen_ts DESC);

-- 2. app_focus_intervals (partitioned by month)
CREATE TABLE app_focus_intervals (
    id              UUID            NOT NULL DEFAULT gen_random_uuid(),
    created_ts      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    tenant_id       UUID            NOT NULL,
    device_id       UUID            NOT NULL,
    username        VARCHAR(256)    NOT NULL,
    session_id      UUID,
    process_name    VARCHAR(512)    NOT NULL,
    window_title    VARCHAR(2048)   NOT NULL DEFAULT '',
    is_browser      BOOLEAN         NOT NULL DEFAULT false,
    browser_name    VARCHAR(100),
    domain          VARCHAR(512),
    started_at      TIMESTAMPTZ     NOT NULL,
    ended_at        TIMESTAMPTZ,
    duration_ms     INTEGER         NOT NULL DEFAULT 0
        CHECK (duration_ms >= 0),
    category        VARCHAR(50)     NOT NULL DEFAULT 'uncategorized',
    correlation_id  UUID,
    PRIMARY KEY (id, created_ts)
) PARTITION BY RANGE (created_ts);

CREATE TABLE app_focus_intervals_2026_03 PARTITION OF app_focus_intervals FOR VALUES FROM ('2026-03-01') TO ('2026-04-01');
CREATE TABLE app_focus_intervals_2026_04 PARTITION OF app_focus_intervals FOR VALUES FROM ('2026-04-01') TO ('2026-05-01');
CREATE TABLE app_focus_intervals_2026_05 PARTITION OF app_focus_intervals FOR VALUES FROM ('2026-05-01') TO ('2026-06-01');
CREATE TABLE app_focus_intervals_2026_06 PARTITION OF app_focus_intervals FOR VALUES FROM ('2026-06-01') TO ('2026-07-01');
CREATE TABLE app_focus_intervals_2026_07 PARTITION OF app_focus_intervals FOR VALUES FROM ('2026-07-01') TO ('2026-08-01');
CREATE TABLE app_focus_intervals_2026_08 PARTITION OF app_focus_intervals FOR VALUES FROM ('2026-08-01') TO ('2026-09-01');
CREATE TABLE app_focus_intervals_2026_09 PARTITION OF app_focus_intervals FOR VALUES FROM ('2026-09-01') TO ('2026-10-01');
CREATE TABLE app_focus_intervals_2026_10 PARTITION OF app_focus_intervals FOR VALUES FROM ('2026-10-01') TO ('2026-11-01');
CREATE TABLE app_focus_intervals_2026_11 PARTITION OF app_focus_intervals FOR VALUES FROM ('2026-11-01') TO ('2026-12-01');
CREATE TABLE app_focus_intervals_2026_12 PARTITION OF app_focus_intervals FOR VALUES FROM ('2026-12-01') TO ('2027-01-01');

CREATE INDEX idx_afi_tenant_user_started
    ON app_focus_intervals (tenant_id, username, started_at DESC, created_ts);

CREATE INDEX idx_afi_device_started
    ON app_focus_intervals (device_id, started_at DESC, created_ts);

CREATE INDEX idx_afi_tenant_user_process
    ON app_focus_intervals (tenant_id, username, process_name, started_at DESC, created_ts);

CREATE INDEX idx_afi_tenant_user_domain
    ON app_focus_intervals (tenant_id, username, domain, started_at DESC, created_ts)
    WHERE is_browser = true;

CREATE UNIQUE INDEX idx_afi_id_unique
    ON app_focus_intervals (id, created_ts);

-- 3. Extend device_audit_events with username and new event types
ALTER TABLE device_audit_events DROP CONSTRAINT IF EXISTS device_audit_events_event_type_check;

ALTER TABLE device_audit_events ADD CONSTRAINT device_audit_events_event_type_check
    CHECK (event_type IN (
        'SESSION_LOCK', 'SESSION_UNLOCK',
        'SESSION_LOGON', 'SESSION_LOGOFF',
        'PROCESS_START', 'PROCESS_STOP',
        'USER_LOGON', 'USER_LOGOFF'
    ));

ALTER TABLE device_audit_events ADD COLUMN IF NOT EXISTS username VARCHAR(256);

CREATE INDEX IF NOT EXISTS idx_dae_tenant_username
    ON device_audit_events (tenant_id, username, event_ts DESC, created_ts);

-- 4. Aggregation view: tenant users
CREATE OR REPLACE VIEW v_tenant_users AS
SELECT
    dus.tenant_id,
    dus.username,
    dus.display_name,
    dus.windows_domain,
    ARRAY_AGG(DISTINCT dus.device_id) AS device_ids,
    COUNT(DISTINCT dus.device_id) AS device_count,
    MIN(dus.first_seen_ts) AS first_seen_ts,
    MAX(dus.last_seen_ts) AS last_seen_ts,
    BOOL_OR(dus.is_active) AS is_active
FROM device_user_sessions dus
GROUP BY dus.tenant_id, dus.username, dus.display_name, dus.windows_domain;
