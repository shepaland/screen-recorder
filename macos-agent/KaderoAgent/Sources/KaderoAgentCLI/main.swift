import Foundation
import AppKit
import KaderoAgent

let log = Logger("Main")
log.info("Kadero Agent v\(AgentConfig.agentVersion) starting")

let config = AgentConfig()
config.ensureDirectories()

let apiClient = ApiClient()
let keychainStore = KeychainStore()
let authManager = AuthManager(apiClient: apiClient, keychainStore: keychainStore, config: config)
let sessionManager = SessionManager(apiClient: apiClient, config: config)
let screenRecorder = ScreenRecorder(config: config)
let offlineDbPath = URL(fileURLWithPath: config.dataPath).appendingPathComponent("offline.db").path
let offlineStore = OfflineStore(path: offlineDbPath)
let segmentUploader = SegmentUploader(apiClient: apiClient, config: config)
let uploadQueue = UploadQueue(uploader: segmentUploader, offlineStore: offlineStore)
let commandHandler = CommandHandler(apiClient: apiClient, config: config)
let activitySink = ActivitySink(apiClient: apiClient, config: config, offlineStore: offlineStore)
let agentState = AgentState.shared
activitySink.sessionIdProvider = { sessionManager.currentSessionId }

var statusBar: StatusBarController?

// MARK: - CLI helpers

func cliArg(_ name: String) -> String? {
    let args = CommandLine.arguments
    guard let idx = args.firstIndex(of: name), idx + 1 < args.count else { return nil }
    return args[idx + 1]
}

func showInputDialog(title: String, message: String, defaultValue: String = "") -> String? {
    let script = """
    tell application "System Events"
        display dialog "\(message)" default answer "\(defaultValue)" with title "\(title)" buttons {"Отмена", "OK"} default button "OK"
        if button returned of result is "OK" then
            return text returned of result
        end if
    end tell
    """
    let proc = Process()
    proc.executableURL = URL(fileURLWithPath: "/usr/bin/osascript")
    proc.arguments = ["-e", script]
    let pipe = Pipe()
    proc.standardOutput = pipe; proc.standardError = FileHandle.nullDevice
    do {
        try proc.run(); proc.waitUntilExit()
        guard proc.terminationStatus == 0 else { return nil }
        let data = pipe.fileHandleForReading.readDataToEndOfFile()
        let result = String(data: data, encoding: .utf8)?.trimmingCharacters(in: .whitespacesAndNewlines)
        return (result?.isEmpty == false) ? result : nil
    } catch { return nil }
}

func showAlert(title: String, message: String) {
    let script = "display dialog \"\(message)\" with title \"\(title)\" buttons {\"OK\"} default button \"OK\""
    let proc = Process()
    proc.executableURL = URL(fileURLWithPath: "/usr/bin/osascript")
    proc.arguments = ["-e", script]
    proc.standardOutput = FileHandle.nullDevice; proc.standardError = FileHandle.nullDevice
    try? proc.run(); proc.waitUntilExit()
}

func installLaunchAgent() {
    let launchAgentsDir = FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Library/LaunchAgents")
    try? FileManager.default.createDirectory(at: launchAgentsDir, withIntermediateDirectories: true)
    let plistPath = launchAgentsDir.appendingPathComponent("ru.kadero.agent.plist")

    // Don't overwrite if already exists — avoids triggering macOS auto-load
    if FileManager.default.fileExists(atPath: plistPath.path) {
        log.info("LaunchAgent already exists, skipping")
        return
    }

    let appPath = Bundle.main.executablePath ?? "/Applications/KaderoAgent.app/Contents/MacOS/KaderoAgent"
    let plist = """
    <?xml version="1.0" encoding="UTF-8"?>
    <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
    <plist version="1.0">
    <dict>
        <key>Label</key><string>ru.kadero.agent</string>
        <key>ProgramArguments</key><array><string>\(appPath)</string><string>--record</string></array>
        <key>RunAtLoad</key><true/>
        <key>KeepAlive</key><dict><key>SuccessfulExit</key><false/></dict>
        <key>ThrottleInterval</key><integer>30</integer>
        <key>StandardOutPath</key><string>/tmp/kadero-stdout.log</string>
        <key>StandardErrorPath</key><string>/tmp/kadero-stderr.log</string>
        <key>ProcessType</key><string>Background</string>
    </dict>
    </plist>
    """
    do {
        try plist.write(to: plistPath, atomically: true, encoding: .utf8)
        log.info("LaunchAgent plist saved (auto-start on next login): \(plistPath.path)")
    } catch { log.error("Failed to install LaunchAgent: \(error.localizedDescription)") }
}

// MARK: - Setup

var pendingServerUrl: String? = cliArg("--server-url")
var pendingToken: String? = cliArg("--token")

