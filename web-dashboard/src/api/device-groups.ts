import { cpApiClient } from './client';
import type {
  DeviceGroupResponse,
  CreateDeviceGroupRequest,
  UpdateDeviceGroupRequest,
  BulkAssignDevicesRequest,
  BulkAssignDevicesResponse,
} from '../types/device-groups';
import type { DeviceResponse } from '../types/device';

export async function getDeviceGroups(includeStats = false): Promise<DeviceGroupResponse[]> {
  const resp = await cpApiClient.get<DeviceGroupResponse[]>('/device-groups', {
    params: { include_stats: includeStats },
  });
  return resp.data;
}

export async function createDeviceGroup(data: CreateDeviceGroupRequest): Promise<DeviceGroupResponse> {
  const resp = await cpApiClient.post<DeviceGroupResponse>('/device-groups', data);
  return resp.data;
}

export async function updateDeviceGroup(id: string, data: UpdateDeviceGroupRequest): Promise<DeviceGroupResponse> {
  const resp = await cpApiClient.put<DeviceGroupResponse>(`/device-groups/${id}`, data);
  return resp.data;
}

export async function deleteDeviceGroup(id: string): Promise<void> {
  await cpApiClient.delete(`/device-groups/${id}`);
}

export async function assignDeviceToGroup(deviceId: string, groupId: string | null): Promise<DeviceResponse> {
  const resp = await cpApiClient.put<DeviceResponse>(`/devices/${deviceId}/group`, {
    device_group_id: groupId,
  });
  return resp.data;
}

export async function bulkAssignDevices(groupId: string, data: BulkAssignDevicesRequest): Promise<BulkAssignDevicesResponse> {
  const resp = await cpApiClient.post<BulkAssignDevicesResponse>(`/device-groups/${groupId}/devices`, data);
  return resp.data;
}
