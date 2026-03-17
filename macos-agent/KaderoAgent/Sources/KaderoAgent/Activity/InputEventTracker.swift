import Foundation
import AppKit

public struct MouseClick {
    public let timestamp: Date; public let x: Int; public let y: Int
    public let button: String; public let clickType: String
    public let processName: String; public let windowTitle: String
}

public struct ScrollEvent {
    public let timestamp: Date; public let deltaY: Double; public let processName: String
}

public struct ClipboardEvent {
    public let timestamp: Date; public let processName: String
}

public final class InputEventTracker {
    private let log = Logger("InputEventTracker")
    private let windowTracker: WindowTracker
    private var mouseMonitor: Any?; private var keyMonitor: Any?
    private var scrollMonitor: Any?; private var clipboardTimer: Timer?
    private var lastPasteboardCount: Int = 0
    private let lock = NSLock()
    private var _mouseClicks: [MouseClick] = []; private var _scrollEvents: [ScrollEvent] = []
    private var _clipboardEvents: [ClipboardEvent] = []; private var _keystrokeCount: Int = 0
    private var _typingBurstDetected = false; private var _burstKeysInWindow: Int = 0
    private var _lastKeystrokeTime: Date?

    public init(windowTracker: WindowTracker) { self.windowTracker = windowTracker }

    public func start() {
        lastPasteboardCount = NSPasteboard.general.changeCount
        mouseMonitor = NSEvent.addGlobalMonitorForEvents(matching: [.leftMouseDown, .rightMouseDown, .otherMouseDown]) { [weak self] e in self?.handleMouse(e) }
        keyMonitor = NSEvent.addGlobalMonitorForEvents(matching: .keyDown) { [weak self] _ in self?.handleKeyDown() }
        scrollMonitor = NSEvent.addGlobalMonitorForEvents(matching: .scrollWheel) { [weak self] e in self?.handleScroll(e) }
        DispatchQueue.main.async { [weak self] in
            self?.clipboardTimer = Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { [weak self] _ in self?.checkClipboard() }
        }
        log.info("Input event tracking started")
    }

    public func stop() {
        if let m = mouseMonitor { NSEvent.removeMonitor(m) }
        if let m = keyMonitor { NSEvent.removeMonitor(m) }
        if let m = scrollMonitor { NSEvent.removeMonitor(m) }
        clipboardTimer?.invalidate()
        mouseMonitor = nil; keyMonitor = nil; scrollMonitor = nil; clipboardTimer = nil
        log.info("Input event tracking stopped")
    }

    public func flush() -> (mouseClicks: [MouseClick], keystrokeCount: Int, hasTypingBurst: Bool, scrollEvents: [ScrollEvent], clipboardEvents: [ClipboardEvent]) {
        lock.lock()
        let r = (_mouseClicks, _keystrokeCount, _typingBurstDetected, _scrollEvents, _clipboardEvents)
        _mouseClicks = []; _keystrokeCount = 0; _typingBurstDetected = false
        _burstKeysInWindow = 0; _scrollEvents = []; _clipboardEvents = []
        lock.unlock()
        return r
    }

    private func handleMouse(_ event: NSEvent) {
        let window = windowTracker.activeWindow()
        let button: String = event.buttonNumber == 0 ? "left" : event.buttonNumber == 1 ? "right" : "other"
        let click = MouseClick(timestamp: Date(), x: Int(event.locationInWindow.x), y: Int(event.locationInWindow.y),
                               button: button, clickType: event.clickCount >= 2 ? "double" : "single",
                               processName: window?.processName ?? "Unknown", windowTitle: window?.windowTitle ?? "")
        lock.lock(); _mouseClicks.append(click); lock.unlock()
    }

    private func handleKeyDown() {
        let now = Date()
        lock.lock()
        _keystrokeCount += 1
        if let last = _lastKeystrokeTime, now.timeIntervalSince(last) < 10 {
            _burstKeysInWindow += 1
            if _burstKeysInWindow >= 30 { _typingBurstDetected = true }
        } else { _burstKeysInWindow = 1 }
        _lastKeystrokeTime = now
        lock.unlock()
    }

    private func handleScroll(_ event: NSEvent) {
        let window = windowTracker.activeWindow()
        let s = ScrollEvent(timestamp: Date(), deltaY: Double(event.scrollingDeltaY), processName: window?.processName ?? "Unknown")
        lock.lock(); _scrollEvents.append(s); lock.unlock()
    }

    private func checkClipboard() {
        let current = NSPasteboard.general.changeCount
        guard current != lastPasteboardCount else { return }
        lastPasteboardCount = current
        let window = windowTracker.activeWindow()
        let e = ClipboardEvent(timestamp: Date(), processName: window?.processName ?? "Unknown")
        lock.lock(); _clipboardEvents.append(e); lock.unlock()
    }
}
