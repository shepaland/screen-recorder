package com.prg.auth.repository;

import com.prg.auth.entity.TenantMembership;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TenantMembershipRepository extends JpaRepository<TenantMembership, UUID> {

    @Query("SELECT tm FROM TenantMembership tm " +
           "JOIN FETCH tm.tenant t " +
           "JOIN FETCH tm.roles r " +
           "WHERE tm.user.id = :userId AND tm.isActive = true AND t.isActive = true " +
           "ORDER BY tm.isDefault DESC, t.createdTs ASC")
    List<TenantMembership> findActiveByUserId(@Param("userId") UUID userId);

    @Query("SELECT tm FROM TenantMembership tm " +
           "JOIN FETCH tm.roles r JOIN FETCH r.permissions " +
           "JOIN FETCH tm.tenant t " +
           "WHERE tm.user.id = :userId AND tm.tenant.id = :tenantId " +
           "AND tm.isActive = true AND t.isActive = true")
    Optional<TenantMembership> findActiveByUserIdAndTenantId(
            @Param("userId") UUID userId, @Param("tenantId") UUID tenantId);

    boolean existsByUserIdAndTenantIdAndIsActiveTrue(UUID userId, UUID tenantId);

    @Query("SELECT COUNT(tm) FROM TenantMembership tm " +
           "JOIN tm.roles r WHERE tm.tenant.id = :tenantId AND r.code = :roleCode AND tm.isActive = true")
    long countByTenantIdAndRoleCode(@Param("tenantId") UUID tenantId, @Param("roleCode") String roleCode);
}
