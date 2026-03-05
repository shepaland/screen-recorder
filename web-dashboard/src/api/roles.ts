import apiClient from './client';
import type {
  PageResponse,
  RoleResponse,
  CreateRoleRequest,
  UpdateRoleRequest,
} from '../types';

export interface RolesListParams {
  page?: number;
  size?: number;
  is_system?: boolean;
}

export async function getRoles(params?: RolesListParams): Promise<PageResponse<RoleResponse>> {
  const response = await apiClient.get<PageResponse<RoleResponse>>('/roles', { params });
  return response.data;
}

export async function getRole(id: string): Promise<RoleResponse> {
  const response = await apiClient.get<RoleResponse>(`/roles/${id}`);
  return response.data;
}

export async function createRole(data: CreateRoleRequest): Promise<RoleResponse> {
  const response = await apiClient.post<RoleResponse>('/roles', data);
  return response.data;
}

export async function updateRole(id: string, data: UpdateRoleRequest): Promise<RoleResponse> {
  const response = await apiClient.put<RoleResponse>(`/roles/${id}`, data);
  return response.data;
}

export async function deleteRole(id: string): Promise<void> {
  await apiClient.delete(`/roles/${id}`);
}

export interface CloneRoleRequest {
  code: string;
  name: string;
  description?: string;
}

export async function cloneRole(id: string, data: CloneRoleRequest): Promise<RoleResponse> {
  const response = await apiClient.post<RoleResponse>(`/roles/${id}/clone`, data);
  return response.data;
}
