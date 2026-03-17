import Foundation

public actor UploadQueueActor {
    private var queue: [SegmentUploadItem] = []
    private var _isProcessing = false

    public var isEmpty: Bool { queue.isEmpty }
    public var count: Int { queue.count }
    public var isProcessing: Bool { _isProcessing }

    public func enqueue(_ item: SegmentUploadItem) { queue.append(item) }
    public func dequeue() -> SegmentUploadItem? {
        guard !queue.isEmpty else { return nil }
        return queue.removeFirst()
    }
    public func setProcessing(_ value: Bool) { _isProcessing = value }
}

public final class UploadQueue: @unchecked Sendable {
    private let uploader: SegmentUploader
    private let log = Logger("UploadQueue", file: "kadero-upload.log")
    private let state = UploadQueueActor()
    private var taskHandle: Task<Void, Never>?
    private let offlineStore: OfflineStore?

    private let maxRetries = 3
    private let retryDelaySec: UInt64 = 5

    private let encoder: JSONEncoder = {
        let e = JSONEncoder(); e.keyEncodingStrategy = .convertToSnakeCase; return e
    }()
    private let decoder: JSONDecoder = {
        let d = JSONDecoder(); d.keyDecodingStrategy = .convertFromSnakeCase; return d
    }()

    public init(uploader: SegmentUploader, offlineStore: OfflineStore? = nil) {
        self.uploader = uploader
        self.offlineStore = offlineStore
    }

    public func restoreFromOffline() {
        guard let store = offlineStore else { return }
        let items = store.dequeue(type: .segment, limit: 100)
        guard !items.isEmpty else { return }
        log.info("Restoring \(items.count) pending segments from offline store")
        for item in items {
            guard let uploadItem = decodeSegmentItem(item.payload) else {
                store.markCompleted(id: item.id); continue
            }
            guard FileManager.default.fileExists(atPath: uploadItem.fileURL.path) else {
                log.warn("Orphan segment file missing, removing"); store.markCompleted(id: item.id); continue
            }
            Task { await state.enqueue(uploadItem); await startProcessingIfNeeded() }
        }
    }

    public func enqueue(_ item: SegmentUploadItem) {
        if let store = offlineStore, let data = try? encoder.encode(SegmentItemCodable(item)) {
            store.enqueue(id: UUID().uuidString, type: .segment, payload: data, sessionId: item.sessionId)
        }
        Task {
            await state.enqueue(item)
            log.info("Enqueued segment \(item.sequenceNum) (queue: \(await state.count))")
            await startProcessingIfNeeded()
        }
    }

    public var pendingCount: Int {
        var result = 0
        let sem = DispatchSemaphore(value: 0)
        Task { result = await state.count; sem.signal() }
        sem.wait()
        return result
    }

    public func stop() { taskHandle?.cancel(); taskHandle = nil }

    public func drain() async {
        while await !state.isEmpty { try? await Task.sleep(nanoseconds: 500_000_000) }
        while await state.isProcessing { try? await Task.sleep(nanoseconds: 200_000_000) }
    }

    private func startProcessingIfNeeded() async {
        guard await !state.isProcessing else { return }
        await state.setProcessing(true)
        taskHandle = Task { [weak self] in await self?.processLoop() }
    }

    private func processLoop() async {
        while !Task.isCancelled {
            guard let item = await state.dequeue() else {
                await state.setProcessing(false); return
            }
            var success = false
            for attempt in 1...maxRetries {
                do {
                    try await uploader.upload(item)
                    success = true; removeFromOffline(item); break
                } catch let error as UploadError {
                    switch error {
                    case .sessionClosed, .discarded, .fileNotFound:
                        log.warn("Segment \(item.sequenceNum) discarded: \(error.localizedDescription)")
                        try? FileManager.default.removeItem(at: item.fileURL)
                        removeFromOffline(item); success = true
                    }
                    if success { break }
                } catch {
                    log.error("Upload attempt \(attempt)/\(maxRetries) failed: \(error.localizedDescription)")
                    if attempt < maxRetries {
                        try? await Task.sleep(nanoseconds: retryDelaySec * 1_000_000_000)
                    }
                }
            }
            if !success {
                log.error("Segment \(item.sequenceNum) failed after \(maxRetries) retries, keeping in offline store")
            }
        }
    }

    private func removeFromOffline(_ item: SegmentUploadItem) {
        guard let store = offlineStore else { return }
        let pending = store.dequeue(type: .segment, limit: 500)
        for p in pending {
            if let decoded = decodeSegmentItem(p.payload),
               decoded.sessionId == item.sessionId && decoded.sequenceNum == item.sequenceNum {
                store.markCompleted(id: p.id); break
            }
        }
    }

    private func decodeSegmentItem(_ data: Data) -> SegmentUploadItem? {
        guard let c = try? decoder.decode(SegmentItemCodable.self, from: data) else { return nil }
        return c.toUploadItem()
    }
}

struct SegmentItemCodable: Codable {
    let fileUrl: String; let sessionId: String; let sequenceNum: Int
    let durationMs: Int; let resolution: String; let fps: Int; let codec: String

    init(_ item: SegmentUploadItem) {
        fileUrl = item.fileURL.path; sessionId = item.sessionId; sequenceNum = item.sequenceNum
        durationMs = item.durationMs; resolution = item.metadata.resolution
        fps = item.metadata.fps; codec = item.metadata.codec
    }
    func toUploadItem() -> SegmentUploadItem {
        SegmentUploadItem(fileURL: URL(fileURLWithPath: fileUrl), sessionId: sessionId,
                          sequenceNum: sequenceNum, durationMs: durationMs,
                          metadata: SessionMetadata(resolution: resolution, fps: fps, codec: codec))
    }
}
