export interface DeviceTokenResponse {
  id: string;
  token?: string;
  token_preview?: string;
  name: string;
  max_uses: number | null;
  current_uses: number;
  expires_at: string | null;
  is_active: boolean;
  created_by_username?: string;
  created_ts: string;
}

export interface CreateDeviceTokenRequest {
  name: string;
  max_uses?: number | null;
  expires_at?: string | null;
}

export interface DeviceResponse {
  id: string;
  hostname: string;
  os_version: string;
  agent_version: string;
  status: 'offline' | 'online' | 'recording' | 'error';
  last_heartbeat_ts: string | null;
  last_recording_ts: string | null;
  ip_address: string | null;
  is_active: boolean;
  is_deleted: boolean;
  deleted_ts: string | null;
  settings: Record<string, unknown>;
  user?: {
    id: string;
    username: string;
    first_name: string;
    last_name: string;
  };
  created_ts: string;
}

export interface DeviceDetailResponse extends DeviceResponse {
  recent_commands: DeviceCommandResponse[];
  active_session?: RecordingSessionResponse;
}

export interface DeviceCommandResponse {
  id: string;
  command_type: string;
  payload: Record<string, unknown>;
  status: string;
  created_ts: string;
  delivered_ts: string | null;
  acknowledged_ts: string | null;
}

export interface RecordingSessionResponse {
  id: string;
  status: string;
  started_ts: string;
  ended_ts: string | null;
  segment_count: number;
  total_bytes: number;
  total_duration_ms: number;
}

export interface CreateCommandRequest {
  command_type: 'START_RECORDING' | 'STOP_RECORDING' | 'UPDATE_SETTINGS' | 'RESTART_AGENT' | 'UNREGISTER';
  payload?: Record<string, unknown>;
}
