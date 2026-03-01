export interface PermissionResponse {
  id: string;
  code: string;
  name: string;
  resource: string;
  action: string;
}

export interface RoleResponse {
  id: string;
  code: string;
  name: string;
  description: string | null;
  is_system: boolean;
  permissions?: PermissionResponse[];
  permissions_count?: number;
  users_count?: number;
  created_ts: string;
  updated_ts?: string;
}

export interface CreateRoleRequest {
  code: string;
  name: string;
  description?: string;
  permission_ids: string[];
}

export interface UpdateRoleRequest {
  name?: string;
  description?: string;
  permission_ids?: string[];
}
