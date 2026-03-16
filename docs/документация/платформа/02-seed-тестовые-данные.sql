-- =============================================================================
-- Seed Test Data for Kadero Screen Recorder
-- Tenant: "Тест 1" (386597e2-7c48-4668-a101-711308a6a5b2)
-- Period: 2026-02-24 .. 2026-03-10 (working days only, 09:00-18:00 MSK)
-- 15 users, 15 devices, ~100 recording sessions, ~7000 focus intervals
-- 
-- IMPORTANT: INSERT only, no DELETE/TRUNCATE. Safe to run on existing data.
-- Partitioned tables (app_focus_intervals, device_audit_events): partitions
-- 2026_02 and 2026_03 must already exist.
-- =============================================================================

BEGIN;

-- Create missing partition for February 2026
CREATE TABLE IF NOT EXISTS app_focus_intervals_2026_02
    PARTITION OF app_focus_intervals
    FOR VALUES FROM ('2026-02-01') TO ('2026-03-01');

-- ============================================================
-- 0. Helper: temporary table with user definitions
-- ============================================================
CREATE TEMPORARY TABLE _seed_users (
    idx         int PRIMARY KEY,
    username    varchar,
    display_name varchar,
    hostname    varchar,
    ip_address  varchar
) ON COMMIT DROP;

INSERT INTO _seed_users (idx, username, display_name, hostname, ip_address) VALUES
(1,  'ivanov.a',     'Иванов Алексей',      'WS-001', '192.168.1.101'),
(2,  'petrova.m',    'Петрова Мария',        'WS-002', '192.168.1.102'),
(3,  'sidorov.d',    'Сидоров Дмитрий',      'WS-003', '192.168.1.103'),
(4,  'kozlova.e',    'Козлова Елена',        'WS-004', '192.168.1.104'),
(5,  'novikov.s',    'Новиков Сергей',       'WS-005', '192.168.1.105'),
(6,  'morozova.a',   'Морозова Анна',        'WS-006', '192.168.1.106'),
(7,  'volkov.i',     'Волков Игорь',         'WS-007', '192.168.1.107'),
(8,  'sokolova.n',   'Соколова Наталья',     'WS-008', '192.168.1.108'),
(9,  'lebedev.v',    'Лебедев Виктор',       'WS-009', '192.168.1.109'),
(10, 'kuznetsova.o', 'Кузнецова Ольга',      'WS-010', '192.168.1.110'),
(11, 'popov.r',      'Попов Роман',          'WS-011', '192.168.1.111'),
(12, 'andreeva.t',   'Андреева Татьяна',     'WS-012', '192.168.1.112'),
(13, 'orlov.k',      'Орлов Константин',     'WS-013', '192.168.1.113'),
(14, 'fedorova.y',   'Фёдорова Юлия',       'WS-014', '192.168.1.114'),
(15, 'baranov.p',    'Баранов Павел',        'WS-015', '192.168.1.115');

-- ============================================================
-- 1. devices (15 rows)
-- ============================================================
INSERT INTO devices (
    id, tenant_id, registration_token_id,
    hostname, os_version, agent_version, hardware_id,
    status, last_heartbeat_ts, ip_address,
    settings, is_active, created_ts, updated_ts,
    is_deleted, timezone, os_type
)
SELECT
    gen_random_uuid(),
    '386597e2-7c48-4668-a101-711308a6a5b2'::uuid,
    '0fbd3b54-21bc-4509-8cf4-e11d6c964550'::uuid,
    u.hostname,
    'Microsoft Windows NT 10.0.26200.0',
    '1.0.0',
    gen_random_uuid()::text,
    'online',
    NOW(),
    u.ip_address,
    '{"quality":"low","auto_start":true,"resolution":"720p","capture_fps":1,"recording_enabled":true,"segment_duration_sec":60,"session_max_duration_hours":24}'::jsonb,
    true,
    (NOW() - interval '15 days'),
    NOW(),
    false,
    'Europe/Moscow',
    'windows'
FROM _seed_users u
ORDER BY u.idx;

-- ============================================================
-- 2. device_user_sessions (15 rows, one per device)
-- ============================================================
INSERT INTO device_user_sessions (
    id, tenant_id, device_id,
    username, windows_domain, display_name,
    first_seen_ts, last_seen_ts, is_active,
    created_ts, updated_ts
)
SELECT
    gen_random_uuid(),
    '386597e2-7c48-4668-a101-711308a6a5b2'::uuid,
    d.id,
    'Kadero' || E'\\' || u.username,
    'Kadero',
    u.display_name,
    (NOW() - interval '15 days'),
    NOW(),
    true,
    (NOW() - interval '15 days'),
    NOW()
