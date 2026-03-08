import apiClient from './client';
import type {
  PageResponse,
  UserResponse,
  CreateUserRequest,
  UpdateUserRequest,
  ChangePasswordRequest,
} from '../types';

export interface UsersListParams {
  page?: number;
  size?: number;
  sort?: string;
  search?: string;
  is_active?: boolean;
  role_code?: string;
}

export async function getUsers(params?: UsersListParams): Promise<PageResponse<UserResponse>> {
  const response = await apiClient.get<PageResponse<UserResponse>>('/users', { params });
  return response.data;
}

export async function getUser(id: string): Promise<UserResponse> {
  const response = await apiClient.get<UserResponse>(`/users/${id}`);
  return response.data;
}

export async function createUser(data: CreateUserRequest): Promise<UserResponse> {
  const response = await apiClient.post<UserResponse>('/users', data);
  return response.data;
}

export async function updateUser(id: string, data: UpdateUserRequest): Promise<UserResponse> {
  const response = await apiClient.put<UserResponse>(`/users/${id}`, data);
  return response.data;
}

export async function deleteUser(id: string): Promise<void> {
  await apiClient.delete(`/users/${id}`);
}

export async function changePassword(id: string, data: ChangePasswordRequest): Promise<void> {
  await apiClient.put(`/users/${id}/password`, data);
}

export async function hardDeleteUser(id: string): Promise<void> {
  await apiClient.delete(`/users/${id}`, { params: { hard: true } });
}
