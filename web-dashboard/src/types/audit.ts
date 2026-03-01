export interface AuditLogResponse {
  id: string;
  user_id: string | null;
  username: string | null;
  action: string;
  resource_type: string;
  resource_id: string | null;
  details: Record<string, unknown> | null;
  ip_address: string | null;
  created_ts: string;
}

export interface AuditLogParams {
  page?: number;
  size?: number;
  user_id?: string;
  action?: string;
  resource_type?: string;
  from_ts?: string;
  to_ts?: string;
}
