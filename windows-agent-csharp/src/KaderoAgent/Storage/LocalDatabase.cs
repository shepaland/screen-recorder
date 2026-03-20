using System.Globalization;
using System.Text.Json;
using Microsoft.Data.Sqlite;
using KaderoAgent.Audit;
using KaderoAgent.Configuration;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Options;

namespace KaderoAgent.Storage;

public class LocalDatabase
{
    private readonly string _dbPath;
    private readonly ILogger<LocalDatabase> _logger;

    public string DatabasePath => _dbPath;

    public LocalDatabase(IOptions<AgentConfig> config, ILogger<LocalDatabase> logger)
    {
        _logger = logger;
        var dir = config.Value.DataPath;
        Directory.CreateDirectory(dir);
        _dbPath = Path.Combine(dir, "agent.db");
        InitializeDatabase();
    }

    // ──────────────────── Initialization ────────────────────

    private void InitializeDatabase()
    {
        using var conn = GetConnection();
        conn.Open();

        // 1. PRAGMAs (before table creation!)
        Exec(conn, "PRAGMA auto_vacuum = INCREMENTAL");
        Exec(conn, "PRAGMA journal_mode = WAL");

        // 2. Create agent_state + pending_activity (always safe, new tables)
        CreateAgentStateTable(conn);
        CreatePendingActivityTable(conn);

        // 3. Migrate old data BEFORE creating new pending_segments
        // (old table may have 'uploaded' column, migration renames it)
        MigratePendingSegments(conn);
        MigrateAuditEvents(conn);

        // 4. Create new pending_segments if it doesn't exist yet (fresh install or post-migration)
        CreatePendingSegmentsTable(conn);

        // 5. One-time VACUUM if auto_vacuum was just enabled on existing DB
        if (NeedsInitialVacuum(conn))
        {
            _logger.LogInformation("Running initial VACUUM to enable incremental auto_vacuum");
            Exec(conn, "VACUUM");
        }

        // 6. Crash recovery: reset in-flight records to PENDING
        ResetInFlightRecords(conn);
    }

    private void ResetInFlightRecords(SqliteConnection conn)
    {
        int segments = ExecCount(conn, "UPDATE pending_segments SET status = 'PENDING' WHERE status IN ('QUEUED', 'SENDED')");
        int activity = ExecCount(conn, "UPDATE pending_activity SET status = 'PENDING' WHERE status IN ('QUEUED', 'SENDED')");
        if (segments > 0 || activity > 0)
            _logger.LogInformation("Crash recovery: reset {Segments} segments + {Activity} activity from QUEUED/SENDED to PENDING",
                segments, activity);
    }

