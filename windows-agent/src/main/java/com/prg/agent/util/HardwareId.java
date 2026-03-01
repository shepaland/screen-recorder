package com.prg.agent.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;

/**
 * Generates a unique hardware fingerprint for the current machine.
 *
 * <p>On Windows, combines motherboard serial, CPU processor ID, and first disk serial
 * using PowerShell WMI queries. On non-Windows platforms (for development/testing),
 * falls back to hostname + username combination.
 *
 * <p>The result is a SHA-256 hash, cached for the lifetime of the process.
 */
public class HardwareId {

    private static final Logger log = LoggerFactory.getLogger(HardwareId.class);
    private static volatile String cachedId;

    private HardwareId() {
    }

    /**
     * Returns the hardware ID, computing it on first call and caching the result.
     */
    public static String getHardwareId() {
        if (cachedId != null) {
            return cachedId;
        }
        synchronized (HardwareId.class) {
            if (cachedId != null) {
                return cachedId;
            }
            cachedId = computeHardwareId();
            log.info("Hardware ID computed: {}...{}", cachedId.substring(0, 8), cachedId.substring(56));
            return cachedId;
        }
    }

    private static String computeHardwareId() {
        String osName = System.getProperty("os.name", "").toLowerCase();

        if (osName.contains("windows")) {
            return computeWindowsHardwareId();
        } else {
            return computeFallbackHardwareId();
        }
    }

    /**
     * Windows: queries WMI for motherboard serial, CPU ID, and disk serial.
     */
    private static String computeWindowsHardwareId() {
        StringBuilder sb = new StringBuilder();

        String mbSerial = runPowerShell(
                "(Get-WmiObject Win32_BaseBoard).SerialNumber");
        sb.append("MB:").append(mbSerial != null ? mbSerial.trim() : "UNKNOWN");

        String cpuId = runPowerShell(
                "(Get-WmiObject Win32_Processor).ProcessorId");
        sb.append("|CPU:").append(cpuId != null ? cpuId.trim() : "UNKNOWN");

        String diskSerial = runPowerShell(
                "(Get-WmiObject Win32_DiskDrive | Select-Object -First 1).SerialNumber");
        sb.append("|DISK:").append(diskSerial != null ? diskSerial.trim() : "UNKNOWN");

        log.debug("Hardware ID components: {}", sb);

        return sha256(sb.toString());
    }

    /**
     * Fallback for non-Windows platforms: hostname + username.
     */
    private static String computeFallbackHardwareId() {
        String hostname = "unknown";
        try {
            hostname = java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            log.warn("Could not get hostname", e);
        }

        String username = System.getProperty("user.name", "unknown");
        String osArch = System.getProperty("os.arch", "unknown");
        String osVersion = System.getProperty("os.version", "unknown");

        String combined = "FALLBACK:" + hostname + "|" + username + "|" + osArch + "|" + osVersion;
        log.debug("Using fallback hardware ID: {}", combined);

        return sha256(combined);
    }

    /**
     * Executes a PowerShell command and returns the trimmed output.
     */
    private static String runPowerShell(String command) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "powershell.exe", "-NoProfile", "-NonInteractive", "-Command", command);
            pb.redirectErrorStream(true);

            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line.trim());
                }
            }

            boolean finished = process.waitFor(10, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.warn("PowerShell command timed out: {}", command);
                return null;
            }

            String result = output.toString().trim();
            return result.isEmpty() ? null : result;
        } catch (Exception e) {
            log.warn("Failed to execute PowerShell command: {}", command, e);
            return null;
        }
    }

    /**
     * Computes SHA-256 hash of the input string and returns it as a hex string.
     */
    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    /**
     * Returns the machine hostname, used for display purposes.
     */
    public static String getHostname() {
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return System.getProperty("user.name", "unknown");
        }
    }
}
