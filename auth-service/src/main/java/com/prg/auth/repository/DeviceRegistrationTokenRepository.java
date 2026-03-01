package com.prg.auth.repository;

import com.prg.auth.entity.DeviceRegistrationToken;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DeviceRegistrationTokenRepository extends JpaRepository<DeviceRegistrationToken, UUID> {

    Optional<DeviceRegistrationToken> findByTokenHash(String tokenHash);

    List<DeviceRegistrationToken> findByTenantIdAndIsActiveTrue(UUID tenantId);

    Page<DeviceRegistrationToken> findByTenantId(UUID tenantId, Pageable pageable);

    @Modifying
    @Query("UPDATE DeviceRegistrationToken t SET t.currentUses = t.currentUses + 1 WHERE t.id = :id")
    void incrementCurrentUses(@Param("id") UUID id);
}
