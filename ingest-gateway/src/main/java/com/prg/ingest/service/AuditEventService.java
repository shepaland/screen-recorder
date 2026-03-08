package com.prg.ingest.service;

import com.prg.ingest.dto.request.AuditEventItem;
import com.prg.ingest.dto.request.SubmitAuditEventsRequest;
import com.prg.ingest.dto.response.AuditEventResponse;
import com.prg.ingest.dto.response.DeviceAuditEventsResponse;
import com.prg.ingest.dto.response.SubmitAuditEventsResponse;
import com.prg.ingest.entity.Device;
import com.prg.ingest.entity.DeviceAuditEvent;
import com.prg.ingest.entity.DeviceUserSession;
import com.prg.ingest.exception.AccessDeniedException;
import com.prg.ingest.exception.ResourceNotFoundException;
import com.prg.ingest.repository.DeviceAuditEventRepository;
import com.prg.ingest.repository.DeviceRepository;
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
public class AuditEventService {

    private static final Pattern HTML_TAG_PATTERN = Pattern.compile("<[^>]+>");

    private final DeviceAuditEventRepository auditEventRepository;
    private final DeviceRepository deviceRepository;
    private final DeviceUserSessionRepository userSessionRepository;
    private final EntityManager entityManager;

    private static String sanitize(String input) {
        if (input == null) return null;
        return HTML_TAG_PATTERN.matcher(input).replaceAll("");
    }

    @Transactional
    public SubmitAuditEventsResponse submitEvents(
            SubmitAuditEventsRequest request,
            DevicePrincipal principal,
            UUID correlationId) {

        // Validate device_id present in JWT (mandatory for ingestion endpoints)
        if (principal.getDeviceId() == null) {
            throw new AccessDeniedException("device_id is required in JWT", "DEVICE_ID_REQUIRED");
        }

        // Validate device_id matches JWT
        if (!principal.getDeviceId().equals(request.getDeviceId())) {
            throw new AccessDeniedException("device_id mismatch with JWT claims", "DEVICE_MISMATCH");
        }

        // Validate event timestamps not in future (5 min tolerance)
        Instant maxAllowed = Instant.now().plusSeconds(300);
        for (AuditEventItem item : request.getEvents()) {
            if (item.getEventTs().isAfter(maxAllowed)) {
                throw new IllegalArgumentException("event_ts cannot be in the future: " + item.getEventTs());
            }
        }

        // Deduplicate: find existing IDs
        Set<UUID> requestIds = request.getEvents().stream()
                .map(AuditEventItem::getId)
                .collect(Collectors.toSet());

        Set<UUID> existingIds = requestIds.isEmpty() ? Set.of() : auditEventRepository.findExistingIds(requestIds);

        int accepted = 0;
        int duplicates = 0;

        for (AuditEventItem item : request.getEvents()) {
            if (existingIds.contains(item.getId())) {
                duplicates++;
                continue;
            }

            DeviceAuditEvent entity = DeviceAuditEvent.builder()
                    .id(item.getId())
                    .tenantId(principal.getTenantId())
                    .deviceId(request.getDeviceId())
                    .sessionId(item.getSessionId())
                    .eventType(item.getEventType())
                    .eventTs(item.getEventTs())
                    .details(item.getDetails() != null ? item.getDetails() : Map.of())
                    .correlationId(correlationId)
                    .username(sanitize(request.getUsername()))
                    .build();

            entityManager.persist(entity);
            accepted++;

            // Flush in batches of 50
            if (accepted % 50 == 0) {
                entityManager.flush();
                entityManager.clear();
            }
        }

        if (accepted % 50 != 0) {
            entityManager.flush();
        }

        // Upsert DeviceUserSession if username is provided
        if (request.getUsername() != null && !request.getUsername().isBlank()) {
            upsertUserSession(principal.getTenantId(), request.getDeviceId(), request.getUsername());
        }

        log.info("AUDIT_EVENTS_SUBMITTED: device_id={}, accepted={}, duplicates={}, tenant_id={}, correlation_id={}",
                request.getDeviceId(), accepted, duplicates, principal.getTenantId(), correlationId);

        return SubmitAuditEventsResponse.builder()
                .accepted(accepted)
                .duplicates(duplicates)
                .correlationId(correlationId)
                .build();
    }

