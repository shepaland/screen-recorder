import Foundation
import Security

public final class KeychainStore {
    private let service = "ru.kadero.agent"
    private let log = Logger("KeychainStore")

    public init() {}

    public func save(key: String, data: Data) -> Bool {
        delete(key: key)
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key,
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlock
        ]
        let status = SecItemAdd(query as CFDictionary, nil)
        if status != errSecSuccess {
            log.error("Keychain save failed for \(key): \(status)")
        }
        return status == errSecSuccess
    }

    public func load(key: String) -> Data? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]
        var result: AnyObject?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        guard status == errSecSuccess else { return nil }
        return result as? Data
    }

    @discardableResult
    public func delete(key: String) -> Bool {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key
        ]
        return SecItemDelete(query as CFDictionary) == errSecSuccess
    }

    public func saveCodable<T: Encodable>(_ value: T, key: String) -> Bool {
        guard let data = try? JSONEncoder().encode(value) else { return false }
        return save(key: key, data: data)
    }

    public func loadCodable<T: Decodable>(_ type: T.Type, key: String) -> T? {
        guard let data = load(key: key) else { return nil }
        return try? JSONDecoder().decode(type, from: data)
    }
}
