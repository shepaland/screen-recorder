package com.prg.controlplane.repository;

import com.prg.controlplane.entity.DeviceLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface DeviceLogRepository extends JpaRepository<DeviceLog, UUID> {

    List<DeviceLog> findByDeviceIdAndTenantIdOrderByLogTypeAsc(UUID deviceId, UUID tenantId);

    @Query("SELECT DISTINCT d.logType FROM DeviceLog d WHERE d.deviceId = :deviceId AND d.tenantId = :tenantId ORDER BY d.logType")
    List<String> findLogTypesByDeviceIdAndTenantId(UUID deviceId, UUID tenantId);

    @Modifying
    @Query("DELETE FROM DeviceLog d WHERE d.deviceId = :deviceId AND d.tenantId = :tenantId")
    void deleteByDeviceIdAndTenantId(UUID deviceId, UUID tenantId);
}
