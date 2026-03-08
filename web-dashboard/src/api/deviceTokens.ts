import apiClient from './client';
import type { DeviceTokenResponse, CreateDeviceTokenRequest } from '../types/device';
import type { PageResponse } from '../types/common';

export interface TokenDeviceItem {
  id: string;
  hostname: string;
  os_info: string;
  status: string;
  is_active: boolean;
  is_deleted: boolean;
  last_heartbeat_ts: string | null;
  created_ts: string;
}

export interface TokenDevicesResponse {
  token_id: string;
  token_name: string;
  devices: TokenDeviceItem[];
  total_count: number;
}

export interface DeviceTokensListParams {
  page?: number;
  size?: number;
  search?: string;
  is_active?: boolean;
}

export async function getDeviceTokens(params?: DeviceTokensListParams): Promise<PageResponse<DeviceTokenResponse>> {
  const response = await apiClient.get<PageResponse<DeviceTokenResponse>>('/device-tokens', { params });
  return response.data;
}

export async function getDeviceToken(id: string): Promise<DeviceTokenResponse> {
  const response = await apiClient.get<DeviceTokenResponse>(`/device-tokens/${id}`);
  return response.data;
}

export async function createDeviceToken(data: CreateDeviceTokenRequest): Promise<DeviceTokenResponse> {
  const response = await apiClient.post<DeviceTokenResponse>('/device-tokens', data);
  return response.data;
}

export async function deleteDeviceToken(id: string): Promise<void> {
  await apiClient.delete(`/device-tokens/${id}`);
}

export async function getTokenDevices(tokenId: string): Promise<TokenDevicesResponse> {
  const response = await apiClient.get<TokenDevicesResponse>(`/device-tokens/${tokenId}/devices`);
  return response.data;
}

export async function revealDeviceToken(id: string): Promise<DeviceTokenResponse> {
  const response = await apiClient.post<DeviceTokenResponse>(`/device-tokens/${id}/reveal`);
  return response.data;
}

export async function hardDeleteDeviceToken(id: string): Promise<void> {
  await apiClient.delete(`/device-tokens/${id}`, { params: { hard: true } });
}
