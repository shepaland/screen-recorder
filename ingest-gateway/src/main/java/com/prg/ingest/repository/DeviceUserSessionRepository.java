package com.prg.ingest.repository;

import com.prg.ingest.entity.DeviceUserSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface DeviceUserSessionRepository extends JpaRepository<DeviceUserSession, UUID> {

    Optional<DeviceUserSession> findByTenantIdAndDeviceIdAndUsernameAndIsActiveTrue(
            UUID tenantId, UUID deviceId, String username);
}
