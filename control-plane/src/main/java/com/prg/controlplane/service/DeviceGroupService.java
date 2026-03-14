package com.prg.controlplane.service;

import com.prg.controlplane.dto.request.BulkAssignDevicesRequest;
import com.prg.controlplane.dto.request.CreateDeviceGroupRequest;
import com.prg.controlplane.dto.request.UpdateDeviceGroupRequest;
import com.prg.controlplane.dto.response.BulkAssignDevicesResponse;
import com.prg.controlplane.dto.response.DeviceGroupResponse;
import com.prg.controlplane.dto.response.DeviceGroupStatsResponse;
import com.prg.controlplane.entity.Device;
import com.prg.controlplane.entity.DeviceGroup;
import com.prg.controlplane.exception.ResourceNotFoundException;
import com.prg.controlplane.repository.DeviceGroupRepository;
import com.prg.controlplane.repository.DeviceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class DeviceGroupService {

    private final DeviceGroupRepository deviceGroupRepository;
    private final DeviceRepository deviceRepository;

    @Transactional(readOnly = true)
    public List<DeviceGroupResponse> getGroups(UUID tenantId, boolean includeStats) {
        List<DeviceGroup> groups = deviceGroupRepository.findByTenantIdOrderBySortOrderAscNameAsc(tenantId);

        if (!includeStats) {
            return groups.stream().map(this::toResponse).toList();
        }

        // Load all non-deleted devices for stats
        List<Device> allDevices = deviceRepository.findByTenantIdAndIsDeletedFalse(tenantId);

        // Group devices by device_group_id
        Map<UUID, List<Device>> devicesByGroup = allDevices.stream()
                .filter(d -> d.getDeviceGroupId() != null)
                .collect(Collectors.groupingBy(Device::getDeviceGroupId));

        // Build parent-child mapping
        Map<UUID, List<UUID>> childGroupIds = groups.stream()
                .filter(g -> g.getParentId() != null)
                .collect(Collectors.groupingBy(DeviceGroup::getParentId,
                        Collectors.mapping(DeviceGroup::getId, Collectors.toList())));

        return groups.stream().map(group -> {
            DeviceGroupResponse resp = toResponse(group);

            // Compute stats for this group (including children if it's a root group)
            List<Device> groupDevices = new ArrayList<>(devicesByGroup.getOrDefault(group.getId(), List.of()));

            // If root group, include devices from child groups
            if (group.getParentId() == null) {
                List<UUID> childIds = childGroupIds.getOrDefault(group.getId(), List.of());
                for (UUID childId : childIds) {
                    groupDevices.addAll(devicesByGroup.getOrDefault(childId, List.of()));
                }
            }

            int totalDevices = groupDevices.size();
            int onlineDevices = (int) groupDevices.stream()
                    .filter(d -> "online".equals(d.getStatus()) || "recording".equals(d.getStatus()))
                    .count();

            resp.setStats(DeviceGroupStatsResponse.builder()
                    .totalDevices(totalDevices)
                    .onlineDevices(onlineDevices)
                    .totalVideoGb(0.0) // Will be populated by frontend via separate call if needed
                    .build());

            return resp;
        }).toList();
    }

    @Transactional
    public DeviceGroupResponse createGroup(UUID tenantId, UUID userId, CreateDeviceGroupRequest request) {
        String name = request.getName().trim();

        // Validate parent
        if (request.getParentId() != null) {
            DeviceGroup parent = deviceGroupRepository.findByIdAndTenantId(request.getParentId(), tenantId)
                    .orElseThrow(() -> new ResourceNotFoundException("Parent group not found", "NOT_FOUND"));

            // Parent must be a root group (no grandchildren allowed)
            if (parent.getParentId() != null) {
                throw new IllegalArgumentException("Device group nesting depth cannot exceed 2 levels");
            }

            // Check unique name within parent
            if (deviceGroupRepository.existsChildByTenantIdAndParentIdAndName(tenantId, request.getParentId(), name)) {
                throw new IllegalStateException("A group with this name already exists under the same parent");
            }
        } else {
            // Check unique name for root groups
            if (deviceGroupRepository.existsRootByTenantIdAndName(tenantId, name)) {
                throw new IllegalStateException("A root group with this name already exists");
            }
        }

        DeviceGroup group = DeviceGroup.builder()
                .tenantId(tenantId)
                .parentId(request.getParentId())
                .name(name)
                .description(request.getDescription())
                .color(request.getColor())
                .sortOrder(request.getSortOrder() != null ? request.getSortOrder() : 0)
                .createdBy(userId)
                .build();

        group = deviceGroupRepository.save(group);
        log.info("Device group created: id={}, name={}, tenant_id={}, parent_id={}",
                group.getId(), group.getName(), tenantId, group.getParentId());

        return toResponse(group);
    }

    @Transactional
    public DeviceGroupResponse updateGroup(UUID tenantId, UUID groupId, UpdateDeviceGroupRequest request) {
        DeviceGroup group = deviceGroupRepository.findByIdAndTenantId(groupId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Device group not found", "NOT_FOUND"));

        if (request.getName() != null) {
            String name = request.getName().trim();

            // Check unique name
            if (group.getParentId() == null) {
                if (deviceGroupRepository.existsRootByTenantIdAndNameExcluding(tenantId, name, groupId)) {
                    throw new IllegalStateException("A root group with this name already exists");
                }
            } else {
                if (deviceGroupRepository.existsChildByTenantIdAndParentIdAndNameExcluding(tenantId, group.getParentId(), name, groupId)) {
                    throw new IllegalStateException("A group with this name already exists under the same parent");
                }
            }
            group.setName(name);
        }

        if (request.getDescription() != null) {
            group.setDescription(request.getDescription());
        }
        if (request.getColor() != null) {
            group.setColor(request.getColor());
        }
        if (request.getSortOrder() != null) {
            group.setSortOrder(request.getSortOrder());
        }

        group = deviceGroupRepository.save(group);
        log.info("Device group updated: id={}, tenant_id={}", groupId, tenantId);

        return toResponse(group);
    }

    @Transactional
    public void deleteGroup(UUID tenantId, UUID groupId) {
        DeviceGroup group = deviceGroupRepository.findByIdAndTenantId(groupId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Device group not found", "NOT_FOUND"));

        // ON DELETE CASCADE handles child groups
        // ON DELETE SET NULL handles devices.device_group_id
        deviceGroupRepository.delete(group);
        log.info("Device group deleted: id={}, name={}, tenant_id={}", groupId, group.getName(), tenantId);
    }

    @Transactional
    public void assignDeviceToGroup(UUID tenantId, UUID deviceId, UUID groupId) {
        Device device = deviceRepository.findByIdAndTenantId(deviceId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Device not found", "DEVICE_NOT_FOUND"));

        if (groupId != null) {
            DeviceGroup group = deviceGroupRepository.findByIdAndTenantId(groupId, tenantId)
                    .orElseThrow(() -> new ResourceNotFoundException("Device group not found", "NOT_FOUND"));

            if (!group.getTenantId().equals(tenantId)) {
                throw new IllegalArgumentException("Device group belongs to a different tenant");
            }
        }

        device.setDeviceGroupId(groupId);
        deviceRepository.save(device);
        log.info("Device {} assigned to group {} in tenant {}", deviceId, groupId, tenantId);
    }

    @Transactional
    public BulkAssignDevicesResponse bulkAssignDevices(UUID tenantId, UUID groupId, BulkAssignDevicesRequest request) {
        // Verify group exists and belongs to tenant
        DeviceGroup group = deviceGroupRepository.findByIdAndTenantId(groupId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Device group not found", "NOT_FOUND"));

        List<String> errors = new ArrayList<>();
        int assigned = 0;

        for (UUID deviceId : request.getDeviceIds()) {
            try {
                Optional<Device> deviceOpt = deviceRepository.findByIdAndTenantId(deviceId, tenantId);
                if (deviceOpt.isEmpty()) {
                    errors.add("Device not found: " + deviceId);
                    continue;
                }
                Device device = deviceOpt.get();
                device.setDeviceGroupId(groupId);
                deviceRepository.save(device);
                assigned++;
            } catch (Exception e) {
                errors.add("Failed to assign device " + deviceId + ": " + e.getMessage());
            }
        }

        log.info("Bulk assign: {} devices assigned to group {} in tenant {}, {} errors",
                assigned, groupId, tenantId, errors.size());

        return BulkAssignDevicesResponse.builder()
                .assigned(assigned)
                .errors(errors)
                .build();
    }

    private DeviceGroupResponse toResponse(DeviceGroup group) {
        return DeviceGroupResponse.builder()
                .id(group.getId())
                .parentId(group.getParentId())
                .name(group.getName())
                .description(group.getDescription())
                .color(group.getColor())
                .sortOrder(group.getSortOrder())
                .createdAt(group.getCreatedAt())
                .updatedAt(group.getUpdatedAt())
                .build();
    }
}
