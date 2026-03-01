package com.prg.agent.service;

import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;

/**
 * Collects system metrics for heartbeat reporting.
 *
 * <p>Gathers CPU usage, memory consumption, and available disk space.
 * Uses JMX management beans for process metrics and standard Java
 * file APIs for disk space.
 */
public class MetricsCollector {

    private static final Logger log = LoggerFactory.getLogger(MetricsCollector.class);

    private final OperatingSystemMXBean osMxBean;

    public MetricsCollector() {
        this.osMxBean = ManagementFactory.getOperatingSystemMXBean();
    }

    /**
     * Collects a snapshot of current system metrics.
     */
    public SystemMetrics collectMetrics() {
        SystemMetrics metrics = new SystemMetrics();

        // CPU usage
        metrics.setCpuPercent(getCpuUsage());

        // Memory
        Runtime runtime = Runtime.getRuntime();
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        long usedMemory = totalMemory - freeMemory;
        metrics.setMemoryMb((int) (usedMemory / (1024 * 1024)));

        // Disk free space
        File root = new File(System.getProperty("user.home"));
        double freeGb = root.getUsableSpace() / (1024.0 * 1024.0 * 1024.0);
        metrics.setDiskFreeGb(Math.round(freeGb * 10.0) / 10.0);

        return metrics;
    }

    /**
     * Returns the current CPU usage as a percentage (0-100).
     */
    private double getCpuUsage() {
        try {
            if (osMxBean instanceof com.sun.management.OperatingSystemMXBean sunOsMxBean) {
                double cpuLoad = sunOsMxBean.getProcessCpuLoad();
                if (cpuLoad >= 0) {
                    return Math.round(cpuLoad * 1000.0) / 10.0; // Round to 1 decimal
                }
            }
        } catch (Exception e) {
            log.debug("Could not get process CPU load: {}", e.getMessage());
        }

        // Fallback: system load average
        double loadAvg = osMxBean.getSystemLoadAverage();
        if (loadAvg >= 0) {
            int processors = osMxBean.getAvailableProcessors();
            return Math.round((loadAvg / processors) * 1000.0) / 10.0;
        }

        return -1;
    }

    /**
     * System metrics snapshot.
     */
    @Data
    public static class SystemMetrics {
        private double cpuPercent;
        private int memoryMb;
        private double diskFreeGb;
    }
}
