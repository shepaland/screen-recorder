import Foundation

// MARK: - Auth DTOs

public struct ValidateRegistrationTokenRequest: Encodable {
    public let registrationToken: String
    public init(registrationToken: String) { self.registrationToken = registrationToken }
}

public struct ValidateRegistrationTokenResponse: Decodable {
    public let valid: Bool
    public let tenantName: String?
    public let tokenName: String?
    public let reason: String?
}

public struct DeviceLoginRequest: Encodable {
    public let registrationToken: String
    public let deviceInfo: DeviceInfo
}

public struct DeviceInfo: Codable {
    public let hostname: String
    public let osVersion: String
    public let agentVersion: String
    public let hardwareId: String

    public init(hostname: String, osVersion: String, agentVersion: String, hardwareId: String) {
        self.hostname = hostname
        self.osVersion = osVersion
        self.agentVersion = agentVersion
        self.hardwareId = hardwareId
    }
}

public struct DeviceLoginResponse: Decodable {
    public let accessToken: String
    public let refreshToken: String
    public let tokenType: String
    public let expiresIn: Int
    public let deviceId: String
    public let deviceStatus: String?
    public let serverConfig: ServerConfig?
}

public struct DeviceRefreshRequest: Encodable {
    public let refreshToken: String
    public let deviceId: String
}

public struct DeviceRefreshResponse: Decodable {
    public let accessToken: String
    public let refreshToken: String
    public let tokenType: String
    public let expiresIn: Int
}

// MARK: - Heartbeat DTOs

public struct HeartbeatRequest: Encodable {
    public let status: String
    public let sessionLocked: Bool
    public let agentVersion: String
    public let metrics: HeartbeatMetrics
}

public struct HeartbeatMetrics: Encodable {
    public let cpuPercent: Double
    public let memoryMb: Double
    public let diskFreeGb: Double
    public let segmentsQueued: Int

    public init(cpuPercent: Double, memoryMb: Double, diskFreeGb: Double, segmentsQueued: Int) {
        self.cpuPercent = cpuPercent
        self.memoryMb = memoryMb
        self.diskFreeGb = diskFreeGb
        self.segmentsQueued = segmentsQueued
    }
}

public struct HeartbeatResponse: Decodable {
    public let serverTs: String?
    public let pendingCommands: [PendingCommand]?
    public let nextHeartbeatSec: Int?
    public let deviceSettings: [String: AnyCodableValue]?
}

public struct PendingCommand: Decodable {
    public let id: String
    public let commandType: String
    public let payload: [String: AnyCodableValue]?
    public let createdTs: String?
}

// MARK: - Server Config

public struct ServerConfig: Codable {
    public var heartbeatIntervalSec: Int?
    public var segmentDurationSec: Int?
    public var captureFps: Int?
    public var quality: String?
    public var ingestBaseUrl: String?
    public var controlPlaneBaseUrl: String?
    public var resolution: String?
    public var sessionMaxDurationMin: Int?
    public var sessionMaxDurationHours: Int?
    public var autoStart: Bool?
    public var recordingEnabled: Bool?
    public var configReceivedFromServer: Bool?

    public init(
        heartbeatIntervalSec: Int? = nil, segmentDurationSec: Int? = nil, captureFps: Int? = nil,
        quality: String? = nil, ingestBaseUrl: String? = nil, controlPlaneBaseUrl: String? = nil,
        resolution: String? = nil, sessionMaxDurationMin: Int? = nil, sessionMaxDurationHours: Int? = nil,
        autoStart: Bool? = nil, recordingEnabled: Bool? = nil, configReceivedFromServer: Bool? = nil
    ) {
        self.heartbeatIntervalSec = heartbeatIntervalSec; self.segmentDurationSec = segmentDurationSec
        self.captureFps = captureFps; self.quality = quality; self.ingestBaseUrl = ingestBaseUrl
        self.controlPlaneBaseUrl = controlPlaneBaseUrl; self.resolution = resolution
        self.sessionMaxDurationMin = sessionMaxDurationMin; self.sessionMaxDurationHours = sessionMaxDurationHours
        self.autoStart = autoStart; self.recordingEnabled = recordingEnabled
        self.configReceivedFromServer = configReceivedFromServer
    }
}

// MARK: - AnyCodableValue

public enum AnyCodableValue: Codable, Hashable {
    case string(String)
    case int(Int)
    case double(Double)
    case bool(Bool)
    case null

    public init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        if let v = try? container.decode(Bool.self) { self = .bool(v) }
        else if let v = try? container.decode(Int.self) { self = .int(v) }
        else if let v = try? container.decode(Double.self) { self = .double(v) }
        else if let v = try? container.decode(String.self) { self = .string(v) }
        else if container.decodeNil() { self = .null }
        else { self = .null }
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        switch self {
        case .string(let v): try container.encode(v)
        case .int(let v): try container.encode(v)
        case .double(let v): try container.encode(v)
        case .bool(let v): try container.encode(v)
        case .null: try container.encodeNil()
        }
    }

    public var stringValue: String? { if case .string(let v) = self { return v }; return nil }
    public var intValue: Int? {
        switch self { case .int(let v): return v; case .double(let v): return Int(v); default: return nil }
    }
    public var doubleValue: Double? {
        switch self { case .double(let v): return v; case .int(let v): return Double(v); default: return nil }
    }
    public var boolValue: Bool? { if case .bool(let v) = self { return v }; return nil }
}

