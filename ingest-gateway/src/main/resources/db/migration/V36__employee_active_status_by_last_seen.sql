-- V34: Compute is_active based on last_seen_ts (active = last seen within 5 minutes)
-- Previously is_active was always true (never reset), now it's dynamic.

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
    BOOL_OR(dus.last_seen_ts > NOW() - INTERVAL '5 minutes') AS is_active
FROM device_user_sessions dus
GROUP BY dus.tenant_id, dus.username, dus.display_name, dus.windows_domain;