FROM _seed_users u
JOIN devices d ON d.hostname = u.hostname
    AND d.tenant_id = '386597e2-7c48-4668-a101-711308a6a5b2'::uuid;

-- ============================================================
-- 3. recording_sessions
--    For each device: one session per working day, varying start/duration.
--    Working days between 2026-02-24 and 2026-03-10.
-- ============================================================

-- Helper: working days
CREATE TEMPORARY TABLE _seed_workdays (
    day_date date PRIMARY KEY,
    day_idx  int
) ON COMMIT DROP;

INSERT INTO _seed_workdays (day_date, day_idx)
SELECT d::date, ROW_NUMBER() OVER (ORDER BY d)::int
FROM generate_series('2026-02-24'::date, '2026-03-10'::date, '1 day') AS d
WHERE EXTRACT(DOW FROM d) BETWEEN 1 AND 5;

-- Session templates: vary start hour and duration per (device_idx, day_idx)
-- We generate 1 session per device per workday = ~11 days * 15 devices = ~165 sessions
INSERT INTO recording_sessions (
    id, tenant_id, device_id, user_id,
    status, started_ts, ended_ts,
    segment_count, total_bytes, total_duration_ms,
    metadata, created_ts, updated_ts
)
SELECT
    gen_random_uuid(),
    '386597e2-7c48-4668-a101-711308a6a5b2'::uuid,
    d.id,
    NULL,
    'completed',
    -- start: day + 09:00 MSK + offset based on user idx (0..90 min) + day variation
    (w.day_date + interval '9 hours'
        + (((u.idx * 7 + w.day_idx * 13) % 90) || ' minutes')::interval
    ) AT TIME ZONE 'Europe/Moscow',
    -- end: start + duration (1-4 hours, varies by user+day)
    (w.day_date + interval '9 hours'
        + (((u.idx * 7 + w.day_idx * 13) % 90) || ' minutes')::interval
        + (1 + ((u.idx * 3 + w.day_idx * 7) % 4)) * interval '1 hour'
    ) AT TIME ZONE 'Europe/Moscow',
    -- segment_count = duration_hours * 60
    (1 + ((u.idx * 3 + w.day_idx * 7) % 4)) * 60,
    -- total_bytes = segment_count * 500000
    (1 + ((u.idx * 3 + w.day_idx * 7) % 4)) * 60 * 500000::bigint,
    -- total_duration_ms = segment_count * 60000
    (1 + ((u.idx * 3 + w.day_idx * 7) % 4)) * 60 * 60000::bigint,
    jsonb_build_object(
        'username', 'Kadero' || E'\\' || u.username,
        'hostname', u.hostname
    ),
    (w.day_date + interval '9 hours') AT TIME ZONE 'Europe/Moscow',
    (w.day_date + interval '18 hours') AT TIME ZONE 'Europe/Moscow'
FROM _seed_users u
CROSS JOIN _seed_workdays w
JOIN devices d ON d.hostname = u.hostname
    AND d.tenant_id = '386597e2-7c48-4668-a101-711308a6a5b2'::uuid
ORDER BY w.day_date, u.idx;

-- ============================================================
-- 4. app_focus_intervals
--    ~50-100 intervals per user per working day.
--    We generate them procedurally using generate_series within the
--    recording session window.
-- ============================================================

-- Application definitions
CREATE TEMPORARY TABLE _seed_apps (
    app_idx     int PRIMARY KEY,
    process_name varchar,
    is_browser  boolean,
    browser_name varchar,
    category    varchar
) ON COMMIT DROP;

INSERT INTO _seed_apps VALUES
(1,  'chrome.exe',   true,  'Google Chrome',   'browsing'),
(2,  'msedge.exe',   true,  'Microsoft Edge',  'browsing'),
(3,  'outlook.exe',  false, NULL,               'communication'),
(4,  'teams.exe',    false, NULL,               'communication'),
(5,  'explorer.exe', false, NULL,               'other'),
(6,  'notepad.exe',  false, NULL,               'other'),
(7,  'code.exe',     false, NULL,               'work'),
(8,  '1cv8.exe',     false, NULL,               'work'),
(9,  'calc.exe',     false, NULL,               'other');

-- Browser domains
CREATE TEMPORARY TABLE _seed_domains (
    dom_idx int PRIMARY KEY,
    domain  varchar,
    title_prefix varchar
) ON COMMIT DROP;

