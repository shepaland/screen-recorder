import apiClient from './client';
import type { PermissionResponse } from '../types';

interface PermissionsListResponse {
  content: PermissionResponse[];
  total_elements: number;
}

export async function getPermissions(): Promise<PermissionResponse[]> {
  const response = await apiClient.get<PermissionsListResponse>('/permissions');
  return response.data.content;
}
