import AppKit

public final class StatusWindow {
    private var window: NSPanel!
    private var contentStack: NSStackView!
    private var refreshTimer: Timer?
    private let state = AgentState.shared
    private let permissionChecker = PermissionChecker()

    // Dynamic labels
    private var serverLabel: NSTextField!
    private var deviceLabel: NSTextField!
    private var heartbeatRow: NSStackView!
    private var heartbeatValueLabel: NSTextField!
    private var heartbeatBadge: NSView!
    private var heartbeatAgoLabel: NSTextField!
    private var recStatusRow: NSStackView!
    private var recValueLabel: NSTextField!
    private var recBadge: NSView!
    private var recSessionLabel: NSTextField!
    private var recDurationLabel: NSTextField!
    private var recSegmentsLabel: NSTextField!
    private var recParamsLabel: NSTextField!
    private var permScreenRow: NSStackView!
    private var permScreenBadge: NSView!
    private var permScreenValueLabel: NSTextField!
    private var permAccessRow: NSStackView!
    private var permAccessBadge: NSView!
    private var permAccessValueLabel: NSTextField!
    private var errorSection: NSStackView!
    private var errorLabel: NSTextField!

    public init() {
        setupWindow()
        buildContent()
        refresh()
    }

    // MARK: - Window

    private func setupWindow() {
        let rect = NSRect(x: 0, y: 0, width: 360, height: 440)
        window = NSPanel(
            contentRect: rect,
            styleMask: [.titled, .closable, .fullSizeContentView, .nonactivatingPanel],
            backing: .buffered,
            defer: false
        )
        window.title = "Кадеро"
        window.titlebarAppearsTransparent = true
        window.titleVisibility = .hidden
        window.isMovableByWindowBackground = true
        window.level = .floating
        window.backgroundColor = .clear
        window.isReleasedWhenClosed = false
        window.center()

        let vfx = NSVisualEffectView(frame: rect)
        vfx.material = .hudWindow
        vfx.blendingMode = .behindWindow
        vfx.state = .active
        window.contentView = vfx
    }

    // MARK: - Build

