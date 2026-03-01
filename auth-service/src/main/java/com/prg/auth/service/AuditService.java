package com.prg.auth.service;

import com.prg.auth.dto.response.AuditLogResponse;
import com.prg.auth.dto.response.PageResponse;
import com.prg.auth.entity.AuditLog;
import com.prg.auth.entity.User;
import com.prg.auth.repository.AuditLogRepository;
import com.prg.auth.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuditService {

    private final AuditLogRepository auditLogRepository;
    private final UserRepository userRepository;

    @PersistenceContext
    private EntityManager entityManager;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logAction(UUID tenantId, UUID userId, String action, String resourceType,
                          UUID resourceId, Map<String, Object> details,
                          String ipAddress, String userAgent, UUID correlationId) {
        try {
            AuditLog auditLog = AuditLog.builder()
                    .id(UUID.randomUUID())
                    .tenantId(tenantId)
                    .userId(userId)
                    .action(action)
                    .resourceType(resourceType)
                    .resourceId(resourceId)
                    .details(details)
                    .ipAddress(ipAddress)
                    .userAgent(userAgent)
                    .correlationId(correlationId)
                    .createdTs(Instant.now())
                    .build();

            entityManager.persist(auditLog);
            entityManager.flush();

            log.debug("Audit log created: action={}, resource_type={}, resource_id={}", action, resourceType, resourceId);
        } catch (Exception e) {
            log.error("Failed to write audit log: action={}, error={}", action, e.getMessage(), e);
        }
    }

    @Transactional(readOnly = true)
    public PageResponse<AuditLogResponse> getAuditLogs(UUID tenantId, UUID userId, String action,
                                                        String resourceType, Instant fromTs, Instant toTs,
                                                        int page, int size) {
        PageRequest pageRequest = PageRequest.of(page, Math.min(size, 200));
        Page<AuditLog> auditPage = auditLogRepository.findByTenantIdWithFilters(
                tenantId, userId, action, resourceType, fromTs, toTs, pageRequest);

        return PageResponse.<AuditLogResponse>builder()
                .content(auditPage.getContent().stream().map(this::toResponse).toList())
                .page(auditPage.getNumber())
                .size(auditPage.getSize())
                .totalElements(auditPage.getTotalElements())
                .totalPages(auditPage.getTotalPages())
                .build();
    }

    private AuditLogResponse toResponse(AuditLog auditLog) {
        String username = null;
        if (auditLog.getUserId() != null) {
            username = userRepository.findById(auditLog.getUserId())
                    .map(User::getUsername)
                    .orElse(null);
        }

        return AuditLogResponse.builder()
                .id(auditLog.getId())
                .userId(auditLog.getUserId())
                .username(username)
                .action(auditLog.getAction())
                .resourceType(auditLog.getResourceType())
                .resourceId(auditLog.getResourceId())
                .details(auditLog.getDetails())
                .ipAddress(auditLog.getIpAddress())
                .createdTs(auditLog.getCreatedTs())
                .build();
    }
}
