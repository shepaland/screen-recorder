import Foundation
import SQLite3

public enum OfflineItemType: String { case segment, focus, input, audit }

public struct PendingItem {
    public let id: String; public let itemType: OfflineItemType; public let payload: Data
    public let sessionId: String?; public let createdTs: String; public let retryCount: Int
}

public final class OfflineStore {
    private var db: OpaquePointer?
    private let queue = DispatchQueue(label: "ru.kadero.offlinestore", qos: .utility)
    private let log = Logger("OfflineStore")
    private let iso = ISO8601DateFormatter()
    private let ttlDays = 7

    public init(path: String) {
        queue.sync {
            if sqlite3_open(path, &db) != SQLITE_OK { log.error("Failed to open SQLite: \(path)"); db = nil; return }
            exec("PRAGMA journal_mode=WAL")
            exec("""
                CREATE TABLE IF NOT EXISTS pending_items (
                    id TEXT PRIMARY KEY, item_type TEXT NOT NULL, payload TEXT NOT NULL,
                    session_id TEXT, created_ts TEXT NOT NULL, retry_count INTEGER DEFAULT 0,
                    max_retries INTEGER DEFAULT 10, status TEXT DEFAULT 'pending',
                    last_error TEXT, expires_at TEXT NOT NULL)
            """)
            exec("CREATE INDEX IF NOT EXISTS idx_pending_type_status ON pending_items(item_type, status)")
            exec("CREATE INDEX IF NOT EXISTS idx_pending_expires ON pending_items(expires_at)")
            log.info("OfflineStore opened: \(path)")
        }
    }

    deinit { if let db = db { sqlite3_close(db) } }
    public var isAvailable: Bool { db != nil }

    public func enqueue(id: String, type: OfflineItemType, payload: Data, sessionId: String? = nil) {
        guard let db = db else { return }
        queue.sync {
            let now = iso.string(from: Date()); let expires = iso.string(from: Date().addingTimeInterval(Double(ttlDays) * 86400))
            let payloadStr = String(data: payload, encoding: .utf8) ?? ""
            var stmt: OpaquePointer?
            guard sqlite3_prepare_v2(db, "INSERT OR REPLACE INTO pending_items (id, item_type, payload, session_id, created_ts, expires_at) VALUES (?, ?, ?, ?, ?, ?)", -1, &stmt, nil) == SQLITE_OK else { return }
            defer { sqlite3_finalize(stmt) }
            sqlite3_bind_text(stmt, 1, (id as NSString).utf8String, -1, nil)
            sqlite3_bind_text(stmt, 2, (type.rawValue as NSString).utf8String, -1, nil)
            sqlite3_bind_text(stmt, 3, (payloadStr as NSString).utf8String, -1, nil)
            if let sid = sessionId { sqlite3_bind_text(stmt, 4, (sid as NSString).utf8String, -1, nil) } else { sqlite3_bind_null(stmt, 4) }
            sqlite3_bind_text(stmt, 5, (now as NSString).utf8String, -1, nil)
            sqlite3_bind_text(stmt, 6, (expires as NSString).utf8String, -1, nil)
            if sqlite3_step(stmt) != SQLITE_DONE { log.error("Enqueue failed: \(sqlError())") }
        }
    }

    public func dequeue(type: OfflineItemType, limit: Int = 100) -> [PendingItem] {
        guard let db = db else { return [] }
        return queue.sync {
            var items: [PendingItem] = []; var stmt: OpaquePointer?
            guard sqlite3_prepare_v2(db, "SELECT id, item_type, payload, session_id, created_ts, retry_count FROM pending_items WHERE item_type = ? AND status = 'pending' ORDER BY created_ts ASC LIMIT ?", -1, &stmt, nil) == SQLITE_OK else { return [] }
            defer { sqlite3_finalize(stmt) }
            sqlite3_bind_text(stmt, 1, (type.rawValue as NSString).utf8String, -1, nil)
            sqlite3_bind_int(stmt, 2, Int32(limit))
            while sqlite3_step(stmt) == SQLITE_ROW {
                items.append(PendingItem(id: String(cString: sqlite3_column_text(stmt, 0)), itemType: type,
                    payload: String(cString: sqlite3_column_text(stmt, 2)).data(using: .utf8) ?? Data(),
                    sessionId: sqlite3_column_text(stmt, 3).map { String(cString: $0) },
                    createdTs: String(cString: sqlite3_column_text(stmt, 4)), retryCount: Int(sqlite3_column_int(stmt, 5))))
            }
            return items
        }
    }

    public func markInProgress(id: String) { guard db != nil else { return }; queue.sync { exec("UPDATE pending_items SET status = 'in_progress' WHERE id = '\(esc(id))'") } }
    public func markCompleted(id: String) { guard db != nil else { return }; queue.sync { exec("DELETE FROM pending_items WHERE id = '\(esc(id))'") } }

    public func markCompletedBatch(ids: [String]) {
        guard db != nil, !ids.isEmpty else { return }
        queue.sync { exec("DELETE FROM pending_items WHERE id IN (\(ids.map { "'\(esc($0))'" }.joined(separator: ",")))") }
    }

    public func markRetry(id: String, error: String) {
        guard let db = db else { return }
        queue.sync {
            var stmt: OpaquePointer?
            guard sqlite3_prepare_v2(db, "UPDATE pending_items SET retry_count = retry_count + 1, last_error = ?, status = CASE WHEN retry_count + 1 >= max_retries THEN 'failed' ELSE 'pending' END WHERE id = ?", -1, &stmt, nil) == SQLITE_OK else { return }
            defer { sqlite3_finalize(stmt) }
            sqlite3_bind_text(stmt, 1, (error as NSString).utf8String, -1, nil)
            sqlite3_bind_text(stmt, 2, (id as NSString).utf8String, -1, nil)
            sqlite3_step(stmt)
        }
    }

    public func pendingCount(type: OfflineItemType) -> Int {
        guard let db = db else { return 0 }
        return queue.sync {
            var stmt: OpaquePointer?
            guard sqlite3_prepare_v2(db, "SELECT COUNT(*) FROM pending_items WHERE item_type = ? AND status = 'pending'", -1, &stmt, nil) == SQLITE_OK else { return 0 }
            defer { sqlite3_finalize(stmt) }
            sqlite3_bind_text(stmt, 1, (type.rawValue as NSString).utf8String, -1, nil)
            return sqlite3_step(stmt) == SQLITE_ROW ? Int(sqlite3_column_int(stmt, 0)) : 0
        }
    }

    @discardableResult
    public func cleanup() -> Int {
        guard db != nil else { return 0 }
        return queue.sync {
            let now = iso.string(from: Date())
            exec("DELETE FROM pending_items WHERE expires_at < '\(esc(now))' OR status = 'failed'")
            let deleted = Int(sqlite3_changes(db))
            if deleted > 0 { log.info("Cleanup: removed \(deleted) expired/failed items") }
            return deleted
        }
    }

    private func exec(_ sql: String) {
        var err: UnsafeMutablePointer<CChar>?
        if sqlite3_exec(db, sql, nil, nil, &err) != SQLITE_OK {
            let msg = err.map { String(cString: $0) } ?? "unknown"; log.error("SQL error: \(msg)"); sqlite3_free(err)
        }
    }
    private func sqlError() -> String { db.map { String(cString: sqlite3_errmsg($0)) } ?? "no db" }
    private func esc(_ s: String) -> String { s.replacingOccurrences(of: "'", with: "''") }
}
