import Foundation
import AppKit
import CoreGraphics

public struct WindowInfo {
    public let processName: String
    public let bundleId: String?
    public let windowTitle: String
    public let pid: pid_t
    public let x: Int; public let y: Int; public let width: Int; public let height: Int
    public let isFullscreen: Bool
    public let monitorIndex: Int
}

public final class WindowTracker {
    private let log = Logger("WindowTracker")
    public init() {}

    public func activeWindow() -> WindowInfo? {
        guard let app = NSWorkspace.shared.frontmostApplication else { return nil }
        let processName = app.localizedName ?? "Unknown"
        let bundleId = app.bundleIdentifier
        let pid = app.processIdentifier
        let windowTitle = getWindowTitle(pid: pid) ?? processName
        let (x, y, w, h) = getWindowGeometry(pid: pid)
        let isFullscreen = checkFullscreen(pid: pid)
        let monitorIndex = getMonitorIndex(x: x, y: y)
        return WindowInfo(processName: processName, bundleId: bundleId, windowTitle: windowTitle,
                          pid: pid, x: x, y: y, width: w, height: h,
                          isFullscreen: isFullscreen, monitorIndex: monitorIndex)
    }

    private func getWindowTitle(pid: pid_t) -> String? {
        let appElement = AXUIElementCreateApplication(pid)
        var focusedWindow: AnyObject?
        guard AXUIElementCopyAttributeValue(appElement, kAXFocusedWindowAttribute as CFString, &focusedWindow) == .success else { return nil }
        var title: AnyObject?
        AXUIElementCopyAttributeValue(focusedWindow as! AXUIElement, kAXTitleAttribute as CFString, &title)
        return title as? String
    }

    private func checkFullscreen(pid: pid_t) -> Bool {
        let appElement = AXUIElementCreateApplication(pid)
        var focusedWindow: AnyObject?
        guard AXUIElementCopyAttributeValue(appElement, kAXFocusedWindowAttribute as CFString, &focusedWindow) == .success else { return false }
        var fullscreen: AnyObject?
        AXUIElementCopyAttributeValue(focusedWindow as! AXUIElement, "AXFullScreen" as CFString, &fullscreen)
        return (fullscreen as? Bool) ?? false
    }

    private func getWindowGeometry(pid: pid_t) -> (Int, Int, Int, Int) {
        guard let windowList = CGWindowListCopyWindowInfo([.optionOnScreenOnly, .excludeDesktopElements], kCGNullWindowID) as? [[String: Any]] else { return (0,0,0,0) }
        for window in windowList {
            guard let ownerPID = window[kCGWindowOwnerPID as String] as? pid_t, ownerPID == pid,
                  let bounds = window[kCGWindowBounds as String] as? [String: Any],
                  let x = bounds["X"] as? CGFloat, let y = bounds["Y"] as? CGFloat,
                  let w = bounds["Width"] as? CGFloat, let h = bounds["Height"] as? CGFloat else { continue }
            if w < 100 || h < 100 { continue }
            return (Int(x), Int(y), Int(w), Int(h))
        }
        return (0,0,0,0)
    }

    private func getMonitorIndex(x: Int, y: Int) -> Int {
        let point = NSPoint(x: x + 50, y: y + 50)
        for (i, screen) in NSScreen.screens.enumerated() {
            if screen.frame.contains(point) { return i }
        }
        return 0
    }
}