if !authManager.initialize() {
    if pendingServerUrl == nil || pendingToken == nil {
        if isatty(STDIN_FILENO) != 0 {
            if pendingServerUrl == nil {
                print("Server URL (e.g. https://services-test.shepaland.ru/screenrecorder):")
                if let url = readLine()?.trimmingCharacters(in: .whitespacesAndNewlines), !url.isEmpty { pendingServerUrl = url }
            }
            if pendingToken == nil {
                print("Registration Token (e.g. drt_...):")
                if let token = readLine()?.trimmingCharacters(in: .whitespacesAndNewlines), !token.isEmpty { pendingToken = token }
            }
        } else {
            log.info("No terminal detected, showing GUI setup dialogs")
            pendingServerUrl = pendingServerUrl ?? showInputDialog(title: "Кадеро — Настройка", message: "Введите адрес сервера:", defaultValue: "https://services-test.shepaland.ru/screenrecorder")
            if pendingServerUrl == nil { log.error("Setup cancelled"); exit(1) }
            pendingToken = pendingToken ?? showInputDialog(title: "Кадеро — Настройка", message: "Введите регистрационный токен (drt_...):")
            if pendingToken == nil { log.error("Setup cancelled"); exit(1) }
        }
    }
}

// MARK: - Recording control

segmentUploader.onSessionInvalidated = { sessionManager.invalidateSession() }

final class RecordingDelegate: NSObject, ScreenRecorderDelegate {
    let uploadQueue: UploadQueue; let config: AgentConfig; let sessionManager: SessionManager
    init(uploadQueue: UploadQueue, config: AgentConfig, sessionManager: SessionManager) {
        self.uploadQueue = uploadQueue; self.config = config; self.sessionManager = sessionManager
    }
    func screenRecorder(_ recorder: ScreenRecorder, didProduceSegment url: URL, sequenceNum: Int, durationMs: Int) {
        guard let sessionId = sessionManager.currentSessionId else { return }
        uploadQueue.enqueue(SegmentUploadItem(fileURL: url, sessionId: sessionId, sequenceNum: sequenceNum, durationMs: durationMs, metadata: SessionMetadata(resolution: config.resolution, fps: config.captureFps)))
        AgentState.shared.segmentsQueued = uploadQueue.pendingCount
    }
    func screenRecorder(_ recorder: ScreenRecorder, didFailWithError error: Error) {
        log.error("Recording error: \(error.localizedDescription)")
        AgentState.shared.status = "error"
        AgentState.shared.setError(error.localizedDescription)
    }
}

let recordingDelegate = RecordingDelegate(uploadQueue: uploadQueue, config: config, sessionManager: sessionManager)
screenRecorder.delegate = recordingDelegate

func startRecording() async {
    guard !screenRecorder.isRecording else { return }
    do {
        try await authManager.ensureValidToken()
        let sessionId = try await sessionManager.startSession()
        try await screenRecorder.startCapture(sessionId: sessionId)
        activitySink.recordAuditEvent("SESSION_START")
        // recording started successfully
        agentState.status = "recording"
        agentState.isRecording = true
        agentState.sessionId = sessionId
        agentState.clearError()
        log.info("Recording started (session: \(sessionId))")
    } catch {
        let desc = error.localizedDescription
        let raw = "\(error)"
        log.error("Failed to start recording: '\(desc)' | raw: \(raw)")

        let isPermission = desc.isEmpty || desc.contains("отклонил") || desc.contains("denied")
            || desc.contains("not permitted") || raw.contains("notAuthorized") || raw.contains("userDeclined")
        if isPermission {
            agentState.status = "waiting_permission"
            agentState.setError("Нет разрешения Screen Recording. Добавьте приложение в System Settings → Privacy & Security → Screen Recording, затем нажмите «Начать запись» или перезапустите.")
        } else {
            agentState.status = "error"
            agentState.setError(desc.isEmpty ? "Ошибка записи: \(raw.prefix(100))" : desc)
        }
    }
}

func stopRecording() async {
    guard screenRecorder.isRecording else { return }
    activitySink.recordAuditEvent("SESSION_END")
    _ = await screenRecorder.stopCapture()
    log.info("Draining upload queue (\(uploadQueue.pendingCount) segments)...")
    await uploadQueue.drain()
    await sessionManager.endSession()
    // recording stopped
    agentState.status = "online"
    agentState.isRecording = false
    agentState.sessionId = nil
    log.info("Recording stopped")
}

func checkSessionRotation() async {
    guard screenRecorder.isRecording, sessionManager.needsRotation else { return }
    log.info("Session max duration reached, rotating...")
    _ = await screenRecorder.stopCapture(); await sessionManager.endSession()
    do {
        let newSessionId = try await sessionManager.startSession()
        try await screenRecorder.startCapture(sessionId: newSessionId, startSequence: 0)
        agentState.sessionId = newSessionId
        log.info("Session rotated -> \(newSessionId)")
    } catch {
        log.error("Session rotation failed: \(error.localizedDescription)")
        agentState.status = "error"
        agentState.setError("Ротация сессии: \(error.localizedDescription)")
    }
}

// MARK: - Command handler

