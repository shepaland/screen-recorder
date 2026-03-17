import Foundation
import IOKit
import CryptoKit

public final class HardwareId {
    public static func generate() -> String {
        if let uuid = platformUUID() {
            return uuid
        }
        // Fallback: hash hostname + model
        let host = ProcessInfo.processInfo.hostName
        let model = hardwareModel() ?? "unknown"
        let combined = "\(host)-\(model)"
        let hash = SHA256.hash(data: combined.data(using: .utf8)!)
        return hash.map { String(format: "%02x", $0) }.joined()
    }

    private static func platformUUID() -> String? {
        let service = IOServiceGetMatchingService(kIOMainPortDefault, IOServiceMatching("IOPlatformExpertDevice"))
        guard service != 0 else { return nil }
        defer { IOObjectRelease(service) }
        let key = kIOPlatformUUIDKey as CFString
        guard let uuid = IORegistryEntryCreateCFProperty(service, key, kCFAllocatorDefault, 0)?.takeRetainedValue() as? String else {
            return nil
        }
        return uuid
    }

    private static func hardwareModel() -> String? {
        var size = 0
        sysctlbyname("hw.model", nil, &size, nil, 0)
        var model = [CChar](repeating: 0, count: size)
        sysctlbyname("hw.model", &model, &size, nil, 0)
        return String(cString: model)
    }
}
