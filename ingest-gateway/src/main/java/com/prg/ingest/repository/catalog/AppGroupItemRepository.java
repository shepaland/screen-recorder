package com.prg.ingest.repository.catalog;

import com.prg.ingest.entity.catalog.AppGroupItem;
import com.prg.ingest.entity.catalog.AppGroupItem.ItemType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AppGroupItemRepository extends JpaRepository<AppGroupItem, UUID> {

    List<AppGroupItem> findByGroupId(UUID groupId);

    boolean existsByTenantIdAndItemTypeAndPatternIgnoreCase(UUID tenantId, ItemType itemType, String pattern);

    void deleteByGroupId(UUID groupId);

    Optional<AppGroupItem> findByIdAndTenantId(UUID id, UUID tenantId);

    List<AppGroupItem> findByTenantIdAndItemType(UUID tenantId, ItemType itemType);
}
