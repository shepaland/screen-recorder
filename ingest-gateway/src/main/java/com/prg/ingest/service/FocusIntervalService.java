package com.prg.ingest.service;

import com.prg.ingest.dto.request.FocusIntervalItem;
import com.prg.ingest.dto.request.SubmitFocusIntervalsRequest;
import com.prg.ingest.dto.response.FocusIntervalsResponse;
import com.prg.ingest.entity.AppFocusInterval;
import com.prg.ingest.entity.DeviceUserSession;
import com.prg.ingest.exception.AccessDeniedException;
import com.prg.ingest.repository.AppFocusIntervalRepository;
import com.prg.ingest.repository.DeviceUserSessionRepository;
import com.prg.ingest.security.DevicePrincipal;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class FocusIntervalService {

    private final AppFocusIntervalRepository focusRepo;
    private final DeviceUserSessionRepository sessionRepo;
    private final EntityManager entityManager;

    private static final Pattern HTML_TAG_PATTERN = Pattern.compile("<[^>]+>");
    private static final int FLUSH_BATCH_SIZE = 50;

    @Transactional
    public FocusIntervalsResponse submitIntervals(
            SubmitFocusIntervalsRequest request,
            DevicePrincipal principal,
            UUID correlationId) {

        // 1. Validate device_id present in JWT
        if (principal.getDeviceId() == null) {
            throw new AccessDeniedException("device_id is required in JWT", "DEVICE_ID_REQUIRED");
        }

        // 2. Validate device_id matches JWT
        if (!principal.getDeviceId().equals(request.getDeviceId())) {
            throw new AccessDeniedException("device_id mismatch with JWT claims", "DEVICE_MISMATCH");
        }

        // 3. Validate timestamps not in future (5 min tolerance)
        Instant maxAllowed = Instant.now().plusSeconds(300);
        for (FocusIntervalItem item : request.getIntervals()) {
            if (item.getStartedAt().isAfter(maxAllowed)) {
                throw new IllegalArgumentException("started_at cannot be in the future: " + item.getStartedAt());
            }
        }

        // 4. Upsert DeviceUserSession
        upsertUserSession(principal.getTenantId(), request.getDeviceId(), request.getUsername());

        // 5. Deduplicate: find existing IDs
        Set<UUID> requestIds = request.getIntervals().stream()
                .map(FocusIntervalItem::getId)
                .collect(Collectors.toSet());

        Set<UUID> existingIds = requestIds.isEmpty() ? Set.of() : focusRepo.findExistingIds(requestIds);

        int accepted = 0;
        int duplicates = 0;

        for (FocusIntervalItem item : request.getIntervals()) {
            if (existingIds.contains(item.getId())) {
                duplicates++;
                continue;
            }

            AppFocusInterval entity = AppFocusInterval.builder()
                    .id(item.getId())
                    .tenantId(principal.getTenantId())
                    .deviceId(request.getDeviceId())
                    .username(sanitize(request.getUsername()))
                    .sessionId(item.getSessionId())
                    .processName(sanitize(item.getProcessName()))
                    .windowTitle(sanitize(item.getWindowTitle() != null ? item.getWindowTitle() : ""))
                    .isBrowser(Boolean.TRUE.equals(item.getIsBrowser()))
                    .browserName(item.getBrowserName() != null ? sanitize(item.getBrowserName()) : null)
                    .domain(item.getDomain() != null ? sanitize(item.getDomain()) : null)
                    .startedAt(item.getStartedAt())
                    .endedAt(item.getEndedAt())
                    .durationMs(item.getDurationMs())
                    .correlationId(correlationId)
                    .build();

            entityManager.persist(entity);
            accepted++;

            // Flush in batches
            if (accepted % FLUSH_BATCH_SIZE == 0) {
                entityManager.flush();
                entityManager.clear();
            }
        }

        if (accepted % FLUSH_BATCH_SIZE != 0) {
            entityManager.flush();
        }

        log.info("FOCUS_INTERVALS_SUBMITTED: device_id={}, username={}, accepted={}, duplicates={}, tenant_id={}, correlation_id={}",
                request.getDeviceId(), request.getUsername(), accepted, duplicates, principal.getTenantId(), correlationId);

        return FocusIntervalsResponse.builder()
                .accepted(accepted)
                .duplicates(duplicates)
                .correlationId(correlationId)
                .build();
    }

    private void upsertUserSession(UUID tenantId, UUID deviceId, String username) {
        // Parse DOMAIN\\user format
        String windowsDomain = null;
        String parsedUsername = username;
        if (username.contains("\\")) {
            String[] parts = username.split("\\\\", 2);
            windowsDomain = parts[0];
            parsedUsername = parts[1];
        }

        Optional<DeviceUserSession> existing = sessionRepo
                .findByTenantIdAndDeviceIdAndUsernameAndIsActiveTrue(tenantId, deviceId, username);

        if (existing.isPresent()) {
            DeviceUserSession session = existing.get();
            session.setLastSeenTs(Instant.now());
            session.setUpdatedTs(Instant.now());
            if (windowsDomain != null && session.getWindowsDomain() == null) {
                session.setWindowsDomain(windowsDomain);
            }
            sessionRepo.save(session);
        } else {
            DeviceUserSession newSession = DeviceUserSession.builder()
                    .tenantId(tenantId)
                    .deviceId(deviceId)
                    .username(username)
                    .windowsDomain(windowsDomain)
                    .displayName(parsedUsername)
                    .isActive(true)
                    .build();
            sessionRepo.save(newSession);
        }
    }

    /**
     * Strip HTML tags from input to prevent XSS/injection.
     */
    private static String sanitize(String input) {
        if (input == null) return null;
        return HTML_TAG_PATTERN.matcher(input).replaceAll("");
    }
}
