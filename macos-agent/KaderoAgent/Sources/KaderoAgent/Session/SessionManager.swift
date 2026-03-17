import Foundation

public final class SessionManager {
    private let apiClient: ApiClient
    private let config: AgentConfig
    private let log = Logger("SessionManager")

    public private(set) var currentSessionId: String?
    public private(set) var sessionStartTime: Date?
    public private(set) var sequenceNum: Int = 0

    public init(apiClient: ApiClient, config: AgentConfig) {
        self.apiClient = apiClient
        self.config = config
    }

    public func startSession() async throws -> String {
        let baseUrl = config.serverUrl.trimSlash()
        let url = "\(baseUrl)/api/ingest/v1/ingest/sessions"

        let request = CreateSessionRequest(
            deviceId: apiClient.deviceId ?? "",
            metadata: SessionMetadata(resolution: config.resolution, fps: config.captureFps)
        )

        do {
            let response: SessionResponse = try await apiClient.post(url, body: request)
            currentSessionId = response.id
            sessionStartTime = Date()
            sequenceNum = 0
            log.info("Session created: \(response.id)")
            return response.id
        } catch let error as ApiError {
            if case .httpError(409, _) = error {
                log.warn("409 Conflict — closing stale sessions")
                await closeActiveSessions()
                let response: SessionResponse = try await apiClient.post(url, body: request)
                currentSessionId = response.id
                sessionStartTime = Date()
                sequenceNum = 0
                log.info("Session created after 409 recovery: \(response.id)")
                return response.id
            }
            throw error
        }
    }

    public func endSession() async {
        guard let sessionId = currentSessionId else { return }
        let baseUrl = config.serverUrl.trimSlash()
        let url = "\(baseUrl)/api/ingest/v1/ingest/sessions/\(sessionId)/end"

        do {
            let _: EmptyResponse = try await apiClient.put(url)
            log.info("Session ended: \(sessionId)")
        } catch {
            log.error("Failed to end session \(sessionId): \(error.localizedDescription)")
        }
        currentSessionId = nil
        sessionStartTime = nil
    }

    public var needsRotation: Bool {
        guard let startTime = sessionStartTime else { return false }
        let elapsed = Date().timeIntervalSince(startTime)
        let maxSeconds = Double(config.sessionMaxDurationMin) * 60.0
        return elapsed >= maxSeconds
    }

    public func nextSequenceNum() -> Int {
        let num = sequenceNum
        sequenceNum += 1
        return num
    }

    public func invalidateSession() {
        log.warn("Session invalidated")
        currentSessionId = nil
        sessionStartTime = nil
    }

    private func closeActiveSessions() async {
        guard let deviceId = apiClient.deviceId else { return }
        let baseUrl = config.serverUrl.trimSlash()
        let listUrl = "\(baseUrl)/api/ingest/v1/ingest/recordings?device_id=\(deviceId)&status=active&size=10"

        do {
            let page: RecordingsPageResponse = try await apiClient.get(listUrl)
            for session in page.content {
                let endUrl = "\(baseUrl)/api/ingest/v1/ingest/sessions/\(session.id)/end"
                do {
                    let _: EmptyResponse = try await apiClient.put(endUrl)
                    log.info("Closed stale session: \(session.id)")
                } catch {
                    log.warn("Failed to close stale session \(session.id): \(error.localizedDescription)")
                }
            }
        } catch {
            log.error("Failed to list active sessions: \(error.localizedDescription)")
        }
    }
}
