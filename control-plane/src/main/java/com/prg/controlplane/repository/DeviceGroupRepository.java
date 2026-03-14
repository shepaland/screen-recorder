package com.prg.controlplane.repository;

import com.prg.controlplane.entity.DeviceGroup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DeviceGroupRepository extends JpaRepository<DeviceGroup, UUID> {

    List<DeviceGroup> findByTenantIdOrderBySortOrderAscNameAsc(UUID tenantId);

    Optional<DeviceGroup> findByIdAndTenantId(UUID id, UUID tenantId);

    List<DeviceGroup> findByParentIdAndTenantId(UUID parentId, UUID tenantId);

    @Query("SELECT COUNT(dg) > 0 FROM DeviceGroup dg WHERE dg.tenantId = :tenantId AND dg.parentId IS NULL AND LOWER(dg.name) = LOWER(:name)")
    boolean existsRootByTenantIdAndName(@Param("tenantId") UUID tenantId, @Param("name") String name);

    @Query("SELECT COUNT(dg) > 0 FROM DeviceGroup dg WHERE dg.tenantId = :tenantId AND dg.parentId = :parentId AND LOWER(dg.name) = LOWER(:name)")
    boolean existsChildByTenantIdAndParentIdAndName(@Param("tenantId") UUID tenantId, @Param("parentId") UUID parentId, @Param("name") String name);

    @Query("SELECT COUNT(dg) > 0 FROM DeviceGroup dg WHERE dg.tenantId = :tenantId AND dg.parentId IS NULL AND LOWER(dg.name) = LOWER(:name) AND dg.id != :excludeId")
    boolean existsRootByTenantIdAndNameExcluding(@Param("tenantId") UUID tenantId, @Param("name") String name, @Param("excludeId") UUID excludeId);

    @Query("SELECT COUNT(dg) > 0 FROM DeviceGroup dg WHERE dg.tenantId = :tenantId AND dg.parentId = :parentId AND LOWER(dg.name) = LOWER(:name) AND dg.id != :excludeId")
    boolean existsChildByTenantIdAndParentIdAndNameExcluding(@Param("tenantId") UUID tenantId, @Param("parentId") UUID parentId, @Param("name") String name, @Param("excludeId") UUID excludeId);
}
