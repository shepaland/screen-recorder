package com.prg.auth.repository;

import com.prg.auth.entity.Role;
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
public interface RoleRepository extends JpaRepository<Role, UUID> {

    List<Role> findByTenantId(UUID tenantId);

    Page<Role> findByTenantId(UUID tenantId, Pageable pageable);

    @Query("SELECT r FROM Role r WHERE r.tenant.id = :tenantId " +
           "AND (:isSystem IS NULL OR r.isSystem = :isSystem)")
    Page<Role> findByTenantIdWithFilters(
            @Param("tenantId") UUID tenantId,
            @Param("isSystem") Boolean isSystem,
            Pageable pageable
    );

    Optional<Role> findByIdAndTenantId(UUID id, UUID tenantId);

    Optional<Role> findByTenantIdAndCode(UUID tenantId, String code);

    boolean existsByTenantIdAndCode(UUID tenantId, String code);

    @Query("SELECT r FROM Role r LEFT JOIN FETCH r.permissions WHERE r.tenant.id = :tenantId")
    List<Role> findByTenantIdWithPermissions(@Param("tenantId") UUID tenantId);

    @Query("SELECT r FROM Role r LEFT JOIN FETCH r.permissions WHERE r.id = :id AND r.tenant.id = :tenantId")
    Optional<Role> findByIdAndTenantIdWithPermissions(@Param("id") UUID id, @Param("tenantId") UUID tenantId);

    @Query("SELECT COUNT(u) FROM User u JOIN u.roles r WHERE r.id = :roleId")
    long countUsersByRoleId(@Param("roleId") UUID roleId);
}
