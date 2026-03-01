import apiClient from './client';
import type { DeviceTokenResponse, CreateDeviceTokenRequest } from '../types/device';
import type { PageResponse } from '../types/common';

export interface DeviceTokensListParams {
  page?: number;
  size?: number;
}

export async function getDeviceTokens(params?: DeviceTokensListParams): Promise<PageResponse<DeviceTokenResponse>> {
  const response = await apiClient.get<PageResponse<DeviceTokenResponse>>('/device-tokens', { params });
  return response.data;
}

export async function createDeviceToken(data: CreateDeviceTokenRequest): Promise<DeviceTokenResponse> {
  const response = await apiClient.post<DeviceTokenResponse>('/device-tokens', data);
  return response.data;
}

export async function deleteDeviceToken(id: string): Promise<void> {
  await apiClient.delete(`/device-tokens/${id}`);
}