    private func buildContent() {
        guard let container = window.contentView else { return }

        contentStack = NSStackView()
        contentStack.orientation = .vertical
        contentStack.alignment = .leading
        contentStack.spacing = 4
        contentStack.translatesAutoresizingMaskIntoConstraints = false
        contentStack.edgeInsets = NSEdgeInsets(top: 36, left: 20, bottom: 16, right: 20)

        container.addSubview(contentStack)
        NSLayoutConstraint.activate([
            contentStack.topAnchor.constraint(equalTo: container.topAnchor),
            contentStack.leadingAnchor.constraint(equalTo: container.leadingAnchor),
            contentStack.trailingAnchor.constraint(equalTo: container.trailingAnchor),
            contentStack.bottomAnchor.constraint(lessThanOrEqualTo: container.bottomAnchor)
        ])

        // Title
        let title = label("Кадеро Agent", size: 16, weight: .semibold, color: .labelColor)
        let version = label("v\(AgentConfig.agentVersion)", size: 12, weight: .regular, color: .tertiaryLabelColor)
        let titleRow = hstack([title, version], spacing: 6)
        contentStack.addArrangedSubview(titleRow)
        addSpace(16)

        // --- CONNECTION ---
        contentStack.addArrangedSubview(sectionHeader("ПОДКЛЮЧЕНИЕ"))
        addSpace(6)

        serverLabel = label("---", size: 12, weight: .regular, color: .labelColor)
        contentStack.addArrangedSubview(row("Сервер", serverLabel))

        deviceLabel = label("---", size: 11, weight: .regular, color: .labelColor, mono: true)
        deviceLabel.isSelectable = true
        deviceLabel.lineBreakMode = .byCharWrapping
        deviceLabel.maximumNumberOfLines = 2
        deviceLabel.preferredMaxLayoutWidth = 220
        contentStack.addArrangedSubview(row("Device ID", deviceLabel))

        let (hbRow, hbBadge, hbValue) = badgeRow("Heartbeat")
        heartbeatRow = hbRow; heartbeatBadge = hbBadge; heartbeatValueLabel = hbValue
        contentStack.addArrangedSubview(heartbeatRow)

        heartbeatAgoLabel = label("---", size: 12, weight: .regular, color: .secondaryLabelColor)
        contentStack.addArrangedSubview(row("Последний", heartbeatAgoLabel))

        addSpace(12)
        contentStack.addArrangedSubview(separator())
        addSpace(12)

        // --- RECORDING ---
        contentStack.addArrangedSubview(sectionHeader("ЗАПИСЬ"))
        addSpace(6)

        let (rRow, rBadge, rValue) = badgeRow("Статус")
        recStatusRow = rRow; recBadge = rBadge; recValueLabel = rValue
        contentStack.addArrangedSubview(recStatusRow)

        recSessionLabel = label("---", size: 11, weight: .regular, color: .labelColor, mono: true)
        recSessionLabel.isSelectable = true
        contentStack.addArrangedSubview(row("Сессия", recSessionLabel))

        recDurationLabel = label("---", size: 12, weight: .medium, color: .labelColor)
        contentStack.addArrangedSubview(row("Длительность", recDurationLabel))

        recSegmentsLabel = label("---", size: 12, weight: .regular, color: .labelColor)
        contentStack.addArrangedSubview(row("Сегменты", recSegmentsLabel))

        recParamsLabel = label("---", size: 12, weight: .regular, color: .secondaryLabelColor)
        contentStack.addArrangedSubview(row("Параметры", recParamsLabel))

        addSpace(12)
        contentStack.addArrangedSubview(separator())
        addSpace(12)

        // --- PERMISSIONS ---
        contentStack.addArrangedSubview(sectionHeader("РАЗРЕШЕНИЯ"))
        addSpace(6)

        let (psRow, psBadge, psValue) = badgeRow("Screen Recording")
        permScreenRow = psRow; permScreenBadge = psBadge; permScreenValueLabel = psValue
        contentStack.addArrangedSubview(permScreenRow)

        let (paRow, paBadge, paValue) = badgeRow("Accessibility")
        permAccessRow = paRow; permAccessBadge = paBadge; permAccessValueLabel = paValue
        contentStack.addArrangedSubview(permAccessRow)

        addSpace(12)
        contentStack.addArrangedSubview(separator())
        addSpace(8)

        // --- ERRORS ---
        errorSection = NSStackView()
        errorSection.orientation = .vertical
        errorSection.alignment = .leading
        errorSection.spacing = 4
        let errHeader = sectionHeader("ПОСЛЕДНЯЯ ОШИБКА")
        errorSection.addArrangedSubview(errHeader)
        errorLabel = label("Нет", size: 12, weight: .regular, color: .secondaryLabelColor)
        errorLabel.lineBreakMode = .byWordWrapping
        errorLabel.maximumNumberOfLines = 3
        errorLabel.preferredMaxLayoutWidth = 310
        errorSection.addArrangedSubview(errorLabel)
        contentStack.addArrangedSubview(errorSection)

        addSpace(12)

        // --- BUTTONS ---
        let refreshBtn = NSButton(title: "Обновить", target: self, action: #selector(handleRefresh))
        refreshBtn.bezelStyle = .rounded
        refreshBtn.controlSize = .small
        let logsBtn = NSButton(title: "Открыть логи", target: self, action: #selector(handleOpenLogs))
        logsBtn.bezelStyle = .rounded
        logsBtn.controlSize = .small
        contentStack.addArrangedSubview(hstack([refreshBtn, logsBtn], spacing: 10))
    }

    // MARK: - Show / Hide

    public func showWindow() {
        window.makeKeyAndOrderFront(nil)
        startRefreshTimer()
        refresh()
    }

    public func hideWindow() {
        window.orderOut(nil)
        stopRefreshTimer()
    }

    private func startRefreshTimer() {
        stopRefreshTimer()
        refreshTimer = Timer.scheduledTimer(withTimeInterval: 2.0, repeats: true) { [weak self] _ in self?.refresh() }
    }

    private func stopRefreshTimer() { refreshTimer?.invalidate(); refreshTimer = nil }

    @objc private func handleRefresh() { refresh() }
    @objc private func handleOpenLogs() { NSWorkspace.shared.open(URL(fileURLWithPath: Logger.logsPath)) }

    // MARK: - Refresh

    private func refresh() {
        DispatchQueue.main.async { [weak self] in self?.updateLabels() }
    }

    private func updateLabels() {
        let host = state.serverUrl
            .replacingOccurrences(of: "https://", with: "")
            .replacingOccurrences(of: "http://", with: "")
        serverLabel.stringValue = host.isEmpty ? "Не настроен" : host
        deviceLabel.stringValue = state.deviceId.isEmpty ? "---" : state.deviceId
        deviceLabel.allowsExpansionToolTips = true
        deviceLabel.toolTip = state.deviceId

        // Heartbeat — badge style
        if state.heartbeatOK {
            setBadge(heartbeatBadge, on: true)
            heartbeatValueLabel.stringValue = "Подключён"
        } else {
            setBadge(heartbeatBadge, on: false)
            heartbeatValueLabel.stringValue = "Нет связи"
        }
        heartbeatAgoLabel.stringValue = "\(state.heartbeatAgoFormatted) назад"

        // Recording — badge style
        if state.isRecording {
            setBadge(recBadge, recording: true)
            recValueLabel.stringValue = "Идёт запись"
            recDurationLabel.stringValue = state.recordingDurationFormatted
        } else {
            setBadge(recBadge, recording: false)
            recValueLabel.stringValue = "Не активна"
            recDurationLabel.stringValue = "---"
        }
        recSessionLabel.stringValue = state.sessionId ?? "---"
        recSegmentsLabel.stringValue = "\(state.segmentsUploaded) загружено, \(state.segmentsQueued) в очереди"
        recParamsLabel.stringValue = "\(state.captureFps) FPS  ·  \(state.resolution)"

        // Permissions — infer from state, don't call system APIs (they trigger prompts)
        let screenOK = state.isRecording || state.status == "online" || state.status == "recording_disabled"
        setBadge(permScreenBadge, on: screenOK)
        permScreenValueLabel.stringValue = screenOK ? "Разрешено" : (state.status == "waiting_permission" ? "Требуется" : "Не проверено")

        let accessOK = permissionChecker.hasAccessibility  // AXIsProcessTrusted() is safe, no prompt
        setBadge(permAccessBadge, on: accessOK)
        permAccessValueLabel.stringValue = accessOK ? "Разрешено" : "Требуется"

        // Errors
        if let error = state.lastError {
            errorLabel.stringValue = error
            errorLabel.textColor = .labelColor
        } else {
            errorLabel.stringValue = "Нет ошибок"
            errorLabel.textColor = .tertiaryLabelColor
        }
    }

    // MARK: - Badge (inline status dot — 8pt circle)

    private func badgeRow(_ title: String) -> (NSStackView, NSView, NSTextField) {
        let titleLabel = label(title, size: 12, weight: .medium, color: .secondaryLabelColor)
        titleLabel.setContentHuggingPriority(.defaultHigh, for: .horizontal)

        let dot = NSView(frame: NSRect(x: 0, y: 0, width: 8, height: 8))
        dot.wantsLayer = true
        dot.layer?.cornerRadius = 4
        dot.layer?.backgroundColor = NSColor.tertiaryLabelColor.cgColor
        dot.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([
            dot.widthAnchor.constraint(equalToConstant: 8),
            dot.heightAnchor.constraint(equalToConstant: 8)
        ])

        let valueLabel = label("---", size: 12, weight: .regular, color: .labelColor)

        let row = NSStackView(views: [titleLabel, dot, valueLabel])
        row.orientation = .horizontal
        row.spacing = 6
        row.alignment = .centerY
        return (row, dot, valueLabel)
    }

    private func setBadge(_ dot: NSView, on: Bool) {
        dot.layer?.backgroundColor = on
            ? NSColor.labelColor.withAlphaComponent(0.7).cgColor
            : NSColor.tertiaryLabelColor.cgColor
    }

    private func setBadge(_ dot: NSView, recording: Bool) {
        // For recording: use label color but with a subtle pulse-like stronger presence
        dot.layer?.backgroundColor = recording
            ? NSColor.labelColor.cgColor
            : NSColor.tertiaryLabelColor.cgColor
    }

    // MARK: - UI Builders

    private func sectionHeader(_ text: String) -> NSTextField {
        let l = NSTextField(labelWithString: text)
        l.font = NSFont.systemFont(ofSize: 10, weight: .semibold)
        l.textColor = .tertiaryLabelColor
        return l
    }

    private func label(_ text: String, size: CGFloat, weight: NSFont.Weight, color: NSColor, mono: Bool = false) -> NSTextField {
        let l = NSTextField(labelWithString: text)
        l.font = mono ? NSFont.monospacedSystemFont(ofSize: size, weight: weight) : NSFont.systemFont(ofSize: size, weight: weight)
        l.textColor = color
        l.lineBreakMode = .byTruncatingTail
        return l
    }

    private func row(_ title: String, _ valueLabel: NSTextField) -> NSStackView {
        let titleLabel = label(title, size: 12, weight: .medium, color: .secondaryLabelColor)
        titleLabel.setContentHuggingPriority(.defaultHigh, for: .horizontal)
        let r = NSStackView(views: [titleLabel, valueLabel])
        r.orientation = .horizontal
        r.spacing = 8
        r.alignment = .firstBaseline
        return r
    }

    private func hstack(_ views: [NSView], spacing: CGFloat) -> NSStackView {
        let s = NSStackView(views: views)
        s.orientation = .horizontal
        s.spacing = spacing
        s.alignment = .centerY
        return s
    }

    private func separator() -> NSView {
        let s = NSBox(); s.boxType = .separator; return s
    }

    private func addSpace(_ height: CGFloat) {
        let spacer = NSView()
        spacer.translatesAutoresizingMaskIntoConstraints = false
        spacer.heightAnchor.constraint(equalToConstant: height).isActive = true
        contentStack.addArrangedSubview(spacer)
    }
}
