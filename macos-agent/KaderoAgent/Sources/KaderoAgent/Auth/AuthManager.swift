import Foundation

public struct StoredCredentials: Codable {
    public var serverUrl: String
    public var deviceId: String
    public var accessToken: String
    public var refreshToken: String
    public var expiresAt: Date
}

public final class AuthManager {
    private let apiClient: ApiClient
    private let keychainStore: KeychainStore
    private let config: AgentConfig
    private let log = Logger("AuthManager")

    private let credentialsKey = "device_credentials"
    private var credentials: StoredCredentials?
    private let tokenBufferSeconds: TimeInterval = 120

    public var isAuthenticated: Bool { credentials != nil }
    public var deviceId: String? { credentials?.deviceId }

    public init(apiClient: ApiClient, keychainStore: KeychainStore, config: AgentConfig) {
        self.apiClient = apiClient
        self.keychainStore = keychainStore
        self.config = config

        apiClient.tokenRefreshCallback = { [weak self] in
            try await self?.refreshToken()
        }
    }

    public func initialize() -> Bool {
        guard let creds = keychainStore.loadCodable(StoredCredentials.self, key: credentialsKey) else {
            return false
        }
        credentials = creds
        config.serverUrl = creds.serverUrl
        apiClient.accessToken = creds.accessToken
        apiClient.deviceId = creds.deviceId
        return true
    }

    public func validateToken(_ token: String, serverUrl: String) async throws -> ValidateRegistrationTokenResponse {
        config.serverUrl = serverUrl
        let url = "\(serverUrl.trimSlash())/api/v1/auth/validate-registration-token"
        let req = ValidateRegistrationTokenRequest(registrationToken: token)
        return try await apiClient.postPublic(url, body: req)
    }

    public func register(serverUrl: String, registrationToken: String) async throws {
        config.serverUrl = serverUrl
        let url = "\(serverUrl.trimSlash())/api/v1/auth/device-login"
        let deviceInfo = DeviceInfo(
            hostname: ProcessInfo.processInfo.hostName,
            osVersion: ProcessInfo.processInfo.operatingSystemVersionString,
            agentVersion: AgentConfig.agentVersion,
            hardwareId: HardwareId.generate()
        )
        let req = DeviceLoginRequest(registrationToken: registrationToken, deviceInfo: deviceInfo)
        let resp: DeviceLoginResponse = try await apiClient.postPublic(url, body: req)

        let creds = StoredCredentials(
            serverUrl: serverUrl,
            deviceId: resp.deviceId,
            accessToken: resp.accessToken,
            refreshToken: resp.refreshToken,
            expiresAt: Date().addingTimeInterval(TimeInterval(resp.expiresIn))
        )
        credentials = creds
        apiClient.accessToken = resp.accessToken
        apiClient.deviceId = resp.deviceId
        keychainStore.saveCodable(creds, key: credentialsKey)

        if let sc = resp.serverConfig {
            config.applyServerConfig(sc)
        }
        log.info("Registered device: \(resp.deviceId)")
    }

    public func ensureValidToken() async throws {
        guard let creds = credentials else { throw ApiError.unauthorized }
        if Date().addingTimeInterval(tokenBufferSeconds) >= creds.expiresAt {
            try await refreshToken()
        }
    }

    /// Clear stored credentials (on revoked token, etc.)
    public func clearCredentials() {
        credentials = nil
        apiClient.accessToken = nil
        apiClient.deviceId = nil
        keychainStore.delete(key: credentialsKey)
        log.warn("Credentials cleared")
    }

    private func refreshToken() async throws {
        guard let creds = credentials else { throw ApiError.unauthorized }
        let url = "\(creds.serverUrl.trimSlash())/api/v1/auth/device-refresh"
        let req = DeviceRefreshRequest(refreshToken: creds.refreshToken, deviceId: creds.deviceId)

        do {
            let resp: DeviceRefreshResponse = try await apiClient.postPublic(url, body: req)
            var updated = creds
            updated.accessToken = resp.accessToken
            updated.refreshToken = resp.refreshToken
            updated.expiresAt = Date().addingTimeInterval(TimeInterval(resp.expiresIn))
            credentials = updated
            apiClient.accessToken = resp.accessToken
            keychainStore.saveCodable(updated, key: credentialsKey)
            log.info("Token refreshed")
        } catch let error as ApiError {
            if case .httpError(401, let body) = error, body.contains("REVOKED") {
                log.error("Refresh token revoked, clearing credentials")
                clearCredentials()
            }
            throw error
        }
    }
}
