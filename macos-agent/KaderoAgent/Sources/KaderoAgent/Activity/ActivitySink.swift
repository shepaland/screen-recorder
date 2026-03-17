import Foundation

public final class ActivitySink {
    private let apiClient: ApiClient
    private let config: AgentConfig
    private let windowTracker: WindowTracker
    private let idleDetector: IdleDetector
    private let inputTracker: InputEventTracker
    private let auditTracker: AuditEventTracker
    private let log = Logger("ActivitySink")
    private let offlineStore: OfflineStore?

    private var pollTask: Task<Void, Never>?
    private var lastWindowInfo: WindowInfo?
    private var focusStartTime: Date?
    private var pendingIntervals: [FocusIntervalDTO] = []
    private var pendingInputBatches: [InputEventBatch] = []
    private var pendingAuditEvents: [AuditEventDTO] = []

    public var sessionIdProvider: (() -> String?) = { nil }

    private let iso = ISO8601DateFormatter()
    private let batchIntervalSec: UInt64 = 30
    private let cleanupIntervalSec: UInt64 = 300

    private let encoder: JSONEncoder = { let e = JSONEncoder(); e.keyEncodingStrategy = .convertToSnakeCase; return e }()
    private let decoder: JSONDecoder = { let d = JSONDecoder(); d.keyDecodingStrategy = .convertFromSnakeCase; return d }()

    public init(apiClient: ApiClient, config: AgentConfig, offlineStore: OfflineStore? = nil) {
        self.apiClient = apiClient; self.config = config; self.offlineStore = offlineStore
        self.windowTracker = WindowTracker(); self.idleDetector = IdleDetector()
        self.inputTracker = InputEventTracker(windowTracker: windowTracker)
        self.auditTracker = AuditEventTracker()
    }

    public func start() {
        inputTracker.start(); auditTracker.start()
        focusStartTime = Date(); lastWindowInfo = windowTracker.activeWindow()
        pollTask = Task { [weak self] in await self?.pollLoop() }
        log.info("Activity sink started")
    }

    public func stop() {
        pollTask?.cancel(); pollTask = nil; finalizeFocusInterval()
        inputTracker.stop(); auditTracker.stop(); persistPendingToOffline()
        Task { [weak self] in await self?.sendAll() }
        log.info("Activity sink stopped")
    }

    public func recordAuditEvent(_ eventType: String, details: [String: String]? = nil) {
        auditTracker.record(eventType, details: details)
    }

    private func pollLoop() async {
        var tickCount: UInt64 = 0
        while !Task.isCancelled {
            try? await Task.sleep(nanoseconds: 1_000_000_000); tickCount += 1
            pollActiveWindow()
            if tickCount % batchIntervalSec == 0 { await sendAll() }
            if tickCount % cleanupIntervalSec == 0 { offlineStore?.cleanup() }
        }
    }

    private func pollActiveWindow() {
        let current = windowTracker.activeWindow()
        if current?.processName != lastWindowInfo?.processName || current?.windowTitle != lastWindowInfo?.windowTitle {
            finalizeFocusInterval(); focusStartTime = Date(); lastWindowInfo = current
        }
    }

    private func finalizeFocusInterval() {
        guard let window = lastWindowInfo, let startTime = focusStartTime else { return }
        let endTime = Date(); let durationMs = Int(endTime.timeIntervalSince(startTime) * 1000)
        guard durationMs > 500 else { return }
        let browser = BrowserDomainParser.parse(bundleId: window.bundleId, windowTitle: window.windowTitle)
        pendingIntervals.append(FocusIntervalDTO(
            id: UUID().uuidString, processName: window.processName, windowTitle: window.windowTitle,
            isBrowser: browser.isBrowser, browserName: browser.browserName, domain: browser.domain,
            startedAt: iso.string(from: startTime), endedAt: iso.string(from: endTime), durationMs: durationMs,
            sessionId: sessionIdProvider(), windowX: window.x, windowY: window.y,
            windowWidth: window.width, windowHeight: window.height, isMaximized: false,
            isFullscreen: window.isFullscreen, monitorIndex: window.monitorIndex, isIdle: idleDetector.isIdle))
    }

    private func sendAll() async {
        await sendFocusIntervals(); await sendInputEvents(); await sendAuditEvents()
    }

    private func sendFocusIntervals() async {
        finalizeFocusInterval(); focusStartTime = Date()
        // Load from offline
        var offlineIds: [String] = []
        if let store = offlineStore {
            for item in store.dequeue(type: .focus, limit: 200) {
                if let dto = try? decoder.decode(FocusIntervalDTO.self, from: item.payload) {
                    pendingIntervals.append(dto); offlineIds.append(item.id)
                } else { store.markCompleted(id: item.id) }
            }
        }
        guard !pendingIntervals.isEmpty else { return }
        let intervals = pendingIntervals; pendingIntervals = []
        let batch = FocusIntervalBatch(deviceId: apiClient.deviceId ?? "", username: NSUserName(), intervals: intervals)
        let url = "\(config.serverUrl.trimSlash())/api/ingest/v1/activity/focus-intervals"
        do {
            let resp: ActivityAcceptResponse = try await apiClient.post(url, body: batch)
            log.debug("Focus intervals sent: accepted=\(resp.accepted ?? 0)")
            offlineStore?.markCompletedBatch(ids: offlineIds)
        } catch {
            log.error("Failed to send focus intervals: \(error.localizedDescription)")
            if let store = offlineStore {
                for id in offlineIds { store.markRetry(id: id, error: "send failed") }
                for interval in intervals { if let data = try? encoder.encode(interval) { store.enqueue(id: interval.id, type: .focus, payload: data, sessionId: interval.sessionId) } }
            } else { pendingIntervals = intervals + pendingIntervals }
        }
    }

