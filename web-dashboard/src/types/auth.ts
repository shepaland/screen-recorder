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
  last_login_ts: string | null;
  created_ts: string;
  updated_ts?: string;
}

export interface LoginRequest {
  username: string;
  password: string;
  tenant_slug: string;
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
