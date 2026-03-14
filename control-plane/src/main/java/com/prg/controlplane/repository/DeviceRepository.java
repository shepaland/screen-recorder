package com.prg.controlplane.repository;

import com.prg.controlplane.entity.Device;
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

    Optional<Device> findByIdAndTenantId(UUID id, UUID tenantId);

    List<Device> findByTenantIdAndIsDeletedFalse(UUID tenantId);

    @Query("""
            SELECT d FROM Device d
            WHERE d.tenantId = :tenantId
              AND (:status IS NULL OR (
                  CASE WHEN :status = 'deleted' THEN d.isDeleted = TRUE
                       ELSE (d.status = :status AND d.isDeleted = FALSE)
                  END
              ))
              AND (:includeDeleted = TRUE OR d.isDeleted = FALSE)
              AND (:search IS NULL OR LOWER(d.hostname) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%')))
              AND (:filterByGroup = FALSE OR (
                  CASE WHEN :ungrouped = TRUE THEN d.deviceGroupId IS NULL
                       ELSE d.deviceGroupId IN :groupIds
                  END
              ))
            ORDER BY d.createdTs DESC
            """)
    Page<Device> findByTenantIdWithFilters(
            @Param("tenantId") UUID tenantId,
            @Param("status") String status,
            @Param("search") String search,
            @Param("includeDeleted") boolean includeDeleted,
            @Param("filterByGroup") boolean filterByGroup,
            @Param("ungrouped") boolean ungrouped,
            @Param("groupIds") List<UUID> groupIds,
            Pageable pageable);
}
