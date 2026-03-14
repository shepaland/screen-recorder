package com.prg.ingest.repository.employee;

import com.prg.ingest.entity.employee.EmployeeGroup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface EmployeeGroupRepository extends JpaRepository<EmployeeGroup, UUID> {

    List<EmployeeGroup> findByTenantIdOrderBySortOrderAscNameAsc(UUID tenantId);

    Optional<EmployeeGroup> findByIdAndTenantId(UUID id, UUID tenantId);

    @Query("SELECT COUNT(g) > 0 FROM EmployeeGroup g WHERE g.tenantId = :tenantId AND LOWER(g.name) = LOWER(:name)")
    boolean existsByTenantIdAndNameIgnoreCase(UUID tenantId, String name);

    @Query("SELECT COUNT(g) > 0 FROM EmployeeGroup g WHERE g.tenantId = :tenantId AND LOWER(g.name) = LOWER(:name) AND g.id <> :excludeId")
    boolean existsByTenantIdAndNameIgnoreCaseAndIdNot(UUID tenantId, String name, UUID excludeId);

    // --- Nested groups support ---

    List<EmployeeGroup> findByTenantIdAndParentIdIsNullOrderBySortOrderAscNameAsc(UUID tenantId);

    List<EmployeeGroup> findByParentId(UUID parentId);

    boolean existsByParentId(UUID parentId);

    @Query("SELECT COUNT(g) > 0 FROM EmployeeGroup g WHERE g.tenantId = :tenantId AND g.parentId = :parentId AND LOWER(g.name) = LOWER(:name)")
    boolean existsByTenantIdAndParentIdAndNameIgnoreCase(UUID tenantId, UUID parentId, String name);

    @Query("SELECT COUNT(g) > 0 FROM EmployeeGroup g WHERE g.tenantId = :tenantId AND g.parentId = :parentId AND LOWER(g.name) = LOWER(:name) AND g.id <> :excludeId")
    boolean existsByTenantIdAndParentIdAndNameIgnoreCaseAndIdNot(UUID tenantId, UUID parentId, String name, UUID excludeId);
}
