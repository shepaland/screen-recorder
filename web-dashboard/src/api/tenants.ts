import apiClient from './client';
import type {
  PageResponse,
  TenantResponse,
  CreateTenantRequest,
  UpdateTenantRequest,
} from '../types';

export interface TenantsListParams {
  page?: number;
  size?: number;
}

export async function getTenants(
  params?: TenantsListParams,
): Promise<PageResponse<TenantResponse>> {
  const response = await apiClient.get<PageResponse<TenantResponse>>('/tenants', { params });
  return response.data;
}

export async function createTenant(data: CreateTenantRequest): Promise<TenantResponse> {
  const response = await apiClient.post<TenantResponse>('/tenants', data);
  return response.data;
}

export async function updateTenant(
  id: string,
  data: UpdateTenantRequest,
): Promise<TenantResponse> {
  const response = await apiClient.put<TenantResponse>(`/tenants/${id}`, data);
  return response.data;
}
