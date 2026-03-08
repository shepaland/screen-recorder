package com.prg.ingest.repository.catalog;

import com.prg.ingest.entity.catalog.AppAlias;
import com.prg.ingest.entity.catalog.AppAlias.AliasType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface AppAliasRepository extends JpaRepository<AppAlias, UUID> {

    Page<AppAlias> findByTenantIdAndAliasType(UUID tenantId, AliasType aliasType, Pageable pageable);

    @Query("SELECT a FROM AppAlias a WHERE a.tenantId = :tenantId AND a.aliasType = :aliasType " +
            "AND (LOWER(a.original) LIKE LOWER(CONCAT('%', :search, '%')) " +
            "OR LOWER(a.displayName) LIKE LOWER(CONCAT('%', :search, '%')))")
    Page<AppAlias> findByTenantIdAndAliasTypeAndSearch(
            @Param("tenantId") UUID tenantId,
            @Param("aliasType") AliasType aliasType,
            @Param("search") String search,
            Pageable pageable);

    Optional<AppAlias> findByTenantIdAndAliasTypeAndOriginalIgnoreCase(
            UUID tenantId, AliasType aliasType, String original);

    boolean existsByTenantIdAndAliasTypeAndOriginalIgnoreCase(
            UUID tenantId, AliasType aliasType, String original);

    Optional<AppAlias> findByIdAndTenantId(UUID id, UUID tenantId);
}
