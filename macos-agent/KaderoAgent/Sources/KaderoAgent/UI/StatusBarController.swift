import AppKit

public final class StatusBarController: NSObject, NSMenuDelegate {
    private var statusItem: NSStatusItem!
    private let menu = NSMenu()
    private let state = AgentState.shared

    // Menu items (references for dynamic update)
    private var titleItem: NSMenuItem!
    private var serverItem: NSMenuItem!
    private var heartbeatItem: NSMenuItem!
    private var recordingItem: NSMenuItem!
    private var queueItem: NSMenuItem!
    private var startItem: NSMenuItem!
    private var stopItem: NSMenuItem!
    private var errorSeparator: NSMenuItem!
    private var errorItem: NSMenuItem!

    public var onStartRecording: (() -> Void)?
    public var onStopRecording: (() -> Void)?
    public var onQuit: (() -> Void)?
    public var onShowStatusWindow: (() -> Void)?

    private var statusWindow: StatusWindow?

    public override init() {
        super.init()
        statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
        loadStatusBarIcon()
        buildMenu()
        menu.delegate = self
        statusItem.menu = menu
    }

    // MARK: - Status Bar Icon

    private func loadStatusBarIcon() {
        // Use SF Symbol as template icon — works perfectly as monochrome in menu bar
        if let img = NSImage(systemSymbolName: "record.circle", accessibilityDescription: "Kadero") {
            img.isTemplate = true
            statusItem.button?.image = img
            statusItem.button?.imagePosition = .imageLeft
        } else {
            // Fallback: text "K"
            statusItem.button?.title = " K"
        }
    }

    public func updateStatusIndicator() {
        let indicator: String
        switch state.status {
        case "recording": indicator = " \u{1F534}"  // red circle
        case "error": indicator = " \u{26A0}\u{FE0F}" // warning
        case "recording_disabled": indicator = " \u{23F8}" // pause
        case "waiting_permission": indicator = " \u{1F511}" // key
        case "offline": indicator = " \u{25CB}" // empty circle
        default: indicator = ""
        }
        DispatchQueue.main.async { [weak self] in
            self?.statusItem.button?.title = indicator
        }
    }

    // MARK: - Build Menu

    private func buildMenu() {
        menu.removeAllItems()

        titleItem = NSMenuItem(title: "Кадеро v\(AgentConfig.agentVersion)", action: nil, keyEquivalent: "")
        titleItem.isEnabled = false
        menu.addItem(titleItem)

        menu.addItem(NSMenuItem.separator())

        // -- Info section --
        serverItem = NSMenuItem(title: "Сервер: ---", action: nil, keyEquivalent: "")
        serverItem.isEnabled = false
        menu.addItem(serverItem)

        heartbeatItem = NSMenuItem(title: "Heartbeat: ---", action: nil, keyEquivalent: "")
        heartbeatItem.isEnabled = false
        menu.addItem(heartbeatItem)

        recordingItem = NSMenuItem(title: "Запись: не активна", action: nil, keyEquivalent: "")
        recordingItem.isEnabled = false
        menu.addItem(recordingItem)

        queueItem = NSMenuItem(title: "Очередь: 0 сегментов", action: nil, keyEquivalent: "")
        queueItem.isEnabled = false
        menu.addItem(queueItem)

        menu.addItem(NSMenuItem.separator())

        // -- Actions --
        startItem = NSMenuItem(title: "Начать запись", action: #selector(handleStart), keyEquivalent: "r")
        startItem.target = self
        menu.addItem(startItem)

        stopItem = NSMenuItem(title: "Остановить запись", action: #selector(handleStop), keyEquivalent: "s")
        stopItem.target = self
        stopItem.isEnabled = false
        menu.addItem(stopItem)

        // -- Error section (hidden by default) --
        errorSeparator = NSMenuItem.separator()
        errorSeparator.isHidden = true
        menu.addItem(errorSeparator)

        errorItem = NSMenuItem(title: "", action: nil, keyEquivalent: "")
        errorItem.isEnabled = false
        errorItem.isHidden = true
        menu.addItem(errorItem)

        menu.addItem(NSMenuItem.separator())

        // -- System --
        let windowItem = NSMenuItem(title: "Окно состояния...", action: #selector(handleShowWindow), keyEquivalent: "i")
        windowItem.target = self
        menu.addItem(windowItem)

        let logsItem = NSMenuItem(title: "Открыть логи...", action: #selector(handleOpenLogs), keyEquivalent: "l")
        logsItem.target = self
        menu.addItem(logsItem)

        menu.addItem(NSMenuItem.separator())

        let quitItem = NSMenuItem(title: "Выход", action: #selector(handleQuit), keyEquivalent: "q")
        quitItem.target = self
        menu.addItem(quitItem)
    }

    // MARK: - NSMenuDelegate — refresh on every open

    public func menuWillOpen(_ menu: NSMenu) {
        state.clearStaleError()
        refreshMenuItems()
    }

    private func refreshMenuItems() {
        // Title with colored status
        let statusText: String
        switch state.status {
        case "recording": statusText = "Запись"
        case "error": statusText = "Ошибка"
        case "recording_disabled": statusText = "Отключена"
        case "waiting_permission": statusText = "Ожидание разрешений"
        case "offline": statusText = "Нет связи"
        default: statusText = "Онлайн"
        }
        titleItem.title = "Кадеро — \(statusText)"

        // Server
        if state.heartbeatOK {
            serverItem.title = "  Сервер: подключён"
        } else {
            serverItem.title = "  Сервер: нет связи"
        }

        // Heartbeat
        heartbeatItem.title = "  Heartbeat: \(state.heartbeatAgoFormatted) назад"

        // Recording
        if state.isRecording {
            recordingItem.title = "  Запись: активна (\(state.recordingDurationFormatted))"
        } else {
            recordingItem.title = "  Запись: не активна"
        }

        // Queue
        let q = state.segmentsQueued
        queueItem.title = "  Сегментов в очереди: \(q)"

        // Start/Stop
        startItem.isEnabled = !state.isRecording && state.status != "recording_disabled"
        stopItem.isEnabled = state.isRecording

        // Error
        if let error = state.lastError {
            errorSeparator.isHidden = false
            errorItem.isHidden = false
            let truncated = error.count > 60 ? String(error.prefix(57)) + "..." : error
            errorItem.title = "  \u{26A0} \(truncated)"
        } else {
            errorSeparator.isHidden = true
            errorItem.isHidden = true
        }

        updateStatusIndicator()
    }

    // MARK: - Actions

    @objc private func handleStart() {
        startItem.title = "Запускаю запись..."
        startItem.isEnabled = false
        onStartRecording?()
    }

    @objc private func handleStop() {
        stopItem.title = "Останавливаю..."
        stopItem.isEnabled = false
        onStopRecording?()
    }

    @objc private func handleShowWindow() {
        if statusWindow == nil {
            statusWindow = StatusWindow()
        }
        statusWindow?.showWindow()
    }

    @objc private func handleOpenLogs() {
        NSWorkspace.shared.open(URL(fileURLWithPath: Logger.logsPath))
    }

    @objc private func handleQuit() { onQuit?() }
}
