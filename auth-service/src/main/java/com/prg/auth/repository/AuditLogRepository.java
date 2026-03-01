package com.prg.auth.repository;

import com.prg.auth.entity.AuditLog;
import com.prg.auth.entity.AuditLogId;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.UUID;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, AuditLogId> {

    @Query("SELECT a FROM AuditLog a WHERE a.tenantId = :tenantId " +
           "AND (:userId IS NULL OR a.userId = :userId) " +
           "AND (:action IS NULL OR a.action = :action) " +
           "AND (:resourceType IS NULL OR a.resourceType = :resourceType) " +
           "AND (:fromTs IS NULL OR a.createdTs >= :fromTs) " +
           "AND (:toTs IS NULL OR a.createdTs <= :toTs) " +
           "ORDER BY a.createdTs DESC")
    Page<AuditLog> findByTenantIdWithFilters(
            @Param("tenantId") UUID tenantId,
            @Param("userId") UUID userId,
            @Param("action") String action,
            @Param("resourceType") String resourceType,
            @Param("fromTs") Instant fromTs,
            @Param("toTs") Instant toTs,
            Pageable pageable
    );
}
