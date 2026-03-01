package com.prg.agent.storage;

import com.prg.agent.config.AgentConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * SQLite-backed local database for offline segment buffering.
 *
 * <p>Stores metadata about segments that could not be uploaded immediately
 * (due to network issues) and need to be retried later.
 * Also provides a simple key-value store for agent state persistence.
 */
public class LocalDatabase {

    private static final Logger log = LoggerFactory.getLogger(LocalDatabase.class);
    private static final String DB_FILENAME = "agent.db";

    private final String dbUrl;

    public LocalDatabase(AgentConfig config) {
        String dbPath = config.getDataDir() + File.separator + DB_FILENAME;
        this.dbUrl = "jdbc:sqlite:" + dbPath;
        initialize();
    }

    private void initialize() {
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS pending_segments (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    session_id TEXT NOT NULL,
                    sequence_num INTEGER NOT NULL,
                    file_path TEXT NOT NULL,
                    size_bytes INTEGER NOT NULL,
                    checksum TEXT NOT NULL,
                    retry_count INTEGER DEFAULT 0,
                    created_at TEXT NOT NULL,
                    UNIQUE(session_id, sequence_num)
                )
            """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS agent_state (
                    key TEXT PRIMARY KEY,
                    value TEXT NOT NULL
                )
            """);

            log.info("Local database initialized at {}", dbUrl);
        } catch (SQLException e) {
            log.error("Failed to initialize local database", e);
            throw new RuntimeException("Failed to initialize local database", e);
        }
    }

    private Connection getConnection() throws SQLException {
        return DriverManager.getConnection(dbUrl);
    }

    // ---- Pending Segments ----

    /**
     * Adds a segment to the pending queue for later upload.
     */
    public void addPendingSegment(String sessionId, int sequenceNum, String filePath,
                                   long sizeBytes, String checksum) {
        String sql = """
            INSERT OR REPLACE INTO pending_segments 
            (session_id, sequence_num, file_path, size_bytes, checksum, retry_count, created_at)
            VALUES (?, ?, ?, ?, ?, 0, ?)
        """;

        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            ps.setInt(2, sequenceNum);
            ps.setString(3, filePath);
            ps.setLong(4, sizeBytes);
            ps.setString(5, checksum);
            ps.setString(6, Instant.now().toString());
            ps.executeUpdate();
            log.debug("Added pending segment: session={}, seq={}", sessionId, sequenceNum);
        } catch (SQLException e) {
            log.error("Failed to add pending segment", e);
        }
    }

    /**
     * Returns all pending segments ordered by creation time.
     */
    public List<PendingSegment> getPendingSegments() {
        String sql = "SELECT * FROM pending_segments ORDER BY created_at ASC";
        List<PendingSegment> segments = new ArrayList<>();

        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            while (rs.next()) {
                PendingSegment seg = new PendingSegment();
                seg.setId(rs.getInt("id"));
                seg.setSessionId(rs.getString("session_id"));
                seg.setSequenceNum(rs.getInt("sequence_num"));
                seg.setFilePath(rs.getString("file_path"));
                seg.setSizeBytes(rs.getLong("size_bytes"));
                seg.setChecksum(rs.getString("checksum"));
                seg.setRetryCount(rs.getInt("retry_count"));
                seg.setCreatedAt(rs.getString("created_at"));
                segments.add(seg);
            }
        } catch (SQLException e) {
            log.error("Failed to get pending segments", e);
        }

        return segments;
    }

    /**
     * Returns pending segments with retry_count less than maxRetries.
     */
    public List<PendingSegment> getRetryableSegments(int maxRetries) {
        String sql = "SELECT * FROM pending_segments WHERE retry_count < ? ORDER BY created_at ASC";
        List<PendingSegment> segments = new ArrayList<>();

        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, maxRetries);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    PendingSegment seg = new PendingSegment();
                    seg.setId(rs.getInt("id"));
                    seg.setSessionId(rs.getString("session_id"));
                    seg.setSequenceNum(rs.getInt("sequence_num"));
                    seg.setFilePath(rs.getString("file_path"));
                    seg.setSizeBytes(rs.getLong("size_bytes"));
                    seg.setChecksum(rs.getString("checksum"));
                    seg.setRetryCount(rs.getInt("retry_count"));
                    seg.setCreatedAt(rs.getString("created_at"));
                    segments.add(seg);
                }
            }
        } catch (SQLException e) {
            log.error("Failed to get retryable segments", e);
        }

        return segments;
    }

    /**
     * Increments the retry count for a pending segment.
     */
    public void incrementRetryCount(int segmentId) {
        String sql = "UPDATE pending_segments SET retry_count = retry_count + 1 WHERE id = ?";

        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, segmentId);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to increment retry count for segment {}", segmentId, e);
        }
    }

    /**
     * Removes a pending segment after successful upload.
     */
    public void removePendingSegment(int segmentId) {
        String sql = "DELETE FROM pending_segments WHERE id = ?";

        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, segmentId);
            ps.executeUpdate();
            log.debug("Removed pending segment {}", segmentId);
        } catch (SQLException e) {
            log.error("Failed to remove pending segment {}", segmentId, e);
        }
    }

    /**
     * Removes a pending segment by session and sequence number.
     */
    public void removePendingSegment(String sessionId, int sequenceNum) {
        String sql = "DELETE FROM pending_segments WHERE session_id = ? AND sequence_num = ?";

        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            ps.setInt(2, sequenceNum);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to remove pending segment session={} seq={}", sessionId, sequenceNum, e);
        }
    }

    /**
     * Returns the count of pending segments.
     */
    public int getPendingSegmentCount() {
        String sql = "SELECT COUNT(*) FROM pending_segments";

        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next()) {
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            log.error("Failed to get pending segment count", e);
        }
        return 0;
    }

    // ---- Agent State (key-value) ----

    /**
     * Saves a key-value pair to the agent state store.
     */
    public void saveState(String key, String value) {
        String sql = "INSERT OR REPLACE INTO agent_state (key, value) VALUES (?, ?)";

        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to save state key={}", key, e);
        }
    }

    /**
     * Loads a value from the agent state store.
     *
     * @return the value, or null if not found
     */
    public String loadState(String key) {
        String sql = "SELECT value FROM agent_state WHERE key = ?";

        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("value");
                }
            }
        } catch (SQLException e) {
            log.error("Failed to load state key={}", key, e);
        }
        return null;
    }

    /**
     * Deletes a key from the agent state store.
     */
    public void deleteState(String key) {
        String sql = "DELETE FROM agent_state WHERE key = ?";

        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, key);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to delete state key={}", key, e);
        }
    }

    /**
     * Data class for pending segment records.
     */
    @lombok.Data
    public static class PendingSegment {
        private int id;
        private String sessionId;
        private int sequenceNum;
        private String filePath;
        private long sizeBytes;
        private String checksum;
        private int retryCount;
        private String createdAt;
    }
}
