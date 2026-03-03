package com.prg.controlplane.repository;

import com.prg.controlplane.entity.Device;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface DeviceRepository extends JpaRepository<Device, UUID> {

    Optional<Device> findByIdAndTenantId(UUID id, UUID tenantId);

    @Query("""
            SELECT d FROM Device d
            WHERE d.tenantId = :tenantId
              AND (:status IS NULL OR d.status = :status)
              AND (:search IS NULL OR LOWER(d.hostname) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%')))
            ORDER BY d.createdTs DESC
            """)
    Page<Device> findByTenantIdWithFilters(
            @Param("tenantId") UUID tenantId,
            @Param("status") String status,
            @Param("search") String search,
            Pageable pageable);
}
