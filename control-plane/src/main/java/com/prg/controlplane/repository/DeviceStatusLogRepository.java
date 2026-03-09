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
}