    private func sendInputEvents() async {
        let (clicks, keystrokeCount, hasTypingBurst, scrolls, clips) = inputTracker.flush()
        guard keystrokeCount > 0 || !clicks.isEmpty || !scrolls.isEmpty || !clips.isEmpty else {
            await retrySendOfflineInput(); return
        }
        let now = Date(); let window = lastWindowInfo
        let mouseClickDTOs = clicks.map { MouseClickDTO(id: UUID().uuidString, timestamp: iso.string(from: $0.timestamp), x: $0.x, y: $0.y, button: $0.button, clickType: $0.clickType, processName: $0.processName, windowTitle: $0.windowTitle, sessionId: sessionIdProvider()) }
        var keyboardMetrics: [KeyboardMetricDTO] = []
        if keystrokeCount > 0 {
            keyboardMetrics.append(KeyboardMetricDTO(id: UUID().uuidString, intervalStart: iso.string(from: now.addingTimeInterval(-Double(batchIntervalSec))), intervalEnd: iso.string(from: now), keystrokeCount: keystrokeCount, hasTypingBurst: hasTypingBurst, processName: window?.processName ?? "Unknown", windowTitle: window?.windowTitle ?? "", sessionId: sessionIdProvider()))
        }
        let scrollDTOs = scrolls.map { ScrollEventDTO(id: UUID().uuidString, timestamp: iso.string(from: $0.timestamp), deltaY: $0.deltaY, processName: $0.processName, sessionId: sessionIdProvider()) }
        let clipDTOs = clips.map { ClipboardEventDTO(id: UUID().uuidString, timestamp: iso.string(from: $0.timestamp), processName: $0.processName, sessionId: sessionIdProvider()) }
        let batch = InputEventBatch(deviceId: apiClient.deviceId ?? "", username: NSUserName(), mouseClicks: mouseClickDTOs, keyboardMetrics: keyboardMetrics, scrollEvents: scrollDTOs, clipboardEvents: clipDTOs)
        let url = "\(config.serverUrl.trimSlash())/api/ingest/v1/activity/input-events"
        do {
            let resp: ActivityAcceptResponse = try await apiClient.post(url, body: batch)
            log.debug("Input events sent: accepted=\(resp.accepted ?? 0)")
        } catch {
            log.error("Failed to send input events: \(error.localizedDescription)")
            if let store = offlineStore, let data = try? encoder.encode(batch) { store.enqueue(id: UUID().uuidString, type: .input, payload: data) }
            else { pendingInputBatches.append(batch) }
        }
        await retrySendOfflineInput()
    }

    private func sendAuditEvents() async {
        let events = auditTracker.flush()
        let newDtos = events.map { AuditEventDTO(id: UUID().uuidString, eventType: $0.eventType, eventTs: iso.string(from: $0.timestamp), sessionId: sessionIdProvider(), details: $0.details) }
        var offlineIds: [String] = []
        if let store = offlineStore {
            for item in store.dequeue(type: .audit, limit: 200) {
                if let dto = try? decoder.decode(AuditEventDTO.self, from: item.payload) { pendingAuditEvents.append(dto); offlineIds.append(item.id) }
                else { store.markCompleted(id: item.id) }
            }
        }
        let dtos = pendingAuditEvents + newDtos; pendingAuditEvents = []
        guard !dtos.isEmpty else { return }
        let batch = AuditEventBatch(deviceId: apiClient.deviceId ?? "", username: NSUserName(), events: dtos)
        let url = "\(config.serverUrl.trimSlash())/api/ingest/v1/audit-events"
        do {
            let resp: ActivityAcceptResponse = try await apiClient.post(url, body: batch)
            log.debug("Audit events sent: accepted=\(resp.accepted ?? 0)")
            offlineStore?.markCompletedBatch(ids: offlineIds)
        } catch {
            log.error("Failed to send audit events: \(error.localizedDescription)")
            if let store = offlineStore {
                for id in offlineIds { store.markRetry(id: id, error: "send failed") }
                for dto in dtos { if let data = try? encoder.encode(dto) { store.enqueue(id: dto.id, type: .audit, payload: data, sessionId: dto.sessionId) } }
            } else { pendingAuditEvents = dtos + pendingAuditEvents }
        }
    }

    private func persistPendingToOffline() {
        guard let store = offlineStore else { return }
        for i in pendingIntervals { if let d = try? encoder.encode(i) { store.enqueue(id: i.id, type: .focus, payload: d, sessionId: i.sessionId) } }
        pendingIntervals = []
        for b in pendingInputBatches { if let d = try? encoder.encode(b) { store.enqueue(id: UUID().uuidString, type: .input, payload: d) } }
        pendingInputBatches = []
        for a in pendingAuditEvents { if let d = try? encoder.encode(a) { store.enqueue(id: a.id, type: .audit, payload: d, sessionId: a.sessionId) } }
        pendingAuditEvents = []
    }

    private func retrySendOfflineInput() async {
        guard let store = offlineStore else { return }
        let stored = store.dequeue(type: .input, limit: 10); guard !stored.isEmpty else { return }
        let url = "\(config.serverUrl.trimSlash())/api/ingest/v1/activity/input-events"
        for item in stored {
            guard let batch = try? decoder.decode(InputEventBatch.self, from: item.payload) else { store.markCompleted(id: item.id); continue }
            do { let _: ActivityAcceptResponse = try await apiClient.post(url, body: batch); store.markCompleted(id: item.id) }
            catch { store.markRetry(id: item.id, error: error.localizedDescription) }
        }
    }
}
