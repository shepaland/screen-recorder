export interface UserRole {
  id?: string;
  code: string;
  name: string;
}

export interface UserResponse {
  id: string;
  tenant_id?: string;
  username: string;
  email: string;
  first_name: string | null;
  last_name: string | null;
  is_active: boolean;
  roles: UserRole[];
  last_login_ts: string | null;
  created_ts: string;
  updated_ts?: string;
}

export interface CreateUserRequest {
  username?: string;  // deprecated: auto-set from email
  email: string;
  password: string;
  first_name?: string;
  last_name?: string;
  role_ids?: string[];
}

export interface UpdateUserRequest {
  email?: string;
  first_name?: string;
  last_name?: string;
  is_active?: boolean;
  role_ids?: string[];
}

export interface ChangePasswordRequest {
  current_password?: string;
  new_password: string;
}
