import Foundation
import AVFoundation
import CoreMedia
import CoreVideo

public final class SegmentWriter {
    private var writer: AVAssetWriter?
    private var videoInput: AVAssetWriterInput?
    private var adaptor: AVAssetWriterInputPixelBufferAdaptor?
    private let log = Logger("SegmentWriter")

    public private(set) var segmentURL: URL?
    public private(set) var isWriting = false
    private var startTime: CMTime?
    private var frameCount: Int = 0

    private let width: Int
    private let height: Int
    private let fps: Int

    public init(width: Int, height: Int, fps: Int) {
        self.width = width
        self.height = height
        self.fps = fps
    }

    public func startSegment(at url: URL) throws {
        segmentURL = url
        try? FileManager.default.removeItem(at: url)

        writer = try AVAssetWriter(outputURL: url, fileType: .mp4)

        let videoSettings: [String: Any] = [
            AVVideoCodecKey: AVVideoCodecType.h264,
            AVVideoWidthKey: width,
            AVVideoHeightKey: height,
            AVVideoCompressionPropertiesKey: [
                AVVideoAverageBitRateKey: bitRate,
                AVVideoProfileLevelKey: AVVideoProfileLevelH264BaselineAutoLevel,
                AVVideoMaxKeyFrameIntervalKey: max(fps * 2, 2)
            ]
        ]

        let input = AVAssetWriterInput(mediaType: .video, outputSettings: videoSettings)
        input.expectsMediaDataInRealTime = true

        let sourceAttrs: [String: Any] = [
            kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
            kCVPixelBufferWidthKey as String: width,
            kCVPixelBufferHeightKey as String: height
        ]
        adaptor = AVAssetWriterInputPixelBufferAdaptor(
            assetWriterInput: input,
            sourcePixelBufferAttributes: sourceAttrs
        )

        writer!.add(input)
        videoInput = input
        startTime = nil
        frameCount = 0
        isWriting = true

        log.debug("Segment writer started: \(url.lastPathComponent)")
    }

    public func appendPixelBuffer(_ pixelBuffer: CVPixelBuffer, presentationTime: CMTime) -> Bool {
        guard isWriting, let writer = writer, let input = videoInput, let adaptor = adaptor else {
            return false
        }

        if writer.status == .unknown {
            writer.startWriting()
            writer.startSession(atSourceTime: presentationTime)
            startTime = presentationTime
        }

        guard writer.status == .writing else {
            if writer.status == .failed {
                log.error("Writer failed: \(writer.error?.localizedDescription ?? "unknown")")
            }
            return false
        }

        guard input.isReadyForMoreMediaData else { return false }

        let success = adaptor.append(pixelBuffer, withPresentationTime: presentationTime)
        if success { frameCount += 1 }
        return success
    }

    public func finishSegment() async -> URL? {
        guard isWriting, let writer = writer, let input = videoInput else { return nil }
        isWriting = false

        input.markAsFinished()

        guard writer.status == .writing else {
            log.warn("Writer not in writing state: \(writer.status.rawValue)")
            return segmentURL
        }

        await withCheckedContinuation { continuation in
            writer.finishWriting { continuation.resume() }
        }

        if writer.status == .completed {
            log.info("Segment finalized: \(segmentURL?.lastPathComponent ?? "?") (\(frameCount) frames)")
            let url = segmentURL
            self.writer = nil
            self.videoInput = nil
            self.adaptor = nil
            return url
        } else {
            log.error("Segment finalize failed: \(writer.error?.localizedDescription ?? "unknown")")
            return nil
        }
    }

    public var estimatedDurationMs: Int {
        guard fps > 0 else { return 0 }
        return (frameCount * 1000) / fps
    }

    private var bitRate: Int {
        let pixels = width * height
        if pixels <= 921600 { return 500_000 }
        if pixels <= 2073600 { return 1_000_000 }
        return 2_000_000
    }
}
