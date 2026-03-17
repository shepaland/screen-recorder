import Foundation
import Darwin

public struct HeartbeatMetricsSnapshot {
    public let cpuPercent: Double
    public let memoryMb: Double
    public let diskFreeGb: Double
}

public final class MetricsCollector {
    public static func collect(segmentsQueued: Int) -> HeartbeatMetrics {
        let snap = snapshot()
        return HeartbeatMetrics(
            cpuPercent: snap.cpuPercent,
            memoryMb: snap.memoryMb,
            diskFreeGb: snap.diskFreeGb,
            segmentsQueued: segmentsQueued
        )
    }

    public static func snapshot() -> HeartbeatMetricsSnapshot {
        let cpu = cpuUsage()
        let mem = memoryUsageMb()
        let disk = diskFreeGb()
        return HeartbeatMetricsSnapshot(cpuPercent: cpu, memoryMb: mem, diskFreeGb: disk)
    }

    private static func cpuUsage() -> Double {
        var info = mach_task_basic_info()
        var count = mach_msg_type_number_t(MemoryLayout<mach_task_basic_info>.size) / 4
        let result = withUnsafeMutablePointer(to: &info) {
            $0.withMemoryRebound(to: integer_t.self, capacity: Int(count)) {
                task_info(mach_task_self_, task_flavor_t(MACH_TASK_BASIC_INFO), $0, &count)
            }
        }
        guard result == KERN_SUCCESS else { return 0 }
        return Double(info.resident_size) / 1_000_000
    }

    private static func memoryUsageMb() -> Double {
        var info = mach_task_basic_info()
        var count = mach_msg_type_number_t(MemoryLayout<mach_task_basic_info>.size) / 4
        let result = withUnsafeMutablePointer(to: &info) {
            $0.withMemoryRebound(to: integer_t.self, capacity: Int(count)) {
                task_info(mach_task_self_, task_flavor_t(MACH_TASK_BASIC_INFO), $0, &count)
            }
        }
        guard result == KERN_SUCCESS else { return 0 }
        return Double(info.resident_size) / (1024 * 1024)
    }

    private static func diskFreeGb() -> Double {
        let home = FileManager.default.homeDirectoryForCurrentUser
        guard let attrs = try? FileManager.default.attributesOfFileSystem(forPath: home.path),
              let free = attrs[.systemFreeSize] as? Int64 else { return 0 }
        return Double(free) / (1024 * 1024 * 1024)
    }
}