INSERT INTO _seed_domains VALUES
(1,  'mail.google.com',    'Входящие - Gmail'),
(2,  'docs.google.com',    'Документ - Google Docs'),
(3,  'github.com',         'Pull Requests · GitHub'),
(4,  'stackoverflow.com',  'java - Stack Overflow'),
(5,  'habr.com',           'Лучшие публикации - Хабр'),
(6,  'ya.ru',              'Яндекс — найдётся всё'),
(7,  'ozon.ru',            'OZON — интернет-магазин'),
(8,  'avito.ru',           'Авито — объявления'),
(9,  'hh.ru',              'Вакансии — HeadHunter'),
(10, 'kadero.ru',          'Кадеро — Главная'),
(11, 'jira.kadero.ru',     'KADERO-1234 - Jira'),
(12, 'wiki.kadero.ru',     'Документация - Confluence'),
(13, 'portal.kadero.ru',   'Корпоративный портал');

-- Non-browser window titles
CREATE TEMPORARY TABLE _seed_titles (
    title_idx int PRIMARY KEY,
    process_name varchar,
    window_title varchar
) ON COMMIT DROP;

INSERT INTO _seed_titles VALUES
(1,  'outlook.exe',  'Входящие - Outlook'),
(2,  'outlook.exe',  'Новое письмо — RE: Отчёт за неделю'),
(3,  'outlook.exe',  'Совещание: Планёрка — Outlook'),
(4,  'teams.exe',    'Microsoft Teams — Чат'),
(5,  'teams.exe',    'Собрание: Стендап команды'),
(6,  'teams.exe',    'Звонок: Иванов А. — Teams'),
(7,  'explorer.exe', 'Документы — Проводник'),
(8,  'explorer.exe', 'C:\Users\%user%\Downloads'),
(9,  'notepad.exe',  'Без имени — Блокнот'),
(10, 'notepad.exe',  'notes.txt — Блокнот'),
(11, 'code.exe',     'app.tsx — KaderoAgent — Visual Studio Code'),
(12, 'code.exe',     'main.py — scripts — Visual Studio Code'),
(13, '1cv8.exe',     '1С:Предприятие — Бухгалтерия'),
(14, '1cv8.exe',     '1С:Предприятие — Зарплата и управление персоналом'),
(15, '1cv8.exe',     '1С:Предприятие — Управление торговлей'),
(16, 'calc.exe',     'Калькулятор');

-- Generate focus intervals: for each recording_session, create 50-80 intervals
-- filling the session window with sequential app usage.
-- We use a CTE with generate_series to create slot indices, then map to apps.

INSERT INTO app_focus_intervals (
    id, created_ts, tenant_id, device_id,
    username, session_id, process_name,
    window_title, is_browser, browser_name,
    domain, started_at, ended_at,
    duration_ms, category, correlation_id
)
SELECT
    gen_random_uuid(),
    -- created_ts = started_at (for partitioning)
    slot_start,
    '386597e2-7c48-4668-a101-711308a6a5b2'::uuid,
    rs.device_id,
    meta_username,
    rs.id,
    app_process,
    app_title,
    app_is_browser,
    app_browser_name,
    app_domain,
    slot_start,
    slot_end,
    EXTRACT(EPOCH FROM (slot_end - slot_start))::int * 1000,
    app_category,
    gen_random_uuid()
