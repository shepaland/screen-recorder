using Microsoft.Data.Sqlite;
using KaderoAgent.Configuration;
using Microsoft.Extensions.Options;

namespace KaderoAgent.Storage;

public class LocalDatabase
{
    private readonly string _dbPath;

    public LocalDatabase(IOptions<AgentConfig> config)
    {
        var dir = config.Value.DataPath;
        Directory.CreateDirectory(dir);
        _dbPath = Path.Combine(dir, "agent.db");
        Initialize();
    }

    private void Initialize()
    {
        using var conn = GetConnection();
        conn.Open();
        using var cmd = conn.CreateCommand();
        cmd.CommandText = @"
            CREATE TABLE IF NOT EXISTS pending_segments (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                file_path TEXT NOT NULL,
                session_id TEXT NOT NULL,
                sequence_num INTEGER NOT NULL,
                size_bytes INTEGER DEFAULT 0,
                created_ts TEXT DEFAULT (datetime('now')),
                uploaded INTEGER DEFAULT 0
            );
            CREATE TABLE IF NOT EXISTS agent_state (
                key TEXT PRIMARY KEY,
                value TEXT
            );";
        cmd.ExecuteNonQuery();
    }

    public void SavePendingSegment(Upload.SegmentInfo segment)
    {
        using var conn = GetConnection();
        conn.Open();
        using var cmd = conn.CreateCommand();
        cmd.CommandText = "INSERT INTO pending_segments (file_path, session_id, sequence_num, size_bytes) VALUES (@p, @s, @n, @sz)";
        cmd.Parameters.AddWithValue("@p", segment.FilePath);
        cmd.Parameters.AddWithValue("@s", segment.SessionId);
        cmd.Parameters.AddWithValue("@n", segment.SequenceNum);
        cmd.Parameters.AddWithValue("@sz", new FileInfo(segment.FilePath).Length);
        cmd.ExecuteNonQuery();
    }

    public void MarkSegmentUploaded(string filePath)
    {
        using var conn = GetConnection();
        conn.Open();
        using var cmd = conn.CreateCommand();
        cmd.CommandText = "UPDATE pending_segments SET uploaded = 1 WHERE file_path = @p";
        cmd.Parameters.AddWithValue("@p", filePath);
        cmd.ExecuteNonQuery();
    }

    public List<Upload.SegmentInfo> GetPendingSegments()
    {
        var list = new List<Upload.SegmentInfo>();
        using var conn = GetConnection();
        conn.Open();
        using var cmd = conn.CreateCommand();
        cmd.CommandText = "SELECT file_path, session_id, sequence_num FROM pending_segments WHERE uploaded = 0 ORDER BY id";
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

    private SqliteConnection GetConnection() => new($"Data Source={_dbPath}");
}
