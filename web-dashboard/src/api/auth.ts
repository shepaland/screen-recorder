import apiClient, { setAccessToken } from './client';
import type {
  LoginRequest,
  LoginResponse,
  TokenResponse,
  User,
  TenantPreview,
  SwitchTenantRequest,
  OnboardingRequest,
  OnboardingResponse,
  UpdateSettingsResponse,
} from '../types';

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

// --- OAuth ---

export function getOAuthLoginUrl(): string {
  const basePath = import.meta.env.BASE_URL.replace(/\/$/, '');
  return `${basePath}/api/v1/auth/oauth/yandex`;
}

export async function selectTenant(data: SwitchTenantRequest): Promise<LoginResponse> {
  const response = await apiClient.post<LoginResponse>('/auth/oauth/select-tenant', data);
  setAccessToken(response.data.access_token);
  return response.data;
}

export async function switchTenant(tenantId: string): Promise<LoginResponse> {
  const response = await apiClient.post<LoginResponse>('/auth/switch-tenant', { tenant_id: tenantId });
  setAccessToken(response.data.access_token);
  return response.data;
}

export async function getMyTenants(): Promise<{ tenants: TenantPreview[] }> {
  const response = await apiClient.get<{ tenants: TenantPreview[] }>('/auth/my-tenants');
  return response.data;
}

export async function onboarding(data: OnboardingRequest, oauthToken: string): Promise<OnboardingResponse> {
  const response = await apiClient.post<OnboardingResponse>('/auth/oauth/onboarding', data, {
    headers: { Authorization: `Bearer ${oauthToken}` },
  });
  setAccessToken(response.data.access_token);
  return response.data;
}

export async function updateMySettings(data: { session_ttl_days?: number }): Promise<UpdateSettingsResponse> {
  const response = await apiClient.put<UpdateSettingsResponse>('/users/me/settings', data);
  return response.data;
}
