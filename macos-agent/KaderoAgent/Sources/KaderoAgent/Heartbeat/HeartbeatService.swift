import Foundation

public final class HeartbeatService {
    private let apiClient: ApiClient
    private let authManager: AuthManager
    private let config: AgentConfig
    private let log = Logger("HeartbeatService")
    private var task: Task<Void, Never>?

    public var onPendingCommands: (([PendingCommand]) -> Void)?
    public var onDeviceSettings: (([String: AnyCodableValue]) -> Void)?
    public var statusProvider: (() -> String) = { "online" }
    public var segmentsQueuedProvider: (() -> Int) = { 0 }

    public init(apiClient: ApiClient, authManager: AuthManager, config: AgentConfig) {
        self.apiClient = apiClient
        self.authManager = authManager
        self.config = config
    }

    public func start() {
        guard task == nil else { return }
        log.info("Starting heartbeat (interval: \(config.heartbeatIntervalSec)s)")
        task = Task { [weak self] in
            await self?.heartbeatLoop()
        }
    }

    public func stop() {
        task?.cancel()
        task = nil
        log.info("Heartbeat stopped")
    }

    private func heartbeatLoop() async {
        while !Task.isCancelled {
            await sendHeartbeat()
            let interval = UInt64(config.heartbeatIntervalSec) * 1_000_000_000
            try? await Task.sleep(nanoseconds: interval)
        }
    }

    private func sendHeartbeat() async {
        guard let deviceId = authManager.deviceId else {
            log.warn("No device ID, skipping heartbeat")
            return
        }

        do {
            try await authManager.ensureValidToken()
        } catch {
            log.error("Token validation failed: \(error.localizedDescription)")
            return
        }

        let baseUrl = config.serverUrl.trimSlash()
        let url = "\(baseUrl)/api/cp/v1/devices/\(deviceId)/heartbeat"

        let metrics = MetricsCollector.collect(segmentsQueued: segmentsQueuedProvider())
        let request = HeartbeatRequest(
            status: statusProvider(),
            sessionLocked: false,
            agentVersion: AgentConfig.agentVersion,
            metrics: metrics
        )

        do {
            let response: HeartbeatResponse = try await apiClient.put(url, body: request)

            if let interval = response.nextHeartbeatSec, interval > 0 {
                config.heartbeatIntervalSec = interval
            }

            if let settings = response.deviceSettings {
                config.applyDeviceSettings(settings)
                onDeviceSettings?(settings)
            }

            if let commands = response.pendingCommands, !commands.isEmpty {
                log.info("Received \(commands.count) pending command(s)")
                onPendingCommands?(commands)
            }

            log.debug("Heartbeat OK (next in \(config.heartbeatIntervalSec)s)")
        } catch {
            log.error("Heartbeat failed: \(error.localizedDescription)")
        }
    }
}
