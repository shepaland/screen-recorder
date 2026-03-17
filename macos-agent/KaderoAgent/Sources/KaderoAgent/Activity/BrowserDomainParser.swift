import Foundation

public final class BrowserDomainParser {
    private static let browserBundles: [String: String] = [
        "com.google.Chrome": "Chrome", "com.apple.Safari": "Safari",
        "org.mozilla.firefox": "Firefox", "company.thebrowser.Browser": "Arc",
        "com.microsoft.edgemac": "Edge", "com.operasoftware.Opera": "Opera",
        "com.brave.Browser": "Brave", "ru.yandex.desktop.browser": "Yandex",
        "com.google.Chrome.canary": "Chrome Canary", "org.chromium.Chromium": "Chromium",
        "com.vivaldi.Vivaldi": "Vivaldi",
    ]

    public struct BrowserInfo {
        public let isBrowser: Bool; public let browserName: String?; public let domain: String?
    }

    public static func parse(bundleId: String?, windowTitle: String) -> BrowserInfo {
        guard let bundleId = bundleId, let browserName = browserBundles[bundleId] else {
            return BrowserInfo(isBrowser: false, browserName: nil, domain: nil)
        }
        let domain = extractDomain(from: windowTitle, browser: browserName)
        return BrowserInfo(isBrowser: true, browserName: browserName, domain: domain)
    }

    private static func extractDomain(from title: String, browser: String) -> String? {
        for sep in [" — ", " - ", " – "] {
            let parts = title.components(separatedBy: sep)
            if parts.count >= 2 {
                for i in stride(from: parts.count - 1, through: 1, by: -1) {
                    let candidate = parts[i].trimmingCharacters(in: .whitespaces)
                    if candidate == browser || candidate == "Mozilla Firefox" { continue }
                    if looksLikeDomain(candidate) { return candidate.lowercased() }
                }
            }
        }
        return nil
    }

    private static func looksLikeDomain(_ s: String) -> Bool {
        guard s.count >= 3, s.count <= 253, !s.contains(" ") else { return false }
        return s.contains(".") || s == "localhost"
    }
}
