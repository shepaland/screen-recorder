package com.prg.controlplane.repository;

import com.prg.controlplane.entity.DeviceStatusLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import org.springframework.data.jpa.repository.Modifying;

import java.time.Instant;
import java.util.UUID;

@Repository
public interface DeviceStatusLogRepository extends JpaRepository<DeviceStatusLog, UUID> {

    @Query(value = """
            SELECT * FROM device_status_log dsl
            WHERE dsl.device_id = :deviceId
              AND dsl.tenant_id = :tenantId
              AND (CAST(:fromTs AS timestamptz) IS NULL OR dsl.changed_ts >= CAST(:fromTs AS timestamptz))
              AND (CAST(:toTs AS timestamptz) IS NULL OR dsl.changed_ts <= CAST(:toTs AS timestamptz))
            ORDER BY dsl.changed_ts DESC
            """,
            countQuery = """
            SELECT count(*) FROM device_status_log dsl
            WHERE dsl.device_id = :deviceId
              AND dsl.tenant_id = :tenantId
              AND (CAST(:fromTs AS timestamptz) IS NULL OR dsl.changed_ts >= CAST(:fromTs AS timestamptz))
              AND (CAST(:toTs AS timestamptz) IS NULL OR dsl.changed_ts <= CAST(:toTs AS timestamptz))
            """,
            nativeQuery = true)
    Page<DeviceStatusLog> findByDeviceIdAndTenantIdWithDateRange(
            @Param("deviceId") UUID deviceId,
            @Param("tenantId") UUID tenantId,
            @Param("fromTs") Instant from,
            @Param("toTs") Instant to,
            Pageable pageable);

    /** Direct INSERT to bypass immutable trigger on UPDATE. */
    @Modifying
    @Query(value = "INSERT INTO device_status_log (id, tenant_id, device_id, previous_status, new_status, trigger, details, changed_ts) " +
                   "VALUES (gen_random_uuid(), :tenantId, :deviceId, :prevStatus, :newStatus, :trigger, CAST(:details AS jsonb), now())",
           nativeQuery = true)
    void insertLog(@Param("tenantId") UUID tenantId,
                   @Param("deviceId") UUID deviceId,
                   @Param("prevStatus") String prevStatus,
                   @Param("newStatus") String newStatus,
                   @Param("trigger") String trigger,
                   @Param("details") String details);
}
