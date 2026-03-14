package com.prg.ingest.repository.employee;

import com.prg.ingest.entity.employee.EmployeeGroupMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface EmployeeGroupMemberRepository extends JpaRepository<EmployeeGroupMember, UUID> {

    List<EmployeeGroupMember> findByGroupIdAndTenantId(UUID groupId, UUID tenantId);

    @Query("SELECT m FROM EmployeeGroupMember m WHERE m.tenantId = :tenantId AND LOWER(m.username) = LOWER(:username)")
    List<EmployeeGroupMember> findAllByTenantIdAndUsernameIgnoreCase(UUID tenantId, String username);

    @Query("SELECT m FROM EmployeeGroupMember m WHERE m.group.id = :groupId AND m.tenantId = :tenantId AND LOWER(m.username) = LOWER(:username)")
    Optional<EmployeeGroupMember> findByGroupIdAndTenantIdAndUsernameIgnoreCase(UUID groupId, UUID tenantId, String username);

    Optional<EmployeeGroupMember> findByIdAndTenantId(UUID id, UUID tenantId);

    long countByGroupIdAndTenantId(UUID groupId, UUID tenantId);

    void deleteByGroupIdAndTenantId(UUID groupId, UUID tenantId);
}
