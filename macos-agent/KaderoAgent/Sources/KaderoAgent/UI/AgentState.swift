import Foundation

public final class AgentState {
    public static let shared = AgentState()

    public var status: String = "online" { didSet { onChange?() } }
    public var lastError: String? = nil { didSet { if lastError != nil { lastErrorTime = Date() }; onChange?() } }
    public var lastErrorTime: Date? = nil
    public var lastHeartbeatTime: Date? = nil { didSet { heartbeatOK = true; onChange?() } }
    public var heartbeatOK: Bool = false
    public var isRecording: Bool = false { didSet { if isRecording { recordingStartTime = Date(); segmentsUploaded = 0 } else { recordingStartTime = nil }; onChange?() } }
    public var recordingStartTime: Date? = nil
    public var sessionId: String? = nil
    public var segmentsQueued: Int = 0 { didSet { onChange?() } }
    public var segmentsUploaded: Int = 0
    public var serverUrl: String = ""
    public var deviceId: String = ""
    public var captureFps: Int = 1
    public var resolution: String = "1280x720"

    public var onChange: (() -> Void)?

    private init() {}

    public func setError(_ message: String) {
        lastError = message
    }

    public func clearError() {
        lastError = nil
        lastErrorTime = nil
    }

    /// Auto-clear error after 5 minutes
    public func clearStaleError() {
        guard let time = lastErrorTime, Date().timeIntervalSince(time) > 300 else { return }
        clearError()
    }

    public var recordingDurationFormatted: String {
        guard let start = recordingStartTime else { return "--:--" }
        let elapsed = Int(Date().timeIntervalSince(start))
        let minutes = elapsed / 60
        let seconds = elapsed % 60
        return String(format: "%02d:%02d", minutes, seconds)
    }

    public var heartbeatAgoFormatted: String {
        guard let time = lastHeartbeatTime else { return "---" }
        let ago = Int(Date().timeIntervalSince(time))
        if ago < 60 { return "\(ago)s" }
        return "\(ago / 60)m \(ago % 60)s"
    }
}
