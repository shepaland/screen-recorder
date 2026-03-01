import apiClient from './client';
import type {
  DeviceResponse,
  DeviceDetailResponse,
  DeviceCommandResponse,
  CreateCommandRequest,
} from '../types/device';
import type { PageResponse } from '../types/common';

export interface DevicesListParams {
  page?: number;
  size?: number;
  status?: string;
  search?: string;
}

export async function getDevices(params?: DevicesListParams): Promise<PageResponse<DeviceResponse>> {
  const response = await apiClient.get<PageResponse<DeviceResponse>>('/devices', { params });
  return response.data;
}

export async function getDevice(id: string): Promise<DeviceDetailResponse> {
  const response = await apiClient.get<DeviceDetailResponse>(`/devices/${id}`);
  return response.data;
}

export async function deleteDevice(id: string): Promise<void> {
  await apiClient.delete(`/devices/${id}`);
}

export async function sendCommand(deviceId: string, data: CreateCommandRequest): Promise<DeviceCommandResponse> {
  const response = await apiClient.post<DeviceCommandResponse>(`/devices/${deviceId}/commands`, data);
  return response.data;
}

export async function getDeviceCommands(
  deviceId: string,
  params?: { page?: number; size?: number },
): Promise<PageResponse<DeviceCommandResponse>> {
  const response = await apiClient.get<PageResponse<DeviceCommandResponse>>(
    `/devices/${deviceId}/commands`,
    { params },
  );
  return response.data;
}
