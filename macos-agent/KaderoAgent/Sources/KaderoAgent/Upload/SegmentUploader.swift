import Foundation
import CryptoKit

public struct SegmentUploadItem {
    public let fileURL: URL
    public let sessionId: String
    public let sequenceNum: Int
    public let durationMs: Int
    public let metadata: SessionMetadata

    public init(fileURL: URL, sessionId: String, sequenceNum: Int, durationMs: Int, metadata: SessionMetadata) {
        self.fileURL = fileURL
        self.sessionId = sessionId
        self.sequenceNum = sequenceNum
        self.durationMs = durationMs
        self.metadata = metadata
    }
}

public enum UploadError: Error, LocalizedError {
    case fileNotFound(String)
    case sessionClosed
    case discarded(String)

    public var errorDescription: String? {
        switch self {
        case .fileNotFound(let path): return "Segment file not found: \(path)"
        case .sessionClosed: return "Session closed (409)"
        case .discarded(let reason): return "Segment discarded: \(reason)"
        }
    }
}

public final class SegmentUploader {
    private let apiClient: ApiClient
    private let config: AgentConfig
    private let log = Logger("SegmentUploader", file: "kadero-upload.log")

    public var onSessionInvalidated: (() -> Void)?

    public init(apiClient: ApiClient, config: AgentConfig) {
        self.apiClient = apiClient
        self.config = config
    }

    public func upload(_ item: SegmentUploadItem) async throws {
        let fm = FileManager.default
        guard fm.fileExists(atPath: item.fileURL.path) else {
            throw UploadError.fileNotFound(item.fileURL.path)
        }

        let fileData = try Data(contentsOf: item.fileURL)
        let checksum = SHA256.hash(data: fileData)
        let checksumHex = checksum.map { String(format: "%02x", $0) }.joined()

        log.info("Uploading segment \(item.sequenceNum) (\(fileData.count) bytes, sha256=\(checksumHex.prefix(12))...)")

        let presignResponse = try await presign(item: item, sizeBytes: fileData.count, checksum: checksumHex)
        log.debug("Presign OK: segment_id=\(presignResponse.segmentId)")

        try await uploadToS3(url: presignResponse.uploadUrl, data: fileData)
        log.debug("S3 upload OK")

        try await confirm(segmentId: presignResponse.segmentId, checksum: checksumHex)
        log.debug("Confirm OK")

        try? fm.removeItem(at: item.fileURL)
        log.info("Segment \(item.sequenceNum) uploaded and cleaned up")
    }

    private func presign(item: SegmentUploadItem, sizeBytes: Int, checksum: String) async throws -> PresignResponse {
        let baseUrl = config.serverUrl.trimSlash()
        let url = "\(baseUrl)/api/ingest/v1/ingest/presign"

        let request = PresignRequest(
            deviceId: apiClient.deviceId ?? "",
            sessionId: item.sessionId,
            sequenceNum: item.sequenceNum,
            sizeBytes: sizeBytes,
            durationMs: item.durationMs,
            checksumSha256: checksum,
            contentType: "video/mp4",
            metadata: item.metadata
        )

        do {
            return try await apiClient.post(url, body: request)
        } catch let error as ApiError {
            if case .httpError(let code, _) = error {
                if code == 409 {
                    log.warn("Presign 409 — session closed, discarding segment")
                    onSessionInvalidated?()
                    throw UploadError.sessionClosed
                }
                if code == 400 || code == 404 {
                    log.warn("Presign \(code) — discarding segment")
                    throw UploadError.discarded("HTTP \(code)")
                }
            }
            throw error
        }
    }

    private func uploadToS3(url: String, data: Data) async throws {
        do {
            try await apiClient.putBinary(url: url, data: data, contentType: "video/mp4")
        } catch let error as ApiError {
            if case .httpError(let code, _) = error, (400..<500).contains(code) {
                throw UploadError.discarded("S3 \(code)")
            }
            throw error
        }
    }

    private func confirm(segmentId: String, checksum: String) async throws {
        let baseUrl = config.serverUrl.trimSlash()
        let url = "\(baseUrl)/api/ingest/v1/ingest/confirm"
        let request = ConfirmRequest(segmentId: segmentId, checksumSha256: checksum)
        let _: EmptyResponse = try await apiClient.post(url, body: request)
    }
}
