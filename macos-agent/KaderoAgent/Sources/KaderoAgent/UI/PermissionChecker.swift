import Foundation
import ScreenCaptureKit
import CoreGraphics
import AppKit

public final class PermissionChecker {
    private let log = Logger("PermissionChecker")
    private var cachedScreenCapture: Bool? = nil
    private var lastCheck: Date? = nil

    /// Set to true when SCStream is actively capturing — avoids false negatives
    /// from SCShareableContent which can't be called concurrently with active capture.
    public var isActivelyRecording: Bool = false

    public init() {}

    // MARK: - Screen Recording

    /// Check Screen Recording permission by actually trying to access SCShareableContent.
    /// This is the only reliable method — CGPreflightScreenCaptureAccess and CGWindowList
    /// can give false results depending on bundle path and code signing identity.
    public var hasScreenCapture: Bool {
        // If we are already recording, permission is obviously granted
        if isActivelyRecording { return true }

        // Cache result for 5 seconds to avoid hammering the API
        if let cached = cachedScreenCapture, let last = lastCheck, Date().timeIntervalSince(last) < 5 {
            return cached
        }

        let result = checkScreenCaptureSync()
        cachedScreenCapture = result
        lastCheck = Date()
        log.debug("Screen capture permission: \(result)")
        return result
    }

    /// Invalidate cache (after granting permission + restart)
    public func invalidateCache() {
        cachedScreenCapture = nil
        lastCheck = nil
    }

    private func checkScreenCaptureSync() -> Bool {
        // Run SCShareableContent check on a background thread to avoid deadlocking NSApplication
        var result = false
        let group = DispatchGroup()
        group.enter()

        DispatchQueue.global(qos: .userInitiated).async {
            let sem = DispatchSemaphore(value: 0)
            Task {
                do {
                    let content = try await SCShareableContent.excludingDesktopWindows(false, onScreenWindowsOnly: true)
                    // If we get displays without error, permission is granted
                    result = !content.displays.isEmpty
                } catch {
                    // SCShareableContent throws when permission is denied
                    result = false
                }
                sem.signal()
            }
            _ = sem.wait(timeout: .now() + 5)
            group.leave()
        }

        _ = group.wait(timeout: .now() + 6)
        return result
    }

    public func requestScreenCapture() {
        CGRequestScreenCaptureAccess()
        log.info("Screen capture permission requested")
    }

    // MARK: - Accessibility

    public var hasAccessibility: Bool {
        AXIsProcessTrusted()
    }

    public func requestAccessibility() {
        let options = [kAXTrustedCheckOptionPrompt.takeUnretainedValue(): true] as CFDictionary
        AXIsProcessTrustedWithOptions(options)
        log.info("Accessibility permission requested")
    }

    // MARK: - Combined

    public var allPermissionsGranted: Bool {
        hasScreenCapture && hasAccessibility
    }

    public func requestAllMissing() {
        if !hasScreenCapture { requestScreenCapture() }
        if !hasAccessibility { requestAccessibility() }
    }
}
