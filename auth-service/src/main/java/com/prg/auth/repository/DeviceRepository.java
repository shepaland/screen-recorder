package com.prg.auth.repository;

import com.prg.auth.entity.Device;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DeviceRepository extends JpaRepository<Device, UUID> {

    Optional<Device> findByTenantIdAndHardwareId(UUID tenantId, String hardwareId);

    Optional<Device> findByIdAndTenantId(UUID id, UUID tenantId);

    Page<Device> findByTenantId(UUID tenantId, Pageable pageable);

    List<Device> findByRegistrationTokenIdAndTenantId(UUID registrationTokenId, UUID tenantId);

    @Query("SELECT d.registrationToken.id, COUNT(d) FROM Device d " +
           "WHERE d.registrationToken.id IN :tokenIds AND d.tenant.id = :tenantId " +
           "AND d.isDeleted = false GROUP BY d.registrationToken.id")
    List<Object[]> countActiveDevicesByTokenIds(@Param("tokenIds") List<UUID> tokenIds,
                                                 @Param("tenantId") UUID tenantId);
}