// MARK: - Session DTOs

public struct CreateSessionRequest: Encodable {
    public let deviceId: String
    public let metadata: SessionMetadata
    public init(deviceId: String, metadata: SessionMetadata) {
        self.deviceId = deviceId; self.metadata = metadata
    }
}

public struct SessionMetadata: Codable {
    public let resolution: String
    public let fps: Int
    public let codec: String
    public init(resolution: String, fps: Int, codec: String = "h264") {
        self.resolution = resolution; self.fps = fps; self.codec = codec
    }
}

public struct SessionResponse: Decodable {
    public let id: String
    public let status: String?
    public let startedTs: String?
}

public struct RecordingsPageResponse: Decodable {
    public let content: [SessionResponse]
}

// MARK: - Segment DTOs

public struct PresignRequest: Encodable {
    public let deviceId: String
    public let sessionId: String
    public let sequenceNum: Int
    public let sizeBytes: Int
    public let durationMs: Int
    public let checksumSha256: String
    public let contentType: String
    public let metadata: SessionMetadata
}

public struct PresignResponse: Decodable {
    public let segmentId: String
    public let uploadUrl: String
    public let uploadMethod: String?
    public let expiresInSec: Int?
}

public struct ConfirmRequest: Encodable {
    public let segmentId: String
    public let checksumSha256: String
}

// MARK: - Command ACK DTOs

public struct CommandAckRequest: Encodable {
    public let status: String
    public let result: CommandAckResult?
    public init(status: String, result: CommandAckResult? = nil) {
        self.status = status; self.result = result
    }
}

public struct CommandAckResult: Encodable {
    public let message: String
    public init(message: String) { self.message = message }
}

// MARK: - Device Log DTOs

public struct DeviceLogEntry: Encodable {
    public let logType: String
    public let content: String
    public let fromTs: String
    public let toTs: String
    public init(logType: String, content: String, fromTs: String, toTs: String) {
        self.logType = logType; self.content = content; self.fromTs = fromTs; self.toTs = toTs
    }
}

// MARK: - Focus Interval DTOs

public struct FocusIntervalBatch: Encodable {
    public let deviceId: String
    public let username: String
    public let intervals: [FocusIntervalDTO]
}

public struct FocusIntervalDTO: Codable {
    public let id: String
    public let processName: String
    public let windowTitle: String
    public let isBrowser: Bool
    public let browserName: String?
    public let domain: String?
    public let startedAt: String
    public let endedAt: String
    public let durationMs: Int
    public let sessionId: String?
    public let windowX: Int?
    public let windowY: Int?
    public let windowWidth: Int?
    public let windowHeight: Int?
    public let isMaximized: Bool
    public let isFullscreen: Bool
    public let monitorIndex: Int
    public let isIdle: Bool
}

public struct ActivityAcceptResponse: Decodable {
    public let accepted: Int?
    public let duplicates: Int?
    public let correlationId: String?
}

// MARK: - Input Event DTOs

public struct InputEventBatch: Codable {
    public let deviceId: String
    public let username: String
    public let mouseClicks: [MouseClickDTO]
    public let keyboardMetrics: [KeyboardMetricDTO]
    public let scrollEvents: [ScrollEventDTO]
    public let clipboardEvents: [ClipboardEventDTO]
}

public struct MouseClickDTO: Codable {
    public let id: String
    public let timestamp: String
    public let x: Int
    public let y: Int
    public let button: String
    public let clickType: String
    public let processName: String
    public let windowTitle: String
    public let sessionId: String?
}

public struct KeyboardMetricDTO: Codable {
    public let id: String
    public let intervalStart: String
    public let intervalEnd: String
    public let keystrokeCount: Int
    public let hasTypingBurst: Bool
    public let processName: String
    public let windowTitle: String
    public let sessionId: String?
}

public struct ScrollEventDTO: Codable {
    public let id: String
    public let timestamp: String
    public let deltaY: Double
    public let processName: String
    public let sessionId: String?
}

public struct ClipboardEventDTO: Codable {
    public let id: String
    public let timestamp: String
    public let processName: String
    public let sessionId: String?
}

// MARK: - Audit Event DTOs

public struct AuditEventBatch: Encodable {
    public let deviceId: String
    public let username: String
    public let events: [AuditEventDTO]
}

public struct AuditEventDTO: Codable {
    public let id: String
    public let eventType: String
    public let eventTs: String
    public let sessionId: String?
    public let details: [String: String]?
}

// MARK: - Error Response

public struct ApiErrorResponse: Decodable {
    public let error: String?
    public let code: String?
}
