import Foundation

public final class AgentConfig {
    public static let agentVersion = "1.0.0"

    public var serverUrl: String = ""
    public var registrationToken: String = ""
    public var dataPath: String

    public var heartbeatIntervalSec: Int = 30
    public var segmentDurationSec: Int = 60
    public var captureFps: Int = 1
    public var quality: String = "low"
    public var resolution: String = "1280x720"
    public var sessionMaxDurationMin: Int = 60
    public var autoStart: Bool = false
    public var recordingEnabled: Bool = true
    public var configReceivedFromServer: Bool = false

    public init() {
        let appSupport = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first!
        dataPath = appSupport.appendingPathComponent("Kadero").path
    }

    public var logsPath: String {
        let logsDir = FileManager.default.urls(for: .libraryDirectory, in: .userDomainMask).first!
            .appendingPathComponent("Logs/Kadero")
        return logsDir.path
    }

    public func ensureDirectories() {
        let fm = FileManager.default
        try? fm.createDirectory(atPath: dataPath, withIntermediateDirectories: true)
        try? fm.createDirectory(atPath: logsPath, withIntermediateDirectories: true)
    }

    public func applyServerConfig(_ config: ServerConfig) {
        if let v = config.heartbeatIntervalSec, v > 0 { heartbeatIntervalSec = v }
        if let v = config.segmentDurationSec, v > 0 { segmentDurationSec = v }
        if let v = config.captureFps, v > 0 { captureFps = v }
        if let v = config.quality, !v.isEmpty { quality = v }
        if let v = config.resolution, !v.isEmpty { resolution = v }
        if let v = config.sessionMaxDurationMin, v > 0 {
            sessionMaxDurationMin = v
        } else if let v = config.sessionMaxDurationHours, v > 0 {
            sessionMaxDurationMin = v * 60
        }
        if let v = config.autoStart { autoStart = v }
        if let v = config.recordingEnabled { recordingEnabled = v }
        if let v = config.configReceivedFromServer { configReceivedFromServer = v }
    }

    public func applyDeviceSettings(_ settings: [String: AnyCodableValue]) {
        if let v = settings["capture_fps"]?.intValue, v > 0 { captureFps = v }
        if let v = settings["resolution"]?.stringValue, !v.isEmpty { resolution = v }
        if let v = settings["quality"]?.stringValue, !v.isEmpty { quality = v }
        if let v = settings["segment_duration_sec"]?.intValue, v > 0 { segmentDurationSec = v }
        if let v = settings["auto_start"]?.boolValue { autoStart = v }
        if let v = settings["session_max_duration_min"]?.intValue, v > 0 { sessionMaxDurationMin = v }
        if let v = settings["session_max_duration_hours"]?.intValue, v > 0 { sessionMaxDurationMin = v * 60 }
        if let v = settings["heartbeat_interval_sec"]?.intValue, v > 0 { heartbeatIntervalSec = v }
        if let v = settings["recording_enabled"]?.boolValue { recordingEnabled = v }
        configReceivedFromServer = true
    }
}
