export interface User {
  id: string;
  tenant_id: string;
  username: string;
  email: string;
  first_name: string | null;
  last_name: string | null;
  is_active: boolean;
  roles: string[];
  permissions: string[];
  auth_provider?: 'password' | 'oauth';
  avatar_url?: string | null;
  last_login_ts: string | null;
  created_ts: string;
  updated_ts?: string;
}

export interface LoginRequest {
  username: string;
  password: string;
  tenant_slug?: string;
}

export interface LoginResponse {
  access_token: string;
  token_type: string;
  expires_in: number;
  user: User;
}

export interface TokenResponse {
  access_token: string;
  token_type: string;
  expires_in: number;
}

// --- OAuth types ---

export interface OAuthCallbackResponse {
  status: 'authenticated' | 'tenant_selection_required' | 'needs_onboarding';
  access_token?: string;
  token_type?: string;
  expires_in?: number;
  user?: User;
  oauth_token?: string;
  oauth_token_expires_in?: number;
  oauth_user?: OAuthUser;
  tenants?: TenantPreview[];
}

export interface OAuthUser {
  email: string;
  name: string;
  avatar_url: string | null;
}

export interface TenantPreview {
  id: string;
  name: string;
  slug: string;
  role: string;
  is_current?: boolean;
  created_ts?: string;
}

export interface OnboardingRequest {
  company_name: string;
  slug: string;
  first_name?: string;
  last_name?: string;
}

export interface OnboardingResponse {
  access_token: string;
  token_type: string;
  expires_in: number;
  tenant: TenantPreview;
  user: User;
}

export interface SwitchTenantRequest {
  tenant_id: string;
  oauth_token?: string;
}

export interface UserSettings {
  session_ttl_days?: number;
}

export interface UpdateSettingsResponse {
  settings: UserSettings;
  tenant_max_session_ttl_days: number;
}
