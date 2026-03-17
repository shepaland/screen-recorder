import Foundation

public final class CommandHandler {
    private let apiClient: ApiClient
    private let config: AgentConfig
    private let log = Logger("CommandHandler")

    public var onStartRecording: (() async -> Void)?
    public var onStopRecording: (() async -> Void)?
    public var onSettingsChanged: (() async -> Void)?

    private let maxLogBytes = 500 * 1024

    public init(apiClient: ApiClient, config: AgentConfig) {
        self.apiClient = apiClient
        self.config = config
    }

    public func handle(_ commands: [PendingCommand]) async {
        for cmd in commands { await handleSingle(cmd) }
    }

    private func handleSingle(_ cmd: PendingCommand) async {
        log.info("Handling command: \(cmd.commandType) (id: \(cmd.id))")
        do {
            switch cmd.commandType {
            case "START_RECORDING":
                await onStartRecording?()
                try await ack(commandId: cmd.id, status: "acknowledged")
            case "STOP_RECORDING":
                await onStopRecording?()
                try await ack(commandId: cmd.id, status: "acknowledged")
            case "UPDATE_SETTINGS":
                handleUpdateSettings(cmd)
                await onSettingsChanged?()
                try await ack(commandId: cmd.id, status: "acknowledged")
            case "RESTART_AGENT":
                try await ack(commandId: cmd.id, status: "acknowledged")
                log.info("Restarting agent (exit 0, LaunchAgent will restart)")
                try? await Task.sleep(nanoseconds: 500_000_000)
                exit(0)
            case "UPLOAD_LOGS":
                try await uploadLogs()
                try await ack(commandId: cmd.id, status: "acknowledged")
            default:
                log.warn("Unknown command type: \(cmd.commandType)")
                try await ack(commandId: cmd.id, status: "failed",
                              message: "Unknown command type: \(cmd.commandType)")
            }
        } catch {
            log.error("Command \(cmd.commandType) failed: \(error.localizedDescription)")
            try? await ack(commandId: cmd.id, status: "failed", message: error.localizedDescription)
        }
    }

    private func ack(commandId: String, status: String, message: String? = nil) async throws {
        let baseUrl = config.serverUrl.trimSlash()
        let url = "\(baseUrl)/api/cp/v1/devices/commands/\(commandId)/ack"
        let result = message.map { CommandAckResult(message: $0) }
        let request = CommandAckRequest(status: status, result: result)
        let _: EmptyResponse = try await apiClient.put(url, body: request)
        log.debug("ACK sent for \(commandId): \(status)")
    }

    private func handleUpdateSettings(_ cmd: PendingCommand) {
        guard let payload = cmd.payload else { return }
        if let v = payload["capture_fps"]?.intValue, v > 0 { config.captureFps = v }
        if let v = payload["resolution"]?.stringValue, !v.isEmpty { config.resolution = v }
        if let v = payload["quality"]?.stringValue, !v.isEmpty { config.quality = v }
        if let v = payload["segment_duration_sec"]?.intValue, v > 0 { config.segmentDurationSec = v }
        if let v = payload["auto_start"]?.boolValue { config.autoStart = v }
        if let v = payload["session_max_duration_min"]?.intValue, v > 0 { config.sessionMaxDurationMin = v }
        if let v = payload["recording_enabled"]?.boolValue { config.recordingEnabled = v }
        log.info("Settings updated via command")
    }

    private func uploadLogs() async throws {
        guard let deviceId = apiClient.deviceId else { throw CommandError.noDeviceId }
        let baseUrl = config.serverUrl.trimSlash()
        let url = "\(baseUrl)/api/cp/v1/devices/\(deviceId)/logs"
        let now = ISO8601DateFormatter().string(from: Date())
        let oneHourAgo = ISO8601DateFormatter().string(from: Date().addingTimeInterval(-3600))

        var entries: [DeviceLogEntry] = []
        for (logType, fileName) in [("kadero-agent", "kadero-agent.log"), ("kadero-http", "kadero-http.log"), ("kadero-upload", "kadero-upload.log")] {
            let logPath = URL(fileURLWithPath: Logger.logsPath).appendingPathComponent(fileName)
            if let content = tailFile(at: logPath, maxBytes: maxLogBytes) {
                entries.append(DeviceLogEntry(logType: logType, content: content, fromTs: oneHourAgo, toTs: now))
            }
        }
        guard !entries.isEmpty else { return }
        let _: EmptyResponse = try await apiClient.post(url, body: entries)
        log.info("Uploaded \(entries.count) log file(s)")
    }

    private func tailFile(at url: URL, maxBytes: Int) -> String? {
        guard let handle = FileHandle(forReadingAtPath: url.path) else { return nil }
        defer { handle.closeFile() }
        let fileSize = handle.seekToEndOfFile()
        if fileSize == 0 { return nil }
        let offset = fileSize > UInt64(maxBytes) ? fileSize - UInt64(maxBytes) : 0
        handle.seek(toFileOffset: offset)
        let data = handle.readDataToEndOfFile()
        return String(data: data, encoding: .utf8)
    }
}

public enum CommandError: Error, LocalizedError {
    case noDeviceId
    public var errorDescription: String? {
        switch self { case .noDeviceId: return "No device ID available" }
    }
}
