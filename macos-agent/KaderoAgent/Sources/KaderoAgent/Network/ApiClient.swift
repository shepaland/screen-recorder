import Foundation

public final class ApiClient {
    private let session: URLSession
    private let log = Logger("ApiClient", file: "kadero-http.log")

    private let encoder: JSONEncoder = {
        let e = JSONEncoder()
        e.keyEncodingStrategy = .convertToSnakeCase
        return e
    }()

    private let decoder: JSONDecoder = {
        let d = JSONDecoder()
        d.keyDecodingStrategy = .convertFromSnakeCase
        return d
    }()

    public var accessToken: String?
    public var deviceId: String?
    public var tokenRefreshCallback: (() async throws -> Void)?

    private let maxRetries = 3

    public init() {
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 30
        config.timeoutIntervalForResource = 60
        self.session = URLSession(configuration: config)
    }

    // MARK: - Public API (authenticated)

    public func post<T: Encodable, R: Decodable>(_ url: String, body: T) async throws -> R {
        try await request("POST", url: url, body: body, authenticated: true)
    }

    public func put<T: Encodable, R: Decodable>(_ url: String, body: T) async throws -> R {
        try await request("PUT", url: url, body: body, authenticated: true)
    }

    public func put<R: Decodable>(_ url: String) async throws -> R {
        try await request("PUT", url: url, body: Optional<String>.none, authenticated: true)
    }

    public func get<R: Decodable>(_ url: String) async throws -> R {
        try await request("GET", url: url, body: Optional<String>.none, authenticated: true)
    }

    // MARK: - Public API (no auth, for login/refresh)

    public func postPublic<T: Encodable, R: Decodable>(_ url: String, body: T) async throws -> R {
        try await request("POST", url: url, body: body, authenticated: false)
    }

    // MARK: - Binary upload (for S3 presigned URL, no auth)

    public func putBinary(url: String, data: Data, contentType: String) async throws {
        guard let requestUrl = URL(string: url) else {
            throw ApiError.invalidUrl(url)
        }
        var req = URLRequest(url: requestUrl)
        req.httpMethod = "PUT"
        req.setValue(contentType, forHTTPHeaderField: "Content-Type")
        req.httpBody = data

        log.debug("PUT binary \(url) (\(data.count) bytes)")

        let (_, response) = try await session.data(for: req)
        guard let httpResponse = response as? HTTPURLResponse else {
            throw ApiError.invalidResponse
        }
        guard (200...299).contains(httpResponse.statusCode) else {
            log.error("S3 upload failed: \(httpResponse.statusCode)")
            throw ApiError.httpError(httpResponse.statusCode, "S3 upload failed")
        }
        log.debug("S3 upload OK")
    }

    // MARK: - Private

    private func request<T: Encodable, R: Decodable>(
        _ method: String,
        url: String,
        body: T?,
        authenticated: Bool
    ) async throws -> R {
        guard let requestUrl = URL(string: url) else {
            throw ApiError.invalidUrl(url)
        }

        var tokenRefreshed = false

        for attempt in 0..<maxRetries {
            var req = URLRequest(url: requestUrl)
            req.httpMethod = method
            req.setValue("application/json", forHTTPHeaderField: "Content-Type")

            if authenticated {
                if let token = accessToken {
                    req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
                }
                if let devId = deviceId {
                    req.setValue(devId, forHTTPHeaderField: "X-Device-ID")
                }
            }

            if let body = body {
                req.httpBody = try encoder.encode(body)
            }

            log.debug("\(method) \(url) (attempt \(attempt + 1))")

            let data: Data
            let response: URLResponse
            do {
                (data, response) = try await session.data(for: req)
            } catch {
                log.error("Network error: \(error.localizedDescription)")
                if attempt < maxRetries - 1 {
                    let delay = pow(2.0, Double(attempt))
                    try await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000))
                    continue
                }
                throw error
            }

            guard let httpResponse = response as? HTTPURLResponse else {
                throw ApiError.invalidResponse
            }

            let statusCode = httpResponse.statusCode

            if statusCode == 401 && authenticated && !tokenRefreshed {
                log.warn("401 Unauthorized, attempting token refresh")
                if let refresh = tokenRefreshCallback {
                    do {
                        try await refresh()
                        tokenRefreshed = true
                        continue
                    } catch {
                        log.error("Token refresh failed: \(error.localizedDescription)")
                        throw ApiError.unauthorized
                    }
                }
                throw ApiError.unauthorized
            }

            if (statusCode == 503 || statusCode == 504) && attempt < maxRetries - 1 {
                let delay = pow(2.0, Double(attempt))
                log.warn("\(statusCode), retrying in \(delay)s")
                try await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000))
                continue
            }

            guard (200...299).contains(statusCode) else {
                let errorBody = String(data: data, encoding: .utf8) ?? ""
                log.error("\(method) \(url) → \(statusCode): \(errorBody)")
                throw ApiError.httpError(statusCode, errorBody)
            }

            if data.isEmpty || statusCode == 204 {
                if R.self == EmptyResponse.self {
                    return EmptyResponse() as! R
                }
                let empty = "{}".data(using: .utf8)!
                return try decoder.decode(R.self, from: empty)
            }

            log.debug("\(method) \(url) → \(statusCode) (\(data.count) bytes)")
            return try decoder.decode(R.self, from: data)
        }

        throw ApiError.maxRetriesExceeded
    }
}

// MARK: - Supporting Types

public struct EmptyResponse: Decodable {
    public init() {}
}

public enum ApiError: Error, LocalizedError {
    case invalidUrl(String)
    case invalidResponse
    case unauthorized
    case httpError(Int, String)
    case maxRetriesExceeded

    public var errorDescription: String? {
        switch self {
        case .invalidUrl(let url): return "Invalid URL: \(url)"
        case .invalidResponse: return "Invalid response from server"
        case .unauthorized: return "Unauthorized (401)"
        case .httpError(let code, let body): return "HTTP \(code): \(body)"
        case .maxRetriesExceeded: return "Max retries exceeded"
        }
    }
}

extension String {
    public func trimSlash() -> String {
        var s = self
        while s.hasSuffix("/") { s = String(s.dropLast()) }
        return s
    }
}
