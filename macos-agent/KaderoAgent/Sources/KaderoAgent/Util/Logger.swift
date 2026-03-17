import Foundation

public final class Logger {
    public enum Level: String {
        case debug = "DEBUG"
        case info = "INFO"
        case warn = "WARN"
        case error = "ERROR"
    }

    private let name: String
    private let fileHandle: FileHandle?
    private let queue = DispatchQueue(label: "ru.kadero.logger", qos: .utility)

    public static var logsPath: String = {
        let logsDir = FileManager.default.urls(for: .libraryDirectory, in: .userDomainMask).first!
            .appendingPathComponent("Logs/Kadero")
        try? FileManager.default.createDirectory(atPath: logsDir.path, withIntermediateDirectories: true)
        return logsDir.path
    }()

    public init(_ name: String, file: String = "kadero-agent.log") {
        self.name = name
        let logFile = URL(fileURLWithPath: Self.logsPath).appendingPathComponent(file)
        if !FileManager.default.fileExists(atPath: logFile.path) {
            FileManager.default.createFile(atPath: logFile.path, contents: nil)
        }
        self.fileHandle = FileHandle(forWritingAtPath: logFile.path)
        self.fileHandle?.seekToEndOfFile()
    }

    deinit {
        fileHandle?.closeFile()
    }

    public func debug(_ message: String) { log(.debug, message) }
    public func info(_ message: String) { log(.info, message) }
    public func warn(_ message: String) { log(.warn, message) }
    public func error(_ message: String) { log(.error, message) }

    private func log(_ level: Level, _ message: String) {
        let timestamp = ISO8601DateFormatter().string(from: Date())
        let line = "[\(timestamp)] [\(level.rawValue)] [\(name)] \(message)\n"
        queue.async { [weak self] in
            if let data = line.data(using: .utf8) {
                self?.fileHandle?.write(data)
            }
        }
        #if DEBUG
        print(line, terminator: "")
        #endif
    }
}