FROM (
    SELECT
        rs.id,
        rs.device_id,
        rs.started_ts,
        rs.ended_ts,
        rs.metadata->>'username' AS meta_username,
        -- Generate slot offsets: each slot 1-10 minutes
        -- We create ~60 slots per session by stepping in ~3min average intervals
        gs.n AS slot_idx,
        -- slot_start = session start + cumulative offset
        rs.started_ts + (gs.n * 3 + ((gs.n * 7 + EXTRACT(EPOCH FROM rs.started_ts)::bigint) % 3)) * interval '1 minute' AS slot_start_raw,
        -- slot duration: 30s to 10min (30000-600000 ms), varies by slot index
        (30 + ((gs.n * 13 + EXTRACT(EPOCH FROM rs.started_ts)::bigint) % 571)) AS dur_sec,
        -- Pick app: deterministic based on slot_idx + session
        ((gs.n * 7 + EXTRACT(EPOCH FROM rs.started_ts)::bigint) % 9 + 1)::int AS app_pick,
        -- Pick domain for browser apps
        ((gs.n * 11 + EXTRACT(EPOCH FROM rs.started_ts)::bigint) % 13 + 1)::int AS dom_pick,
        -- Pick title for non-browser apps
        ((gs.n * 17 + EXTRACT(EPOCH FROM rs.started_ts)::bigint) % 16 + 1)::int AS title_pick
    FROM recording_sessions rs
    CROSS JOIN generate_series(0, 59) AS gs(n)
    WHERE rs.tenant_id = '386597e2-7c48-4668-a101-711308a6a5b2'::uuid
      AND rs.status = 'completed'
      AND rs.started_ts >= (NOW() - interval '16 days')
      AND rs.device_id IN (SELECT id FROM devices WHERE hostname LIKE 'WS-%' AND tenant_id = '386597e2-7c48-4668-a101-711308a6a5b2'::uuid)
) rs
JOIN _seed_apps a ON a.app_idx = rs.app_pick
LEFT JOIN _seed_domains dom ON dom.dom_idx = rs.dom_pick AND a.is_browser = true
LEFT JOIN _seed_titles t ON t.title_idx = rs.title_pick AND a.is_browser = false
CROSS JOIN LATERAL (
    SELECT
        -- Clamp slot_start to session window
        GREATEST(rs.slot_start_raw, rs.started_ts) AS slot_start,
        -- Clamp slot_end to session window
        LEAST(
            rs.slot_start_raw + (rs.dur_sec || ' seconds')::interval,
            rs.ended_ts
        ) AS slot_end,
        a.process_name AS app_process,
        a.is_browser AS app_is_browser,
        a.browser_name AS app_browser_name,
        a.category AS app_category,
        CASE
            WHEN a.is_browser THEN dom.domain
            ELSE NULL
        END AS app_domain,
        CASE
            WHEN a.is_browser THEN
                COALESCE(dom.title_prefix, 'Новая вкладка') || ' — ' ||
                COALESCE(a.browser_name, 'Browser')
            ELSE
                COALESCE(t.window_title, a.process_name)
        END AS app_title
) lat
WHERE lat.slot_start < lat.slot_end
  AND lat.slot_start < rs.ended_ts;

-- ============================================================
-- 5. device_audit_events
--    For each recording session: SESSION_START + SESSION_END
--    For each user per workday: USER_LOGIN (morning) + USER_LOGOUT (evening)
--    For each user per workday: STATUS_CHANGE offline->online, online->offline
-- ============================================================

-- SESSION_START events
INSERT INTO device_audit_events (
    id, created_ts, tenant_id, device_id,
    session_id, event_type, event_ts,
    details, correlation_id, username
)
SELECT
    gen_random_uuid(),
    rs.started_ts,
    rs.tenant_id,
    rs.device_id,
    rs.id,
    'SESSION_LOGON',
    rs.started_ts,
    jsonb_build_object('status', 'recording', 'trigger', 'auto_start'),
    gen_random_uuid(),
    rs.metadata->>'username'
FROM recording_sessions rs
WHERE rs.tenant_id = '386597e2-7c48-4668-a101-711308a6a5b2'::uuid
  AND rs.started_ts >= (NOW() - interval '16 days')
  AND rs.device_id IN (SELECT id FROM devices WHERE hostname LIKE 'WS-%' AND tenant_id = '386597e2-7c48-4668-a101-711308a6a5b2'::uuid);

-- SESSION_END events
INSERT INTO device_audit_events (
    id, created_ts, tenant_id, device_id,
    session_id, event_type, event_ts,
    details, correlation_id, username
)
SELECT
    gen_random_uuid(),
    rs.ended_ts,
    rs.tenant_id,
    rs.device_id,
    rs.id,
    'SESSION_LOGOFF',
    rs.ended_ts,
    jsonb_build_object(
        'status', 'completed',
        'segment_count', rs.segment_count,
        'total_bytes', rs.total_bytes,
        'duration_ms', rs.total_duration_ms
    ),
    gen_random_uuid(),
    rs.metadata->>'username'
FROM recording_sessions rs
WHERE rs.tenant_id = '386597e2-7c48-4668-a101-711308a6a5b2'::uuid
  AND rs.ended_ts IS NOT NULL
  AND rs.started_ts >= (NOW() - interval '16 days')
  AND rs.device_id IN (SELECT id FROM devices WHERE hostname LIKE 'WS-%' AND tenant_id = '386597e2-7c48-4668-a101-711308a6a5b2'::uuid);