    private void CreatePendingSegmentsTable(SqliteConnection conn)
    {
        Exec(conn, @"
            CREATE TABLE IF NOT EXISTS pending_segments (
                id              INTEGER PRIMARY KEY AUTOINCREMENT,
                file_path       TEXT    NOT NULL,
                session_id      TEXT    NOT NULL,
                sequence_num    INTEGER NOT NULL,
                size_bytes      INTEGER NOT NULL DEFAULT 0,
                checksum_sha256 TEXT,
                duration_ms     INTEGER,
                recorded_at     TEXT,
                status          TEXT    NOT NULL DEFAULT 'NEW',
                segment_id      TEXT,
                retry_count     INTEGER NOT NULL DEFAULT 0,
                last_error      TEXT,
                created_ts      TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
                updated_ts      TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
                UNIQUE(session_id, sequence_num)
            )");
        Exec(conn, "CREATE INDEX IF NOT EXISTS idx_segments_status ON pending_segments(status)");
    }

    private void CreatePendingActivityTable(SqliteConnection conn)
    {
        Exec(conn, @"
            CREATE TABLE IF NOT EXISTS pending_activity (
                id              INTEGER PRIMARY KEY AUTOINCREMENT,
                event_id        TEXT    NOT NULL UNIQUE,
                data_type       TEXT    NOT NULL,
                session_id      TEXT,
                payload         TEXT    NOT NULL,
                status          TEXT    NOT NULL DEFAULT 'NEW',
                batch_id        TEXT,
                retry_count     INTEGER NOT NULL DEFAULT 0,
                last_error      TEXT,
                created_ts      TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
                updated_ts      TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
            )");
        Exec(conn, "CREATE INDEX IF NOT EXISTS idx_activity_status_type ON pending_activity(status, data_type)");
        Exec(conn, "CREATE INDEX IF NOT EXISTS idx_activity_batch ON pending_activity(batch_id)");
    }

    private void CreateAgentStateTable(SqliteConnection conn)
    {
        Exec(conn, @"
            CREATE TABLE IF NOT EXISTS agent_state (
                key   TEXT PRIMARY KEY,
                value TEXT
            )");
    }

    // ──────────────────── Migrations ────────────────────

    private void MigratePendingSegments(SqliteConnection conn)
    {
        var columns = GetColumnNames(conn, "pending_segments");
        if (!columns.Contains("uploaded")) return; // already migrated or new table

        _logger.LogInformation("Migrating pending_segments: uploaded → status");

        Exec(conn, "ALTER TABLE pending_segments RENAME TO pending_segments_old");

        // Create new table
        CreatePendingSegmentsTable(conn);

        Exec(conn, @"
            INSERT OR IGNORE INTO pending_segments (id, file_path, session_id, sequence_num, size_bytes, created_ts, status)
            SELECT id, file_path, session_id, sequence_num, size_bytes, created_ts,
                CASE WHEN uploaded = 1 THEN 'SERVER_SIDE_DONE' ELSE 'PENDING' END
            FROM pending_segments_old");

        Exec(conn, "DROP TABLE pending_segments_old");
        _logger.LogInformation("pending_segments migration complete");
    }

    private void MigrateAuditEvents(SqliteConnection conn)
    {
        if (!TableExists(conn, "audit_events")) return;

        _logger.LogInformation("Migrating audit_events → pending_activity");

        // Transfer unuploaded audit events to pending_activity
        Exec(conn, @"
            INSERT OR IGNORE INTO pending_activity (event_id, data_type, session_id, payload, status, created_ts)
            SELECT
                id,
                'AUDIT_EVENT',
                session_id,
                json_object('event_type', event_type, 'event_ts', event_ts, 'details', details),
                CASE WHEN uploaded = 1 THEN 'SERVER_SIDE_DONE' ELSE 'PENDING' END,
                event_ts
            FROM audit_events
            WHERE uploaded = 0");

        Exec(conn, "DROP TABLE audit_events");
        _logger.LogInformation("audit_events migration complete");
    }

    private bool NeedsInitialVacuum(SqliteConnection conn)
    {
        using var cmd = conn.CreateCommand();
        cmd.CommandText = "PRAGMA auto_vacuum";
        var result = cmd.ExecuteScalar();
        // 0 = NONE, 1 = FULL, 2 = INCREMENTAL
        // If still 0 after setting INCREMENTAL, VACUUM is needed
        return result != null && Convert.ToInt32(result) == 0;
    }

    // ──────────────────── pending_activity methods ────────────────────

    public void InsertActivity(string eventId, string dataType, string? sessionId, string payload)
    {
        using var conn = GetConnection();
        conn.Open();
        using var cmd = conn.CreateCommand();
        cmd.CommandText = @"
            INSERT OR IGNORE INTO pending_activity (event_id, data_type, session_id, payload)
            VALUES ($eventId, $dataType, $sessionId, $payload)";
        cmd.Parameters.AddWithValue("$eventId", eventId);
        cmd.Parameters.AddWithValue("$dataType", dataType);
        cmd.Parameters.AddWithValue("$sessionId", (object?)sessionId ?? DBNull.Value);
        cmd.Parameters.AddWithValue("$payload", payload);
        cmd.ExecuteNonQuery();
    }

    public List<PendingActivity> GetPendingActivity(string dataType, int limit = 100)
    {
        return GetPendingActivity(new[] { dataType }, limit);
    }

    public List<PendingActivity> GetPendingActivity(string[] dataTypes, int limit = 500)
    {
        var list = new List<PendingActivity>();
        using var conn = GetConnection();
        conn.Open();

        // Build IN clause
        var placeholders = string.Join(",", dataTypes.Select((_, i) => $"$dt{i}"));
        using var cmd = conn.CreateCommand();
        cmd.CommandText = $@"
            SELECT id, event_id, data_type, session_id, payload, status, batch_id, retry_count, last_error, created_ts, updated_ts
            FROM pending_activity
            WHERE status IN ('NEW', 'PENDING') AND data_type IN ({placeholders})
            ORDER BY id ASC
            LIMIT $limit";

        for (int i = 0; i < dataTypes.Length; i++)
            cmd.Parameters.AddWithValue($"$dt{i}", dataTypes[i]);
        cmd.Parameters.AddWithValue("$limit", limit);

        using var reader = cmd.ExecuteReader();
        while (reader.Read())
        {
            list.Add(ReadActivity(reader));
        }
        return list;
    }

    public void UpdateActivityStatus(List<int> ids, string status, string? batchId = null)
    {
        if (ids.Count == 0) return;
        using var conn = GetConnection();
        conn.Open();

        var placeholders = string.Join(",", ids.Select((_, i) => $"$id{i}"));
        using var cmd = conn.CreateCommand();
        cmd.CommandText = $@"
            UPDATE pending_activity
            SET status = $status, batch_id = $batchId, updated_ts = strftime('%Y-%m-%dT%H:%M:%fZ','now')
            WHERE id IN ({placeholders})";
        cmd.Parameters.AddWithValue("$status", status);
        cmd.Parameters.AddWithValue("$batchId", (object?)batchId ?? DBNull.Value);
        for (int i = 0; i < ids.Count; i++)
            cmd.Parameters.AddWithValue($"$id{i}", ids[i]);
        cmd.ExecuteNonQuery();
    }

    public void SetActivityError(int id, string error)
    {
        using var conn = GetConnection();
        conn.Open();
        using var cmd = conn.CreateCommand();
        cmd.CommandText = @"
            UPDATE pending_activity
            SET last_error = $error, retry_count = retry_count + 1,
                status = 'PENDING', updated_ts = strftime('%Y-%m-%dT%H:%M:%fZ','now')
            WHERE id = $id";
        cmd.Parameters.AddWithValue("$id", id);
        cmd.Parameters.AddWithValue("$error", error);
        cmd.ExecuteNonQuery();
    }

    public Dictionary<string, int> GetActivityCountsByStatus()
    {
        var counts = new Dictionary<string, int>();
        using var conn = GetConnection();
        conn.Open();
        using var cmd = conn.CreateCommand();
        cmd.CommandText = "SELECT status, COUNT(*) FROM pending_activity GROUP BY status";
        using var reader = cmd.ExecuteReader();
        while (reader.Read())
            counts[reader.GetString(0)] = reader.GetInt32(1);
        return counts;
    }

    public int CleanupActivity(TimeSpan serverSideDoneAge, TimeSpan evictedAge, TimeSpan maxRetention)
    {
        using var conn = GetConnection();
        conn.Open();

        int total = 0;

        // Delete SERVER_SIDE_DONE older than serverSideDoneAge
        total += ExecCount(conn, @"
            DELETE FROM pending_activity
            WHERE status = 'SERVER_SIDE_DONE'
              AND updated_ts < strftime('%Y-%m-%dT%H:%M:%fZ', 'now', $age)",
            ("$age", $"-{(int)serverSideDoneAge.TotalSeconds} seconds"));

        // Delete all records older than maxRetention regardless of status
        total += ExecCount(conn, @"
            DELETE FROM pending_activity
            WHERE created_ts < strftime('%Y-%m-%dT%H:%M:%fZ', 'now', $age)",
            ("$age", $"-{(int)maxRetention.TotalSeconds} seconds"));

        // Incremental vacuum to reclaim space
        Exec(conn, "PRAGMA incremental_vacuum");

        return total;
    }

    // ──────────────────── pending_segments methods (new v2) ────────────────────

    public void InsertSegment(string filePath, string sessionId, int sequenceNum,
                              long sizeBytes, string? recordedAt)
    {
        using var conn = GetConnection();
        conn.Open();
        using var cmd = conn.CreateCommand();
        cmd.CommandText = @"
            INSERT OR IGNORE INTO pending_segments (file_path, session_id, sequence_num, size_bytes, recorded_at)
            VALUES ($filePath, $sessionId, $seqNum, $sizeBytes, $recordedAt)";
        cmd.Parameters.AddWithValue("$filePath", filePath);
        cmd.Parameters.AddWithValue("$sessionId", sessionId);
        cmd.Parameters.AddWithValue("$seqNum", sequenceNum);
        cmd.Parameters.AddWithValue("$sizeBytes", sizeBytes);
        cmd.Parameters.AddWithValue("$recordedAt", (object?)recordedAt ?? DBNull.Value);
        cmd.ExecuteNonQuery();
    }

    public PendingSegment? GetNextPendingSegment()
    {
        using var conn = GetConnection();
        conn.Open();
        using var cmd = conn.CreateCommand();
        cmd.CommandText = @"
            SELECT id, file_path, session_id, sequence_num, size_bytes, checksum_sha256,
                   duration_ms, recorded_at, status, segment_id, retry_count, last_error, created_ts, updated_ts
            FROM pending_segments
            WHERE status IN ('NEW', 'PENDING')
            ORDER BY id ASC
            LIMIT 1";
        using var reader = cmd.ExecuteReader();
        if (!reader.Read()) return null;
        return ReadSegment(reader);
    }

    public void UpdateSegmentStatus(int id, string status, string? segmentId = null)
    {
        using var conn = GetConnection();
        conn.Open();
        using var cmd = conn.CreateCommand();
        cmd.CommandText = @"
            UPDATE pending_segments
            SET status = $status, segment_id = COALESCE($segmentId, segment_id),
                updated_ts = strftime('%Y-%m-%dT%H:%M:%fZ','now')
            WHERE id = $id";
        cmd.Parameters.AddWithValue("$id", id);
        cmd.Parameters.AddWithValue("$status", status);
        cmd.Parameters.AddWithValue("$segmentId", (object?)segmentId ?? DBNull.Value);
        cmd.ExecuteNonQuery();
    }

    public void SetSegmentError(int id, string error)
    {
        using var conn = GetConnection();
        conn.Open();
        using var cmd = conn.CreateCommand();
        cmd.CommandText = @"
            UPDATE pending_segments
            SET last_error = $error, retry_count = retry_count + 1,
                status = 'PENDING', updated_ts = strftime('%Y-%m-%dT%H:%M:%fZ','now')
            WHERE id = $id";
        cmd.Parameters.AddWithValue("$id", id);
        cmd.Parameters.AddWithValue("$error", error);
        cmd.ExecuteNonQuery();
    }

    public Dictionary<string, int> GetSegmentCountsByStatus()
    {
        var counts = new Dictionary<string, int>();
        using var conn = GetConnection();
        conn.Open();
        using var cmd = conn.CreateCommand();
        cmd.CommandText = "SELECT status, COUNT(*) FROM pending_segments GROUP BY status";
        using var reader = cmd.ExecuteReader();
        while (reader.Read())
            counts[reader.GetString(0)] = reader.GetInt32(1);
        return counts;
    }

    public List<PendingSegment> GetSegmentsForEviction()
    {
        var list = new List<PendingSegment>();
        using var conn = GetConnection();
        conn.Open();
        using var cmd = conn.CreateCommand();
        cmd.CommandText = @"
            SELECT id, file_path, session_id, sequence_num, size_bytes, checksum_sha256,
                   duration_ms, recorded_at, status, segment_id, retry_count, last_error, created_ts, updated_ts
            FROM pending_segments
            WHERE status IN ('SERVER_SIDE_DONE', 'SENDED')
            ORDER BY created_ts ASC";
        using var reader = cmd.ExecuteReader();
        while (reader.Read())
            list.Add(ReadSegment(reader));
        return list;
    }

    public int CleanupSegments(TimeSpan maxRetention)
    {
        using var conn = GetConnection();
        conn.Open();

        int total = 0;

        // Delete SERVER_SIDE_DONE segments (file already evictable)
        total += ExecCount(conn, @"
            DELETE FROM pending_segments
            WHERE status = 'SERVER_SIDE_DONE'
              AND updated_ts < strftime('%Y-%m-%dT%H:%M:%fZ', 'now', $age)",
            ("$age", $"-3600 seconds"));

        // Delete all records older than maxRetention
        total += ExecCount(conn, @"
            DELETE FROM pending_segments
            WHERE created_ts < strftime('%Y-%m-%dT%H:%M:%fZ', 'now', $age)",
            ("$age", $"-{(int)maxRetention.TotalSeconds} seconds"));

        Exec(conn, "PRAGMA incremental_vacuum");

        return total;
    }

    public int? GetOldestUnsentSegmentAgeSec()
    {
        using var conn = GetConnection();
        conn.Open();
        using var cmd = conn.CreateCommand();
        cmd.CommandText = @"
            SELECT CAST((julianday('now') - julianday(created_ts)) * 86400 AS INTEGER)
            FROM pending_segments
            WHERE status IN ('NEW', 'PENDING')
            ORDER BY created_ts ASC
            LIMIT 1";
        var result = cmd.ExecuteScalar();
        return result == null || result == DBNull.Value ? null : Convert.ToInt32(result);
    }

    public int? GetOldestUnsentActivityAgeSec()
    {
        using var conn = GetConnection();
        conn.Open();
        using var cmd = conn.CreateCommand();
        cmd.CommandText = @"
            SELECT CAST((julianday('now') - julianday(created_ts)) * 86400 AS INTEGER)
            FROM pending_activity
            WHERE status IN ('NEW', 'PENDING')
            ORDER BY created_ts ASC
            LIMIT 1";
        var result = cmd.ExecuteScalar();
        return result == null || result == DBNull.Value ? null : Convert.ToInt32(result);
    }

    // ──────────────────── Legacy methods (backward compat for old UploadQueue/Sinks) ────────────────────

    public void SavePendingSegment(Upload.SegmentInfo segment)
    {
        long size = 0;
        try { size = new FileInfo(segment.FilePath).Length; } catch { }
        InsertSegment(segment.FilePath, segment.SessionId, segment.SequenceNum, size, null);
    }

    public void MarkSegmentUploaded(string filePath)
    {
        using var conn = GetConnection();
        conn.Open();
        using var cmd = conn.CreateCommand();
        cmd.CommandText = "UPDATE pending_segments SET status = 'SERVER_SIDE_DONE', updated_ts = strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE file_path = $p";
        cmd.Parameters.AddWithValue("$p", filePath);
        cmd.ExecuteNonQuery();
    }

    public List<Upload.SegmentInfo> GetPendingSegments()
    {
        var list = new List<Upload.SegmentInfo>();
        using var conn = GetConnection();
        conn.Open();
        using var cmd = conn.CreateCommand();
        cmd.CommandText = "SELECT file_path, session_id, sequence_num FROM pending_segments WHERE status IN ('NEW', 'PENDING') ORDER BY id";
        using var reader = cmd.ExecuteReader();
        while (reader.Read())
        {
            list.Add(new Upload.SegmentInfo
            {
                FilePath = reader.GetString(0),
                SessionId = reader.GetString(1),
                SequenceNum = reader.GetInt32(2)
            });
        }
        return list;
    }

    public void SaveAuditEvent(AuditEvent evt)
    {
        var payload = JsonSerializer.Serialize(new
        {
            event_type = evt.EventType,
            event_ts = evt.EventTs.ToString("o"),
            details = JsonSerializer.Serialize(evt.Details)
        });
        InsertActivity(evt.Id, "AUDIT_EVENT", evt.SessionId, payload);
    }

    public List<AuditEvent> GetPendingAuditEvents(int limit = 100)
    {
        var events = new List<AuditEvent>();
        var activities = GetPendingActivity("AUDIT_EVENT", limit);
        foreach (var act in activities)
        {
            try
            {
                var doc = JsonDocument.Parse(act.Payload);
                var root = doc.RootElement;
                events.Add(new AuditEvent
                {
                    Id = act.EventId,
                    EventType = root.GetProperty("event_type").GetString() ?? "",
                    EventTs = DateTime.Parse(root.GetProperty("event_ts").GetString() ?? "", null, DateTimeStyles.RoundtripKind),
                    SessionId = act.SessionId,
                    Details = JsonSerializer.Deserialize<Dictionary<string, object>>(
                        root.GetProperty("details").GetString() ?? "{}") ?? new()
                });
            }
            catch (Exception ex)
            {
                _logger.LogWarning(ex, "Failed to parse audit event payload: {EventId}", act.EventId);
            }
        }
        return events;
    }

    public void MarkAuditEventUploaded(string eventId)
    {
        using var conn = GetConnection();
        conn.Open();
        using var cmd = conn.CreateCommand();
        cmd.CommandText = "UPDATE pending_activity SET status = 'SERVER_SIDE_DONE', updated_ts = strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE event_id = $id";
        cmd.Parameters.AddWithValue("$id", eventId);
        cmd.ExecuteNonQuery();
    }

    // ──────────────────── Helpers ────────────────────

    private static void Exec(SqliteConnection conn, string sql)
    {
        using var cmd = conn.CreateCommand();
        cmd.CommandText = sql;
        cmd.ExecuteNonQuery();
    }

    private static int ExecCount(SqliteConnection conn, string sql, params (string name, string value)[] parameters)
    {
        using var cmd = conn.CreateCommand();
        cmd.CommandText = sql;
        foreach (var (name, value) in parameters)
            cmd.Parameters.AddWithValue(name, value);
        return cmd.ExecuteNonQuery();
    }

    private static List<string> GetColumnNames(SqliteConnection conn, string tableName)
    {
        var columns = new List<string>();
        using var cmd = conn.CreateCommand();
        cmd.CommandText = $"PRAGMA table_info({tableName})";
        using var reader = cmd.ExecuteReader();
        while (reader.Read())
            columns.Add(reader.GetString(1)); // column name is at index 1
        return columns;
    }

    private static bool TableExists(SqliteConnection conn, string tableName)
    {
        using var cmd = conn.CreateCommand();
        cmd.CommandText = "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name=$name";
        cmd.Parameters.AddWithValue("$name", tableName);
        return Convert.ToInt32(cmd.ExecuteScalar()) > 0;
    }

    private static PendingActivity ReadActivity(SqliteDataReader reader)
    {
        return new PendingActivity
        {
            Id = reader.GetInt32(0),
            EventId = reader.GetString(1),
            DataType = reader.GetString(2),
            SessionId = reader.IsDBNull(3) ? null : reader.GetString(3),
            Payload = reader.GetString(4),
            Status = reader.GetString(5),
            BatchId = reader.IsDBNull(6) ? null : reader.GetString(6),
            RetryCount = reader.GetInt32(7),
            LastError = reader.IsDBNull(8) ? null : reader.GetString(8),
            CreatedTs = DateTime.Parse(reader.GetString(9), null, DateTimeStyles.RoundtripKind),
            UpdatedTs = DateTime.Parse(reader.GetString(10), null, DateTimeStyles.RoundtripKind)
        };
    }

    private static PendingSegment ReadSegment(SqliteDataReader reader)
    {
        return new PendingSegment
        {
            Id = reader.GetInt32(0),
            FilePath = reader.GetString(1),
            SessionId = reader.GetString(2),
            SequenceNum = reader.GetInt32(3),
            SizeBytes = reader.GetInt64(4),
            ChecksumSha256 = reader.IsDBNull(5) ? null : reader.GetString(5),
            DurationMs = reader.IsDBNull(6) ? null : reader.GetInt32(6),
            RecordedAt = reader.IsDBNull(7) ? null : reader.GetString(7),
            Status = reader.GetString(8),
            SegmentId = reader.IsDBNull(9) ? null : reader.GetString(9),
            RetryCount = reader.GetInt32(10),
            LastError = reader.IsDBNull(11) ? null : reader.GetString(11),
            CreatedTs = DateTime.Parse(reader.GetString(12), null, DateTimeStyles.RoundtripKind),
            UpdatedTs = DateTime.Parse(reader.GetString(13), null, DateTimeStyles.RoundtripKind)
        };
    }

    private SqliteConnection GetConnection() => new($"Data Source={_dbPath}");
}
