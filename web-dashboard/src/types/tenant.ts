export interface TenantSettings {
  max_users?: number;
  max_retention_days?: number;
  features?: {
    ocr_search?: boolean;
    export_enabled?: boolean;
  };
}

export interface TenantResponse {
  id: string;
  name: string;
  slug: string;
  is_active: boolean;
  settings: TenantSettings;
  admin_user_id?: string;
  created_ts: string;
  updated_ts?: string;
}

export interface CreateTenantRequest {
  name: string;
  slug: string;
  settings?: TenantSettings;
  admin_user: {
    username: string;
    email: string;
    password: string;
    first_name?: string;
    last_name?: string;
  };
}

export interface UpdateTenantRequest {
  name?: string;
  is_active?: boolean;
  settings?: TenantSettings;
}