-- USER_LOGIN events (each workday morning, ~08:50-09:10)
INSERT INTO device_audit_events (
    id, created_ts, tenant_id, device_id,
    session_id, event_type, event_ts,
    details, correlation_id, username
)
SELECT
    gen_random_uuid(),
    login_ts,
    '386597e2-7c48-4668-a101-711308a6a5b2'::uuid,
    d.id,
    NULL,
    'USER_LOGON',
    login_ts,
    jsonb_build_object(
        'username', 'Kadero' || E'\\' || u.username,
        'display_name', u.display_name,
        'domain', 'Kadero'
    ),
    gen_random_uuid(),
    'Kadero' || E'\\' || u.username
FROM _seed_users u
CROSS JOIN _seed_workdays w
JOIN devices d ON d.hostname = u.hostname
    AND d.tenant_id = '386597e2-7c48-4668-a101-711308a6a5b2'::uuid
CROSS JOIN LATERAL (
    SELECT (w.day_date + interval '8 hours 50 minutes'
        + ((u.idx * 3 + w.day_idx * 7) % 20 || ' minutes')::interval
    ) AT TIME ZONE 'Europe/Moscow' AS login_ts
) lt;

-- USER_LOGOUT events (each workday evening, ~17:50-18:10)
INSERT INTO device_audit_events (
    id, created_ts, tenant_id, device_id,
    session_id, event_type, event_ts,
    details, correlation_id, username
)
SELECT
    gen_random_uuid(),
    logout_ts,
    '386597e2-7c48-4668-a101-711308a6a5b2'::uuid,
    d.id,
    NULL,
    'USER_LOGOFF',
    logout_ts,
    jsonb_build_object(
        'username', 'Kadero' || E'\\' || u.username,
        'display_name', u.display_name,
        'reason', 'user_initiated'
    ),
    gen_random_uuid(),
    'Kadero' || E'\\' || u.username
FROM _seed_users u
CROSS JOIN _seed_workdays w
JOIN devices d ON d.hostname = u.hostname
    AND d.tenant_id = '386597e2-7c48-4668-a101-711308a6a5b2'::uuid
CROSS JOIN LATERAL (
    SELECT (w.day_date + interval '17 hours 50 minutes'
        + ((u.idx * 5 + w.day_idx * 11) % 20 || ' minutes')::interval
    ) AT TIME ZONE 'Europe/Moscow' AS logout_ts
) lt;

-- SESSION_UNLOCK (morning - user starts working)
INSERT INTO device_audit_events (
    id, created_ts, tenant_id, device_id,
    session_id, event_type, event_ts,
    details, correlation_id, username
)
SELECT
    gen_random_uuid(),
    online_ts,
    '386597e2-7c48-4668-a101-711308a6a5b2'::uuid,
    d.id,
    NULL,
    'SESSION_UNLOCK',
    online_ts,
    jsonb_build_object('trigger', 'user_activity'),
    gen_random_uuid(),
    'Kadero' || E'\\' || u.username
FROM _seed_users u
CROSS JOIN _seed_workdays w
JOIN devices d ON d.hostname = u.hostname
    AND d.tenant_id = '386597e2-7c48-4668-a101-711308a6a5b2'::uuid
CROSS JOIN LATERAL (
    SELECT (w.day_date + interval '8 hours 48 minutes'
        + ((u.idx * 3 + w.day_idx * 7) % 20 || ' minutes')::interval
    ) AT TIME ZONE 'Europe/Moscow' AS online_ts
) lt;

-- SESSION_LOCK (evening - user locks workstation)
INSERT INTO device_audit_events (
    id, created_ts, tenant_id, device_id,
    session_id, event_type, event_ts,
    details, correlation_id, username
)
SELECT
    gen_random_uuid(),
    offline_ts,
    '386597e2-7c48-4668-a101-711308a6a5b2'::uuid,
    d.id,
    NULL,
    'SESSION_LOCK',
    offline_ts,
    jsonb_build_object('trigger', 'user_lock'),
    gen_random_uuid(),
    'Kadero' || E'\\' || u.username
FROM _seed_users u
CROSS JOIN _seed_workdays w
JOIN devices d ON d.hostname = u.hostname
    AND d.tenant_id = '386597e2-7c48-4668-a101-711308a6a5b2'::uuid
CROSS JOIN LATERAL (
    SELECT (w.day_date + interval '18 hours'
        + ((u.idx * 5 + w.day_idx * 11) % 15 || ' minutes')::interval
    ) AT TIME ZONE 'Europe/Moscow' AS offline_ts
) lt;

COMMIT;