    @Transactional(readOnly = true)
    public DeviceAuditEventsResponse getEvents(
            UUID deviceId, String date, String eventTypeFilter,
            int page, int size, DevicePrincipal principal) {

        // Find device, get timezone
        Device device = deviceRepository.findByIdAndTenantId(deviceId, principal.getTenantId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Device not found: " + deviceId, "DEVICE_NOT_FOUND"));

        String timezone = device.getTimezone() != null ? device.getTimezone() : "Europe/Moscow";

        // Build query
        StringBuilder sql = new StringBuilder("""
                SELECT id, event_type, event_ts, session_id, details
                FROM device_audit_events
                WHERE device_id = :deviceId
                  AND tenant_id = :tenantId
                  AND event_ts >= (CAST(:date AS date) AT TIME ZONE :tz)
                  AND event_ts < ((CAST(:date AS date) + INTERVAL '1 day') AT TIME ZONE :tz)
                """);

        StringBuilder countSql = new StringBuilder("""
                SELECT COUNT(*)
                FROM device_audit_events
                WHERE device_id = :deviceId
                  AND tenant_id = :tenantId
                  AND event_ts >= (CAST(:date AS date) AT TIME ZONE :tz)
                  AND event_ts < ((CAST(:date AS date) + INTERVAL '1 day') AT TIME ZONE :tz)
                """);

        if (eventTypeFilter != null && !eventTypeFilter.isBlank()) {
            String filterClause = " AND event_type IN (:eventTypes)";
            sql.append(filterClause);
            countSql.append(filterClause);
        }

        sql.append(" ORDER BY event_ts ASC LIMIT :lim OFFSET :off");

        var query = entityManager.createNativeQuery(sql.toString())
                .setParameter("deviceId", deviceId)
                .setParameter("tenantId", principal.getTenantId())
                .setParameter("date", date)
                .setParameter("tz", timezone)
                .setParameter("lim", size)
                .setParameter("off", page * size);

        var countQuery = entityManager.createNativeQuery(countSql.toString())
                .setParameter("deviceId", deviceId)
                .setParameter("tenantId", principal.getTenantId())
                .setParameter("date", date)
                .setParameter("tz", timezone);

        if (eventTypeFilter != null && !eventTypeFilter.isBlank()) {
            List<String> types = List.of(eventTypeFilter.split(","));
            query.setParameter("eventTypes", types);
            countQuery.setParameter("eventTypes", types);
        }

        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        long totalElements = ((Number) countQuery.getSingleResult()).longValue();

        List<AuditEventResponse> events = rows.stream()
                .map(row -> AuditEventResponse.builder()
                        .id((UUID) row[0])
                        .eventType((String) row[1])
                        .eventTs(toInstant(row[2]))
                        .sessionId(row[3] != null ? (UUID) row[3] : null)
                        .details(toMap(row[4]))
                        .build())
                .toList();

        return DeviceAuditEventsResponse.builder()
                .deviceId(deviceId)
                .date(date)
                .timezone(timezone)
                .totalElements(totalElements)
                .events(events)
                .build();
    }

    private static Instant toInstant(Object value) {
        if (value == null) return null;
        if (value instanceof Instant inst) return inst;
        if (value instanceof java.sql.Timestamp ts) return ts.toInstant();
        if (value instanceof java.time.OffsetDateTime odt) return odt.toInstant();
        return Instant.parse(value.toString());
    }

    private void upsertUserSession(UUID tenantId, UUID deviceId, String username) {
        Optional<DeviceUserSession> existing = userSessionRepository
                .findByTenantIdAndDeviceIdAndUsernameAndIsActiveTrue(tenantId, deviceId, username);

        if (existing.isPresent()) {
            DeviceUserSession session = existing.get();
            session.setLastSeenTs(Instant.now());
            session.setUpdatedTs(Instant.now());
            userSessionRepository.save(session);
        } else {
            String windowsDomain = null;
            String displayName = username;
            if (username.contains("\\")) {
                String[] parts = username.split("\\\\", 2);
                windowsDomain = parts[0];
                displayName = parts[1];
            }

            DeviceUserSession newSession = DeviceUserSession.builder()
                    .tenantId(tenantId)
                    .deviceId(deviceId)
                    .username(username)
                    .windowsDomain(windowsDomain)
                    .displayName(displayName)
                    .isActive(true)
                    .build();
            userSessionRepository.save(newSession);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toMap(Object value) {
        if (value == null) return Map.of();
        if (value instanceof Map) return (Map<String, Object>) value;
        if (value instanceof String str) {
            try {
                return new com.fasterxml.jackson.databind.ObjectMapper().readValue(str, Map.class);
            } catch (Exception e) {
                return Map.of();
            }
        }
        return Map.of();
    }
}
