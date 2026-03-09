package com.prg.controlplane.repository;

import com.prg.controlplane.entity.DeviceStatusLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.UUID;

@Repository
public interface DeviceStatusLogRepository extends JpaRepository<DeviceStatusLog, UUID> {

    @Query("""
            SELECT dsl FROM DeviceStatusLog dsl
            WHERE dsl.deviceId = :deviceId
              AND dsl.tenantId = :tenantId
              AND (:from IS NULL OR dsl.changedTs >= :from)
              AND (:to IS NULL OR dsl.changedTs <= :to)
            ORDER BY dsl.changedTs DESC
            """)
    Page<DeviceStatusLog> findByDeviceIdAndTenantIdWithDateRange(
            @Param("deviceId") UUID deviceId,
            @Param("tenantId") UUID tenantId,
            @Param("from") Instant from,
            @Param("to") Instant to,
            Pageable pageable);
}
