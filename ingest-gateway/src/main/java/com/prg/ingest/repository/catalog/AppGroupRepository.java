package com.prg.ingest.repository.catalog;

import com.prg.ingest.entity.catalog.AppGroup;
import com.prg.ingest.entity.catalog.AppGroup.GroupType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AppGroupRepository extends JpaRepository<AppGroup, UUID> {

    List<AppGroup> findByTenantIdAndGroupTypeOrderBySortOrder(UUID tenantId, GroupType groupType);

    long countByTenantIdAndGroupType(UUID tenantId, GroupType groupType);

    boolean existsByTenantIdAndGroupTypeAndNameIgnoreCase(UUID tenantId, GroupType groupType, String name);

    Optional<AppGroup> findByIdAndTenantId(UUID id, UUID tenantId);
}
