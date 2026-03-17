import Foundation
import AppKit

public struct AuditEvent {
    public let eventType: String; public let timestamp: Date; public let details: [String: String]?
    public init(eventType: String, timestamp: Date = Date(), details: [String: String]? = nil) {
        self.eventType = eventType; self.timestamp = timestamp; self.details = details
    }
}

public final class AuditEventTracker {
    private let log = Logger("AuditEventTracker")
    private let lock = NSLock()
    private var _events: [AuditEvent] = []
    private var observers: [Any] = []

    public init() {}

    public func start() {
        let dnc = DistributedNotificationCenter.default()
        let wnc = NSWorkspace.shared.notificationCenter

        observers.append(dnc.addObserver(forName: NSNotification.Name("com.apple.screenIsLocked"), object: nil, queue: .main) { [weak self] _ in self?.record("LOCK_SCREEN") })
        observers.append(dnc.addObserver(forName: NSNotification.Name("com.apple.screenIsUnlocked"), object: nil, queue: .main) { [weak self] _ in self?.record("UNLOCK_SCREEN") })
        observers.append(wnc.addObserver(forName: NSWorkspace.sessionDidBecomeActiveNotification, object: nil, queue: .main) { [weak self] _ in self?.record("LOGON") })
        observers.append(wnc.addObserver(forName: NSWorkspace.sessionDidResignActiveNotification, object: nil, queue: .main) { [weak self] _ in self?.record("LOGOFF") })
        observers.append(wnc.addObserver(forName: NSWorkspace.didLaunchApplicationNotification, object: nil, queue: .main) { [weak self] notif in
            if let app = notif.userInfo?[NSWorkspace.applicationUserInfoKey] as? NSRunningApplication {
                self?.record("PROCESS_START", details: ["process_name": app.localizedName ?? "Unknown", "bundle_id": app.bundleIdentifier ?? ""])
            }
        })
        observers.append(wnc.addObserver(forName: NSWorkspace.didTerminateApplicationNotification, object: nil, queue: .main) { [weak self] notif in
            if let app = notif.userInfo?[NSWorkspace.applicationUserInfoKey] as? NSRunningApplication {
                self?.record("PROCESS_STOP", details: ["process_name": app.localizedName ?? "Unknown", "bundle_id": app.bundleIdentifier ?? ""])
            }
        })
        log.info("Audit event tracking started")
    }

    public func stop() {
        let dnc = DistributedNotificationCenter.default(); let wnc = NSWorkspace.shared.notificationCenter
        for obs in observers { dnc.removeObserver(obs); wnc.removeObserver(obs) }
        observers.removeAll()
        log.info("Audit event tracking stopped")
    }

    public func record(_ eventType: String, details: [String: String]? = nil) {
        let event = AuditEvent(eventType: eventType, details: details)
        lock.lock(); _events.append(event); lock.unlock()
        log.debug("Audit event: \(eventType)")
    }

    public func flush() -> [AuditEvent] {
        lock.lock(); let events = _events; _events = []; lock.unlock(); return events
    }
}