commandHandler.onStartRecording = { await startRecording() }
commandHandler.onStopRecording = { await stopRecording() }
commandHandler.onSettingsChanged = {
    if screenRecorder.isRecording {
        _ = await screenRecorder.stopCapture(); await uploadQueue.drain()
        await sessionManager.endSession(); await startRecording()
    }
    if !config.recordingEnabled && screenRecorder.isRecording {
        await stopRecording(); agentState.status = "recording_disabled"
    }
    if config.autoStart && config.recordingEnabled && !screenRecorder.isRecording && agentState.status != "recording_disabled" {
        await startRecording()
    }
}

// MARK: - Main run

func run() async {
    if authManager.isAuthenticated {
        log.info("Restored session for device \(authManager.deviceId ?? "?")")
        agentState.serverUrl = config.serverUrl
        agentState.deviceId = authManager.deviceId ?? ""
    } else if let serverUrl = pendingServerUrl, let token = pendingToken {
        log.info("Registering device...")
        do {
            try await authManager.register(serverUrl: serverUrl, registrationToken: token)
            log.info("Registration successful!")
            agentState.serverUrl = serverUrl
            agentState.deviceId = authManager.deviceId ?? ""
            if isatty(STDIN_FILENO) == 0 {
                showAlert(title: "Кадеро", message: "Устройство зарегистрировано!\\n\\nДайте разрешения в:\\nSystem Settings -> Privacy & Security ->\\n  Screen Recording\\n  Accessibility\\n\\nДля автозапуска при входе —\\nоткройте install.sh на DMG-диске.")
            }
        } catch {
            log.error("Registration failed: \(error.localizedDescription)")
            agentState.setError("Регистрация: \(error.localizedDescription)")
            if isatty(STDIN_FILENO) == 0 { showAlert(title: "Кадеро — Ошибка", message: "Регистрация не удалась: \(error.localizedDescription)") }
            exit(1)
        }
    } else {
        log.error("No credentials and no input provided")
        exit(1)
    }

    // Note: don't auto-request permissions with prompts — they annoy users
    // on every rebuild (new CDHash = macOS sees as new app).
    // Permissions will be requested naturally when features need them.

    // Start services (always, regardless of permissions — recording will fail gracefully)
    let heartbeatService = HeartbeatService(apiClient: apiClient, authManager: authManager, config: config)
    heartbeatService.statusProvider = { agentState.status }
    heartbeatService.segmentsQueuedProvider = { uploadQueue.pendingCount }
    heartbeatService.onPendingCommands = { commands in Task { await commandHandler.handle(commands) } }
    heartbeatService.onDeviceSettings = { _ in
        let wasEnabled = config.recordingEnabled
        if !config.recordingEnabled && screenRecorder.isRecording {
            Task { await stopRecording() }; agentState.status = "recording_disabled"
        } else if config.recordingEnabled && !wasEnabled {
            agentState.status = "online"
            if config.autoStart { Task { await startRecording() } }
        }
        if config.autoStart && config.recordingEnabled && !screenRecorder.isRecording && agentState.status == "online" {
            Task { await startRecording() }
        }
        // Update state
        agentState.captureFps = config.captureFps
        agentState.resolution = config.resolution
        agentState.segmentsQueued = uploadQueue.pendingCount
        agentState.lastHeartbeatTime = Date()
    }

    // Track heartbeat success in state
    let origHeartbeatProvider = heartbeatService.statusProvider
    heartbeatService.statusProvider = {
        agentState.lastHeartbeatTime = Date()
        agentState.segmentsQueued = uploadQueue.pendingCount
        return origHeartbeatProvider()
    }

    uploadQueue.restoreFromOffline()
    heartbeatService.start()
    activitySink.start()

    agentState.captureFps = config.captureFps
    agentState.resolution = config.resolution
    log.info("Agent running. Device ID: \(authManager.deviceId ?? "?"). All services active.")

    let forceRecord = CommandLine.arguments.contains("--record")
    if forceRecord || (config.autoStart && config.recordingEnabled && config.configReceivedFromServer) {
        await startRecording()
    }

    while !Task.isCancelled {
        try? await Task.sleep(nanoseconds: 30_000_000_000)
        await checkSessionRotation()
        agentState.segmentsQueued = uploadQueue.pendingCount
    }
}

// MARK: - AppDelegate

class AppDelegate: NSObject, NSApplicationDelegate {
    func applicationDidFinishLaunching(_ notification: Notification) {
        statusBar = StatusBarController()
        statusBar?.onStartRecording = { Task { await startRecording() } }
        statusBar?.onStopRecording = { Task { await stopRecording() } }
        statusBar?.onQuit = {
            log.info("Quit from menu")
            Task { await stopRecording(); activitySink.stop(); uploadQueue.stop(); exit(1) }
        }

        signal(SIGINT, SIG_IGN)
        let sigintSource = DispatchSource.makeSignalSource(signal: SIGINT, queue: .main)
        sigintSource.setEventHandler {
            log.info("SIGINT"); Task { await stopRecording(); activitySink.stop(); uploadQueue.stop(); exit(0) }
        }
        sigintSource.resume()

        Task { await run() }
    }
}

// MARK: - Launch

let app = NSApplication.shared
app.setActivationPolicy(.accessory)
let delegate = AppDelegate()
app.delegate = delegate
app.run()
