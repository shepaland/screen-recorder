package com.prg.controlplane.service;

import com.prg.controlplane.entity.Device;
import com.prg.controlplane.entity.DeviceStatusLog;
import com.prg.controlplane.repository.DeviceRepository;
import com.prg.controlplane.repository.DeviceStatusLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class DeviceTimeoutService {

    private final DeviceRepository deviceRepository;
    private final DeviceStatusLogRepository deviceStatusLogRepository;

    @Value("${prg.device-timeout.stale-threshold-sec:90}")
    private int staleThresholdSec;

    /**
     * Periodically check for devices that haven't sent a heartbeat within the threshold.
     * Marks them as "offline" and logs the transition.
     */
    @Scheduled(fixedDelayString = "${prg.device-timeout.check-interval-ms:60000}")
    @Transactional
    public void checkStaleDevices() {
        Instant threshold = Instant.now().minusSeconds(staleThresholdSec);

        List<Device> staleDevices = deviceRepository.findStaleDevices(threshold);

        for (Device device : staleDevices) {
            String oldStatus = device.getStatus();
            device.setStatus("offline");
            deviceRepository.save(device);

            try {
                long silentSec = device.getLastHeartbeatTs() != null
                        ? Duration.between(device.getLastHeartbeatTs(), Instant.now()).getSeconds() : 0;
                String details = String.format(
                        "{\"reason\":\"heartbeat_timeout\",\"threshold_sec\":%d,\"silent_sec\":%d}",
                        staleThresholdSec, silentSec);
                deviceStatusLogRepository.insertLog(
                        device.getTenantId(), device.getId(),
                        oldStatus, "offline", "system", details);
            } catch (Exception e) {
                log.warn("Failed to log status transition for device {}: {}",
                        device.getId(), e.getMessage());
            }

            log.info("Device {} ({}) marked offline: no heartbeat for {}s (threshold={}s)",
                    device.getId(), device.getHostname(),
                    device.getLastHeartbeatTs() != null
                            ? Duration.between(device.getLastHeartbeatTs(), Instant.now()).getSeconds()
                            : "unknown",
                    staleThresholdSec);
        }

        if (!staleDevices.isEmpty()) {
            log.info("Stale device check: {} devices marked offline", staleDevices.size());
        }
    }
}
