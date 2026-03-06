import { cpApiClient } from './client';
import type {
  DeviceResponse,
  DeviceDetailResponse,
  DeviceCommandResponse,
  CreateCommandRequest,
  DeviceSettings,
} from '../types/device';
import type { PageResponse } from '../types/common';

export interface DevicesListParams {
  page?: number;
  size?: number;
  status?: string;
  search?: string;
  include_deleted?: boolean;
}

export async function getDevices(params?: DevicesListParams): Promise<PageResponse<DeviceResponse>> {
  const response = await cpApiClient.get<PageResponse<DeviceResponse>>('/devices', { params });
  return response.data;
}

export async function getDevice(id: string): Promise<DeviceDetailResponse> {
  const response = await cpApiClient.get<DeviceDetailResponse>(`/devices/${id}`);
  return response.data;
}

export async function deleteDevice(id: string): Promise<void> {
  await cpApiClient.delete(`/devices/${id}`);
}

export async function restoreDevice(id: string): Promise<void> {
  await cpApiClient.put(`/devices/${id}/restore`);
}

export async function sendCommand(deviceId: string, data: CreateCommandRequest): Promise<DeviceCommandResponse> {
  const response = await cpApiClient.post<DeviceCommandResponse>(`/devices/${deviceId}/commands`, data);
  return response.data;
}

export async function getDeviceCommands(
  deviceId: string,
  params?: { page?: number; size?: number },
): Promise<PageResponse<DeviceCommandResponse>> {
  const response = await cpApiClient.get<PageResponse<DeviceCommandResponse>>(
    `/devices/${deviceId}/commands`,
    { params },
  );
  return response.data;
}

export async function updateDeviceSettings(
  deviceId: string,
  settings: DeviceSettings,
): Promise<DeviceResponse> {
  const response = await cpApiClient.put<DeviceResponse>(
    `/devices/${deviceId}`,
    { settings },
  );
  return response.data;
}
