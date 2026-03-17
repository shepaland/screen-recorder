import Foundation
import ScreenCaptureKit
import CoreMedia
import AVFoundation

public protocol ScreenRecorderDelegate: AnyObject {
    func screenRecorder(_ recorder: ScreenRecorder, didProduceSegment url: URL, sequenceNum: Int, durationMs: Int)
    func screenRecorder(_ recorder: ScreenRecorder, didFailWithError error: Error)
}

public final class ScreenRecorder: NSObject {
    private let config: AgentConfig
    private let log = Logger("ScreenRecorder")
    private var stream: SCStream?
    private var segmentWriter: SegmentWriter?
    private let captureQueue = DispatchQueue(label: "ru.kadero.capture", qos: .userInitiated)

    public weak var delegate: ScreenRecorderDelegate?
    public private(set) var isRecording = false

    private var segmentTimer: Timer?
    private var currentSequenceNum: Int = 0
    private var segmentsDir: URL?
    private var sessionId: String?
    private var lastFrameTime: CMTime = .zero

    public init(config: AgentConfig) {
        self.config = config
        super.init()
    }

    public func startCapture(sessionId: String, startSequence: Int = 0) async throws {
        guard !isRecording else {
            log.warn("Already recording")
            return
        }

        self.sessionId = sessionId
        self.currentSequenceNum = startSequence

        let segDir = URL(fileURLWithPath: config.dataPath)
            .appendingPathComponent("segments")
            .appendingPathComponent(sessionId)
        try FileManager.default.createDirectory(at: segDir, withIntermediateDirectories: true)
        segmentsDir = segDir

        let (width, height) = parseResolution(config.resolution)

        let content = try await SCShareableContent.excludingDesktopWindows(false, onScreenWindowsOnly: true)
        guard let display = content.displays.first else {
            throw RecordingError.noDisplay
        }

        let streamConfig = SCStreamConfiguration()
        streamConfig.width = width
        streamConfig.height = height
        streamConfig.minimumFrameInterval = CMTime(value: 1, timescale: CMTimeScale(config.captureFps))
        streamConfig.pixelFormat = kCVPixelFormatType_32BGRA
        streamConfig.showsCursor = true
        streamConfig.queueDepth = 5

        let filter = SCContentFilter(display: display, excludingWindows: [])
        stream = SCStream(filter: filter, configuration: streamConfig, delegate: self)
        try stream!.addStreamOutput(self, type: .screen, sampleHandlerQueue: captureQueue)

        segmentWriter = SegmentWriter(width: width, height: height, fps: config.captureFps)
        let segmentURL = segDir.appendingPathComponent(segmentFileName(currentSequenceNum))
        try segmentWriter!.startSegment(at: segmentURL)

        try await stream!.startCapture()
        isRecording = true
        log.info("Capture started (display: \(display.width)x\(display.height) → \(width)x\(height) @ \(config.captureFps) fps)")

        startSegmentTimer()
    }

    public func stopCapture() async -> URL? {
        guard isRecording else { return nil }
        isRecording = false

        stopSegmentTimer()

        if let stream = stream {
            do {
                try await stream.stopCapture()
            } catch {
                log.error("Stop capture error: \(error.localizedDescription)")
            }
        }
        stream = nil

        let lastURL = await segmentWriter?.finishSegment()
        if let url = lastURL {
            let durationMs = segmentWriter?.estimatedDurationMs ?? 0
            log.info("Last segment finalized: \(url.lastPathComponent)")
            delegate?.screenRecorder(self, didProduceSegment: url, sequenceNum: currentSequenceNum, durationMs: durationMs)
        }

        segmentWriter = nil
        log.info("Capture stopped")
        return lastURL
    }

    private func startSegmentTimer() {
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            self.segmentTimer = Timer.scheduledTimer(
                withTimeInterval: TimeInterval(self.config.segmentDurationSec),
                repeats: true
            ) { [weak self] _ in
                guard let self = self, self.isRecording else { return }
                Task { await self.rotateSegment() }
            }
        }
    }

    private func stopSegmentTimer() {
        segmentTimer?.invalidate()
        segmentTimer = nil
    }

    private func rotateSegment() async {
        guard isRecording, let segDir = segmentsDir else { return }

        let finishedSequence = currentSequenceNum
        let durationMs = segmentWriter?.estimatedDurationMs ?? (config.segmentDurationSec * 1000)

        let finishedURL = await segmentWriter?.finishSegment()

        currentSequenceNum += 1
        let (width, height) = parseResolution(config.resolution)
        segmentWriter = SegmentWriter(width: width, height: height, fps: config.captureFps)
        let newURL = segDir.appendingPathComponent(segmentFileName(currentSequenceNum))
        do {
            try segmentWriter!.startSegment(at: newURL)
        } catch {
            log.error("Failed to start new segment: \(error.localizedDescription)")
            delegate?.screenRecorder(self, didFailWithError: error)
            return
        }

        if let url = finishedURL {
            let fileSize = (try? FileManager.default.attributesOfItem(atPath: url.path)[.size] as? Int) ?? 0
            if fileSize > 0 {
                log.info("Segment \(finishedSequence) ready (\(fileSize) bytes)")
                delegate?.screenRecorder(self, didProduceSegment: url, sequenceNum: finishedSequence, durationMs: durationMs)
            } else {
                log.warn("Skipping 0-byte segment \(finishedSequence)")
                try? FileManager.default.removeItem(at: url)
            }
        }
    }

    private func segmentFileName(_ seq: Int) -> String {
        String(format: "segment_%05d.mp4", seq)
    }

    private func parseResolution(_ resolution: String) -> (Int, Int) {
        let parts = resolution.lowercased().split(separator: "x")
        guard parts.count == 2, let w = Int(parts[0]), let h = Int(parts[1]) else {
            return (1280, 720)
        }
        return (w, h)
    }
}

extension ScreenRecorder: SCStreamDelegate {
    public func stream(_ stream: SCStream, didStopWithError error: Error) {
        log.error("Stream stopped with error: \(error.localizedDescription)")
        isRecording = false
        delegate?.screenRecorder(self, didFailWithError: error)
    }
}

extension ScreenRecorder: SCStreamOutput {
    public func stream(_ stream: SCStream, didOutputSampleBuffer sampleBuffer: CMSampleBuffer, of type: SCStreamOutputType) {
        guard type == .screen, isRecording else { return }
        guard let pixelBuffer = sampleBuffer.imageBuffer else { return }

        let presentationTime = CMSampleBufferGetPresentationTimeStamp(sampleBuffer)
        guard presentationTime.isValid && presentationTime.flags.contains(.valid) else { return }

        lastFrameTime = presentationTime
        _ = segmentWriter?.appendPixelBuffer(pixelBuffer, presentationTime: presentationTime)
    }
}

public enum RecordingError: Error, LocalizedError {
    case noDisplay
    case capturePermissionDenied
    case writerFailed(String)

    public var errorDescription: String? {
        switch self {
        case .noDisplay: return "No display found for capture"
        case .capturePermissionDenied: return "Screen capture permission denied"
        case .writerFailed(let msg): return "Writer failed: \(msg)"
        }
    }
}
