package com.prg.controlplane.repository;

import com.prg.controlplane.entity.DeviceCommand;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DeviceCommandRepository extends JpaRepository<DeviceCommand, UUID> {

    Optional<DeviceCommand> findByIdAndTenantId(UUID id, UUID tenantId);

    @Query("""
            SELECT c FROM DeviceCommand c
            WHERE c.deviceId = :deviceId
              AND c.tenantId = :tenantId
              AND c.status = 'pending'
              AND (c.expiresAt IS NULL OR c.expiresAt > :now)
            ORDER BY c.createdTs ASC
            """)
    List<DeviceCommand> findPendingCommandsByDeviceIdAndTenantId(
            @Param("deviceId") UUID deviceId,
            @Param("tenantId") UUID tenantId,
            @Param("now") Instant now);

    @Query("""
            SELECT c FROM DeviceCommand c
            WHERE c.deviceId = :deviceId
              AND c.tenantId = :tenantId
              AND (:status IS NULL OR c.status = :status)
            ORDER BY c.createdTs DESC
            """)
    Page<DeviceCommand> findByDeviceIdAndTenantIdWithFilters(
            @Param("deviceId") UUID deviceId,
            @Param("tenantId") UUID tenantId,
            @Param("status") String status,
            Pageable pageable);

    @Query("""
            SELECT c FROM DeviceCommand c
            WHERE c.deviceId = :deviceId
              AND c.tenantId = :tenantId
            ORDER BY c.createdTs DESC
            LIMIT :limit
            """)
    List<DeviceCommand> findRecentByDeviceIdAndTenantId(
            @Param("deviceId") UUID deviceId,
            @Param("tenantId") UUID tenantId,
            @Param("limit") int limit);
}
