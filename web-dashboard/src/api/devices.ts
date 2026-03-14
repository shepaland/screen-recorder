import { cpApiClient } from './client';
import type {
  DeviceResponse,
  DeviceDetailResponse,
  DeviceCommandResponse,
  CreateCommandRequest,
  DeviceStatusLogEntry,
} from '../types/device';
import type { PageResponse } from '../types/common';

export interface DevicesListParams {
  page?: number;
  size?: number;
  status?: string;
  search?: string;
  include_deleted?: boolean;
  device_group_id?: string;
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

export async function updateDeviceSettings(
  deviceId: string,
  settings: Record<string, unknown>,
): Promise<DeviceResponse> {
  const response = await cpApiClient.put<DeviceResponse>(`/devices/${deviceId}`, { settings });
  return response.data;
}

export async function getDeviceStatusLog(
  deviceId: string,
  params?: { page?: number; size?: number },
): Promise<PageResponse<DeviceStatusLogEntry>> {
  const response = await cpApiClient.get<PageResponse<DeviceStatusLogEntry>>(
    `/devices/${deviceId}/status-log`,
    { params },
  );
  return response.data;
}

export interface DeviceLogEntry {
  id: string;
  log_type: string;
  content: string;
  log_from_ts: string | null;
  log_to_ts: string | null;
  uploaded_at: string;
}

export async function getDeviceLogs(deviceId: string): Promise<DeviceLogEntry[]> {
  const response = await cpApiClient.get<DeviceLogEntry[]>(`/devices/${deviceId}/logs`);
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
