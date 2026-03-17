import XCTest
@testable import KaderoAgent

final class KaderoAgentTests: XCTestCase {
    func testHardwareIdGeneration() {
        let id = HardwareId.generate()
        XCTAssertFalse(id.isEmpty)
        XCTAssertTrue(id.count >= 8)
    }

    func testMetricsCollection() {
        let snap = MetricsCollector.snapshot()
        XCTAssertTrue(snap.memoryMb > 0)
        XCTAssertTrue(snap.diskFreeGb > 0)
    }

    func testAgentConfigApplyDeviceSettings() {
        let config = AgentConfig()
        config.applyDeviceSettings([
            "capture_fps": .int(5),
            "resolution": .string("1920x1080"),
            "quality": .string("high"),
            "recording_enabled": .bool(false)
        ])
        XCTAssertEqual(config.captureFps, 5)
        XCTAssertEqual(config.resolution, "1920x1080")
        XCTAssertEqual(config.quality, "high")
        XCTAssertFalse(config.recordingEnabled)
    }

    func testServerConfigApply() {
        let config = AgentConfig()
        config.applyServerConfig(ServerConfig(
            heartbeatIntervalSec: 15,
            segmentDurationSec: 30,
            captureFps: 2,
            autoStart: true
        ))
        XCTAssertEqual(config.heartbeatIntervalSec, 15)
        XCTAssertEqual(config.segmentDurationSec, 30)
        XCTAssertEqual(config.captureFps, 2)
        XCTAssertTrue(config.autoStart)
    }

    func testTrimSlash() {
        XCTAssertEqual("https://example.com/".trimSlash(), "https://example.com")
        XCTAssertEqual("https://example.com".trimSlash(), "https://example.com")
        XCTAssertEqual("https://example.com///".trimSlash(), "https://example.com")
    }

    func testAnyCodableValue() {
        let json = """
        {"str":"hello","num":42,"flag":true,"nil_val":null}
        """.data(using: .utf8)!
        let decoded = try! JSONDecoder().decode([String: AnyCodableValue].self, from: json)
        XCTAssertEqual(decoded["str"]?.stringValue, "hello")
        XCTAssertEqual(decoded["num"]?.intValue, 42)
        XCTAssertEqual(decoded["flag"]?.boolValue, true)
    }
}
