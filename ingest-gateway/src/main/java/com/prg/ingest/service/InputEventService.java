package com.prg.ingest.service;

import com.prg.ingest.dto.request.*;
import com.prg.ingest.dto.response.InputEventResponse;
import com.prg.ingest.dto.response.InputEventsResponse;
import com.prg.ingest.entity.Device;
import com.prg.ingest.entity.UserInputEvent;
import com.prg.ingest.repository.DeviceRepository;
import com.prg.ingest.repository.DeviceUserSessionRepository;
import com.prg.ingest.repository.UserInputEventRepository;
import com.prg.ingest.security.DevicePrincipal;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class InputEventService {

    private final UserInputEventRepository inputEventRepo;
    private final DeviceRepository deviceRepo;
    private final DeviceUserSessionRepository sessionRepo;
    private final EntityManager entityManager;

    private static final Pattern HTML_TAG_PATTERN = Pattern.compile("<[^>]+>");
    private static final int FLUSH_BATCH_SIZE = 50;

    @Transactional
    public InputEventsResponse saveInputEvents(
            SubmitInputEventsRequest request,
            DevicePrincipal principal,
            UUID correlationId) {

        UUID tenantId = principal.getTenantId();
        UUID deviceId = request.getDeviceId();
        String username = sanitize(request.getUsername());

        // Upsert user session (same as FocusIntervalService)
        upsertUserSession(tenantId, deviceId, username);

        // Collect all IDs for dedup
        Set<UUID> allIds = new HashSet<>();
        if (request.getMouseClicks() != null) {
            request.getMouseClicks().forEach(e -> allIds.add(e.getId()));
        }
        if (request.getKeyboardMetrics() != null) {
            request.getKeyboardMetrics().forEach(e -> allIds.add(e.getId()));
        }
        if (request.getScrollEvents() != null) {
            request.getScrollEvents().forEach(e -> allIds.add(e.getId()));
        }
        if (request.getClipboardEvents() != null) {
            request.getClipboardEvents().forEach(e -> allIds.add(e.getId()));
        }

        Set<UUID> existingIds = allIds.isEmpty() ? Set.of() : inputEventRepo.findExistingIds(allIds);

        int accepted = 0;
        int duplicates = 0;

        // Process mouse clicks
        if (request.getMouseClicks() != null) {
            for (MouseClickEvent click : request.getMouseClicks()) {
                if (existingIds.contains(click.getId())) {
                    duplicates++;
                    continue;
                }
                UserInputEvent entity = UserInputEvent.builder()
                        .id(click.getId())
                        .tenantId(tenantId)
                        .deviceId(deviceId)
                        .username(username)
                        .sessionId(click.getSessionId())
                        .eventType("mouse_click")
                        .eventTs(click.getTimestamp())
                        .clickX(click.getX())
                        .clickY(click.getY())
                        .clickButton(click.getButton())
                        .clickType(click.getClickType())
                        .uiElementType(sanitize(click.getUiElementType()))
                        .uiElementName(sanitize(click.getUiElementName()))
                        .uiElementClass(sanitize(click.getUiElementClass()))
                        .uiAutomationId(sanitize(click.getUiAutomationId()))
                        .processName(sanitize(click.getProcessName()))
                        .windowTitle(sanitize(click.getWindowTitle()))
                        .segmentId(click.getSegmentId())
                        .segmentOffsetMs(click.getSegmentOffsetMs())
                        .correlationId(correlationId)
                        .build();
                entityManager.persist(entity);
                accepted++;
                flushIfNeeded(accepted);
            }
        }

        // Process keyboard metrics
        if (request.getKeyboardMetrics() != null) {
            for (KeyboardMetricEvent metric : request.getKeyboardMetrics()) {
                if (existingIds.contains(metric.getId())) {
                    duplicates++;
                    continue;
                }
                UserInputEvent entity = UserInputEvent.builder()
                        .id(metric.getId())
                        .tenantId(tenantId)
                        .deviceId(deviceId)
                        .username(username)
                        .sessionId(metric.getSessionId())
                        .eventType("keyboard_metric")
                        .eventTs(metric.getIntervalStart())
                        .eventEndTs(metric.getIntervalEnd())
                        .keystrokeCount(metric.getKeystrokeCount())
                        .hasTypingBurst(metric.getHasTypingBurst())
                        .processName(sanitize(metric.getProcessName()))
                        .windowTitle(sanitize(metric.getWindowTitle()))
                        .segmentId(metric.getSegmentId())
                        .segmentOffsetMs(metric.getSegmentOffsetMs())
                        .correlationId(correlationId)
                        .build();
                entityManager.persist(entity);
                accepted++;
                flushIfNeeded(accepted);
            }
        }

        // Process scroll events
        if (request.getScrollEvents() != null) {
            for (ScrollEvent scroll : request.getScrollEvents()) {
                if (existingIds.contains(scroll.getId())) {
                    duplicates++;
                    continue;
                }
                UserInputEvent entity = UserInputEvent.builder()
                        .id(scroll.getId())
                        .tenantId(tenantId)
                        .deviceId(deviceId)
                        .username(username)
                        .sessionId(scroll.getSessionId())
                        .eventType("scroll")
                        .eventTs(scroll.getIntervalStart())
                        .eventEndTs(scroll.getIntervalEnd())
                        .scrollDirection(scroll.getDirection())
                        .scrollTotalDelta(scroll.getTotalDelta())
                        .scrollEventCount(scroll.getEventCount())
                        .processName(sanitize(scroll.getProcessName()))
                        .segmentId(scroll.getSegmentId())
                        .segmentOffsetMs(scroll.getSegmentOffsetMs())
                        .correlationId(correlationId)
                        .build();
                entityManager.persist(entity);
                accepted++;
                flushIfNeeded(accepted);
            }
        }

        // Process clipboard events
        if (request.getClipboardEvents() != null) {
            for (ClipboardEvent clip : request.getClipboardEvents()) {
                if (existingIds.contains(clip.getId())) {
                    duplicates++;
                    continue;
                }
                UserInputEvent entity = UserInputEvent.builder()
                        .id(clip.getId())
                        .tenantId(tenantId)
                        .deviceId(deviceId)
                        .username(username)
                        .sessionId(clip.getSessionId())
                        .eventType("clipboard")
                        .eventTs(clip.getTimestamp())
                        .clipboardAction(clip.getAction())
                        .clipboardContentType(clip.getContentType())
                        .clipboardContentLength(clip.getContentLength())
                        .processName(sanitize(clip.getSourceProcess()))
                        .segmentId(clip.getSegmentId())
                        .segmentOffsetMs(clip.getSegmentOffsetMs())
                        .correlationId(correlationId)
                        .build();
                entityManager.persist(entity);
                accepted++;
                flushIfNeeded(accepted);
            }
        }

        if (accepted % FLUSH_BATCH_SIZE != 0) {
            entityManager.flush();
        }

        log.info("INPUT_EVENTS_SUBMITTED: device_id={}, accepted={}, duplicates={}, tenant_id={}, correlation_id={}",
                deviceId, accepted, duplicates, tenantId, correlationId);

        return InputEventsResponse.builder()
                .accepted(accepted)
                .duplicates(duplicates)
                .correlationId(correlationId)
                .build();
    }

    @Transactional(readOnly = true)
    public Page<InputEventResponse> queryInputEvents(
            UUID tenantId,
            Instant from,
            Instant to,
            List<String> eventTypes,
            String username,
            UUID deviceId,
            String search,
            int page,
            int size,
            String sortBy,
            String sortDir) {

        // Build device hostname cache for response enrichment
        Sort sort = Sort.by(
                "desc".equalsIgnoreCase(sortDir) ? Sort.Direction.DESC : Sort.Direction.ASC,
                mapSortField(sortBy));
        Pageable pageable = PageRequest.of(page, Math.min(size, 100), sort);

        String eventTypesStr = (eventTypes != null && !eventTypes.isEmpty())
                ? String.join(",", eventTypes) : null;

        Page<UserInputEvent> events = inputEventRepo.findByFilters(
                tenantId, from, to,
                eventTypesStr,
                username,
                deviceId,
                search != null && !search.isBlank() ? search.trim() : null,
                pageable);

        // Collect device IDs for hostname lookup
        Set<UUID> deviceIds = events.getContent().stream()
                .map(UserInputEvent::getDeviceId)
                .collect(Collectors.toSet());
        Map<UUID, String> hostnames = new HashMap<>();
        if (!deviceIds.isEmpty()) {
            deviceRepo.findAllById(deviceIds).forEach(d -> hostnames.put(d.getId(), d.getHostname()));
        }

        return events.map(e -> InputEventResponse.builder()
                .id(e.getId())
                .eventTs(e.getEventTs())
                .eventType(e.getEventType())
                .username(e.getUsername())
                .deviceId(e.getDeviceId())
                .deviceHostname(hostnames.getOrDefault(e.getDeviceId(), "unknown"))
                .sessionId(e.getSessionId())
                .segmentId(e.getSegmentId())
                .segmentOffsetMs(e.getSegmentOffsetMs())
                .clickX(e.getClickX())
                .clickY(e.getClickY())
                .clickButton(e.getClickButton())
                .clickType(e.getClickType())
                .uiElementType(e.getUiElementType())
                .uiElementName(e.getUiElementName())
                .keystrokeCount(e.getKeystrokeCount())
                .hasTypingBurst(e.getHasTypingBurst())
                .scrollDirection(e.getScrollDirection())
                .scrollTotalDelta(e.getScrollTotalDelta())
                .scrollEventCount(e.getScrollEventCount())
                .clipboardAction(e.getClipboardAction())
                .clipboardContentType(e.getClipboardContentType())
                .clipboardContentLength(e.getClipboardContentLength())
                .processName(e.getProcessName())
                .windowTitle(e.getWindowTitle())
                .build());
    }

    private void flushIfNeeded(int count) {
        if (count % FLUSH_BATCH_SIZE == 0) {
            entityManager.flush();
            entityManager.clear();
        }
    }

    private String mapSortField(String sortBy) {
        // Native query uses SQL column names, not JPA field names
        return switch (sortBy) {
            case "event_ts", "time" -> "event_ts";
            case "username", "employee" -> "username";
            case "event_type", "type" -> "event_type";
            case "device_id" -> "device_id";
            default -> "event_ts";
        };
    }

    private void upsertUserSession(UUID tenantId, UUID deviceId, String username) {
        var existing = sessionRepo.findByTenantIdAndDeviceIdAndUsernameAndIsActiveTrue(tenantId, deviceId, username);
        if (existing.isPresent()) {
            var session = existing.get();
            session.setLastSeenTs(Instant.now());
            session.setUpdatedTs(Instant.now());
            sessionRepo.save(session);
        } else {
            String windowsDomain = null;
            String parsedUsername = username;
            if (username.contains("\\")) {
                String[] parts = username.split("\\\\", 2);
                windowsDomain = parts[0];
                parsedUsername = parts[1];
            }
            var newSession = com.prg.ingest.entity.DeviceUserSession.builder()
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

    private static String sanitize(String input) {
        if (input == null) return null;
        return HTML_TAG_PATTERN.matcher(input).replaceAll("");
    }
}
