package com.prg.ingest.service.employee;

import com.prg.ingest.dto.employee.*;
import com.prg.ingest.entity.employee.EmployeeGroup;
import com.prg.ingest.entity.employee.EmployeeGroupMember;
import com.prg.ingest.repository.employee.EmployeeGroupMemberRepository;
import com.prg.ingest.repository.employee.EmployeeGroupRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmployeeGroupService {

    private final EmployeeGroupRepository groupRepo;
    private final EmployeeGroupMemberRepository memberRepo;

    @Transactional(readOnly = true)
    public List<EmployeeGroupResponse> getGroups(UUID tenantId) {
        return groupRepo.findByTenantIdOrderBySortOrderAscNameAsc(tenantId).stream()
                .map(g -> toResponse(g, memberRepo.countByGroupIdAndTenantId(g.getId(), tenantId)))
                .toList();
    }

    @Transactional
    public EmployeeGroupResponse createGroup(UUID tenantId, UUID userId, EmployeeGroupCreateRequest request) {
        if (groupRepo.existsByTenantIdAndNameIgnoreCase(tenantId, request.getName())) {
            throw new IllegalArgumentException("Group with name '" + request.getName() + "' already exists");
        }

        EmployeeGroup group = EmployeeGroup.builder()
                .tenantId(tenantId)
                .name(request.getName().trim())
                .description(request.getDescription())
                .color(request.getColor())
                .sortOrder(request.getSortOrder() != null ? request.getSortOrder() : 0)
                .createdBy(userId)
                .build();

        group = groupRepo.save(group);
        log.info("Created employee group '{}' for tenant {}", group.getName(), tenantId);
        return toResponse(group, 0);
    }

    @Transactional
    public EmployeeGroupResponse updateGroup(UUID tenantId, UUID groupId, EmployeeGroupUpdateRequest request) {
        EmployeeGroup group = groupRepo.findByIdAndTenantId(groupId, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Group not found"));

        if (request.getName() != null && !request.getName().isBlank()) {
            if (groupRepo.existsByTenantIdAndNameIgnoreCaseAndIdNot(tenantId, request.getName(), groupId)) {
                throw new IllegalArgumentException("Group with name '" + request.getName() + "' already exists");
            }
            group.setName(request.getName().trim());
        }
        if (request.getDescription() != null) group.setDescription(request.getDescription());
        if (request.getColor() != null) group.setColor(request.getColor());
        if (request.getSortOrder() != null) group.setSortOrder(request.getSortOrder());

        group = groupRepo.save(group);
        long count = memberRepo.countByGroupIdAndTenantId(groupId, tenantId);
        return toResponse(group, count);
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
        // Validate group exists
        groupRepo.findByIdAndTenantId(groupId, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Group not found"));

        return memberRepo.findByGroupIdAndTenantId(groupId, tenantId).stream()
                .map(this::toMemberResponse)
                .toList();
    }

    @Transactional
    public EmployeeGroupMemberResponse addMember(UUID tenantId, UUID groupId, AssignEmployeeRequest request) {
        EmployeeGroup group = groupRepo.findByIdAndTenantId(groupId, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Group not found"));

        // Check if user is already in any group — if so, move them
        memberRepo.findByTenantIdAndUsernameIgnoreCase(tenantId, request.getUsername())
                .ifPresent(existing -> memberRepo.delete(existing));

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

    private EmployeeGroupResponse toResponse(EmployeeGroup group, long memberCount) {
        return EmployeeGroupResponse.builder()
                .id(group.getId())
                .name(group.getName())
                .description(group.getDescription())
                .color(group.getColor())
                .sortOrder(group.getSortOrder())
                .memberCount(memberCount)
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
