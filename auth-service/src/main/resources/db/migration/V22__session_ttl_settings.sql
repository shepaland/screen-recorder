UPDATE tenants
SET settings = COALESCE(settings, '{}'::jsonb) || '{"session_ttl_max_days": 30}'::jsonb
WHERE NOT (COALESCE(settings, '{}'::jsonb) ? 'session_ttl_max_days');
