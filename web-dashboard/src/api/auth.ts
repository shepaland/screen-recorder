import apiClient, { setAccessToken } from './client';
import type { LoginRequest, LoginResponse, TokenResponse, User } from '../types';

export async function login(data: LoginRequest): Promise<LoginResponse> {
  const response = await apiClient.post<LoginResponse>('/auth/login', data);
  setAccessToken(response.data.access_token);
  return response.data;
}

export async function refresh(): Promise<TokenResponse> {
  const response = await apiClient.post<TokenResponse>('/auth/refresh');
  setAccessToken(response.data.access_token);
  return response.data;
}

export async function logout(): Promise<void> {
  try {
    await apiClient.post('/auth/logout');
  } finally {
    setAccessToken(null);
  }
}

export async function getMe(): Promise<User> {
  const response = await apiClient.get<User>('/users/me');
  return response.data;
}
