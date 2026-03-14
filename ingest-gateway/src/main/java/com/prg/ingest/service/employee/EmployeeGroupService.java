package com.prg.ingest.service.employee;

import com.prg.ingest.dto.employee.*;
import com.prg.ingest.entity.employee.EmployeeGroup;
import com.prg.ingest.entity.employee.EmployeeGroupMember;
import com.prg.ingest.repository.employee.EmployeeGroupMemberRepository;
import com.prg.ingest.repository.employee.EmployeeGroupRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmployeeGroupService {

    private final EmployeeGroupRepository groupRepo;
    private final EmployeeGroupMemberRepository memberRepo;
    private final EntityManager em;

    /**
     * Load all groups for tenant, build a 2-level tree with children,
     * memberCount per group and totalMemberCount (including children).
     */
    @Transactional(readOnly = true)
    public List<EmployeeGroupResponse> getGroups(UUID tenantId) {
        List<EmployeeGroup> allGroups = groupRepo.findByTenantIdOrderBySortOrderAscNameAsc(tenantId);

        // Count members per group
        Map<UUID, Long> memberCounts = new HashMap<>();
        for (EmployeeGroup g : allGroups) {
            memberCounts.put(g.getId(), memberRepo.countByGroupIdAndTenantId(g.getId(), tenantId));
        }

        // Separate root and child groups
        Map<UUID, List<EmployeeGroup>> childrenMap = allGroups.stream()
                .filter(g -> g.getParentId() != null)
                .collect(Collectors.groupingBy(EmployeeGroup::getParentId));

        // Build tree: root groups with children
        return allGroups.stream()
                .filter(g -> g.getParentId() == null)
                .map(root -> {
                    long rootMemberCount = memberCounts.getOrDefault(root.getId(), 0L);
                    List<EmployeeGroupResponse> children = childrenMap
                            .getOrDefault(root.getId(), Collections.emptyList()).stream()
                            .map(child -> {
                                long childMemberCount = memberCounts.getOrDefault(child.getId(), 0L);
                                return toResponse(child, childMemberCount, childMemberCount, Collections.emptyList());
                            })
                            .toList();

                    long totalMemberCount = rootMemberCount + children.stream()
                            .mapToLong(EmployeeGroupResponse::getMemberCount)
                            .sum();

                    return toResponse(root, rootMemberCount, totalMemberCount, children);
                })
                .toList();
    }

    @Transactional
    public EmployeeGroupResponse createGroup(UUID tenantId, UUID userId, EmployeeGroupCreateRequest request) {
        UUID parentId = request.getParentId();

        if (parentId != null) {
            // Validate parent exists and is a root group (max depth 2)
            EmployeeGroup parent = groupRepo.findByIdAndTenantId(parentId, tenantId)
                    .orElseThrow(() -> new IllegalArgumentException("Parent group not found"));
            if (parent.getParentId() != null) {
                throw new IllegalArgumentException("Maximum group depth (2 levels) exceeded");
            }
            // Check name uniqueness among siblings
            if (groupRepo.existsByTenantIdAndParentIdAndNameIgnoreCase(tenantId, parentId, request.getName())) {
                throw new IllegalArgumentException("Child group with name '" + request.getName() + "' already exists under this parent");
            }
        } else {
            // Root group: check name uniqueness among root groups
            if (groupRepo.existsByTenantIdAndNameIgnoreCase(tenantId, request.getName())) {
                throw new IllegalArgumentException("Group with name '" + request.getName() + "' already exists");
            }
        }

        EmployeeGroup group = EmployeeGroup.builder()
                .tenantId(tenantId)
                .name(request.getName().trim())
                .description(request.getDescription())
                .color(request.getColor())
                .sortOrder(request.getSortOrder() != null ? request.getSortOrder() : 0)
                .parentId(parentId)
                .createdBy(userId)
                .build();

        group = groupRepo.save(group);
        log.info("Created employee group '{}' (parent={}) for tenant {}", group.getName(), parentId, tenantId);
        return toResponse(group, 0, 0, Collections.emptyList());
    }

    @Transactional
    public EmployeeGroupResponse updateGroup(UUID tenantId, UUID groupId, EmployeeGroupUpdateRequest request) {
        EmployeeGroup group = groupRepo.findByIdAndTenantId(groupId, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Group not found"));

        if (request.getName() != null && !request.getName().isBlank()) {
            if (group.getParentId() != null) {
                if (groupRepo.existsByTenantIdAndParentIdAndNameIgnoreCaseAndIdNot(
                        tenantId, group.getParentId(), request.getName(), groupId)) {
                    throw new IllegalArgumentException("Child group with name '" + request.getName() + "' already exists under this parent");
                }
            } else {
                if (groupRepo.existsByTenantIdAndNameIgnoreCaseAndIdNot(tenantId, request.getName(), groupId)) {
                    throw new IllegalArgumentException("Group with name '" + request.getName() + "' already exists");
                }
            }
            group.setName(request.getName().trim());
        }
        if (request.getDescription() != null) group.setDescription(request.getDescription());
        if (request.getColor() != null) group.setColor(request.getColor());
        if (request.getSortOrder() != null) group.setSortOrder(request.getSortOrder());

        group = groupRepo.save(group);
        long count = memberRepo.countByGroupIdAndTenantId(groupId, tenantId);
        return toResponse(group, count, count, Collections.emptyList());
    }

    @Transactional
    public void deleteGroup(UUID tenantId, UUID groupId) {
        EmployeeGroup group = groupRepo.findByIdAndTenantId(groupId, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Group not found"));
        groupRepo.delete(group);
        log.info("Deleted employee group '{}' for tenant {}", group.getName(), tenantId);
    }

    @Transactional(readOnly = true)
    public List<EmployeeGroupMemberResponse> getMembers(UUID tenantId, UUID groupId) {
        groupRepo.findByIdAndTenantId(groupId, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Group not found"));

        return memberRepo.findByGroupIdAndTenantId(groupId, tenantId).stream()
                .map(this::toMemberResponse)
                .toList();
    }

    /**
     * Add employee to a group. Only leaf groups (no children) can have members.
     * Employee can now be in multiple groups (no auto-remove from other groups).
     */
    @Transactional
    public EmployeeGroupMemberResponse addMember(UUID tenantId, UUID groupId, AssignEmployeeRequest request) {
        EmployeeGroup group = groupRepo.findByIdAndTenantId(groupId, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Group not found"));

        // Prevent adding members to parent groups that have children
        if (groupRepo.existsByParentId(groupId)) {
            throw new IllegalArgumentException("Cannot assign employee to a parent group that has child groups");
        }

        // Check if already in this specific group
        Optional<EmployeeGroupMember> existing = memberRepo.findByGroupIdAndTenantIdAndUsernameIgnoreCase(
                groupId, tenantId, request.getUsername());
        if (existing.isPresent()) {
            log.debug("Employee '{}' already in group '{}'", request.getUsername(), group.getName());
            return toMemberResponse(existing.get());
        }

        EmployeeGroupMember member = EmployeeGroupMember.builder()
                .tenantId(tenantId)
                .group(group)
                .username(request.getUsername())
                .build();

        member = memberRepo.save(member);
        log.info("Added employee '{}' to group '{}' for tenant {}", request.getUsername(), group.getName(), tenantId);
        return toMemberResponse(member);
    }

    @Transactional
    public void removeMember(UUID tenantId, UUID memberId) {
        EmployeeGroupMember member = memberRepo.findByIdAndTenantId(memberId, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Member not found"));
        memberRepo.delete(member);
        log.info("Removed employee '{}' from group for tenant {}", member.getUsername(), tenantId);
    }

    /**
     * Get metrics: total employees and active employees, optionally filtered by group.
     * If groupId is null, returns metrics for the entire tenant.
     * If groupId is specified, includes employees in the group and its children.
     */
    @Transactional(readOnly = true)
    public GroupMetricsResponse getGroupMetrics(UUID tenantId, UUID groupId) {
        if (groupId == null) {
            // Tenant-wide metrics from v_tenant_users
            String totalSql = "SELECT COUNT(*) FROM v_tenant_users WHERE tenant_id = :tenantId";
            String activeSql = "SELECT COUNT(*) FROM v_tenant_users WHERE tenant_id = :tenantId AND is_active = true";

            long total = ((Number) em.createNativeQuery(totalSql)
                    .setParameter("tenantId", tenantId).getSingleResult()).longValue();
            long active = ((Number) em.createNativeQuery(activeSql)
                    .setParameter("tenantId", tenantId).getSingleResult()).longValue();

            return GroupMetricsResponse.builder()
                    .totalEmployees(total)
                    .activeEmployees(active)
                    .build();
        }

        // Group-specific metrics: include group + its children
        String totalSql = """
                SELECT COUNT(DISTINCT LOWER(egm.username))
                FROM employee_group_members egm
                WHERE egm.tenant_id = :tenantId
                  AND egm.group_id IN (
                    SELECT id FROM employee_groups WHERE id = :groupId OR parent_id = :groupId
                  )
                """;

        String activeSql = """
                SELECT COUNT(DISTINCT LOWER(vtu.username))
                FROM v_tenant_users vtu
                WHERE vtu.tenant_id = :tenantId
                  AND vtu.is_active = true
                  AND LOWER(vtu.username) IN (
                    SELECT LOWER(egm.username)
                    FROM employee_group_members egm
                    WHERE egm.tenant_id = :tenantId
                      AND egm.group_id IN (
                        SELECT id FROM employee_groups WHERE id = :groupId OR parent_id = :groupId
                      )
                  )
                """;

        long total = ((Number) em.createNativeQuery(totalSql)
                .setParameter("tenantId", tenantId)
                .setParameter("groupId", groupId)
                .getSingleResult()).longValue();

        long active = ((Number) em.createNativeQuery(activeSql)
                .setParameter("tenantId", tenantId)
                .setParameter("groupId", groupId)
                .getSingleResult()).longValue();

        return GroupMetricsResponse.builder()
                .totalEmployees(total)
                .activeEmployees(active)
                .build();
    }

    private EmployeeGroupResponse toResponse(EmployeeGroup group, long memberCount, long totalMemberCount,
                                              List<EmployeeGroupResponse> children) {
        return EmployeeGroupResponse.builder()
                .id(group.getId())
                .parentId(group.getParentId())
                .name(group.getName())
                .description(group.getDescription())
                .color(group.getColor())
                .sortOrder(group.getSortOrder())
                .memberCount(memberCount)
                .totalMemberCount(totalMemberCount)
                .children(children != null ? new ArrayList<>(children) : new ArrayList<>())
                .createdAt(group.getCreatedAt())
                .updatedAt(group.getUpdatedAt())
                .build();
    }

    private EmployeeGroupMemberResponse toMemberResponse(EmployeeGroupMember member) {
        return EmployeeGroupMemberResponse.builder()
                .id(member.getId())
                .groupId(member.getGroup().getId())
                .username(member.getUsername())
                .createdAt(member.getCreatedAt())
                .build();
    }
}
