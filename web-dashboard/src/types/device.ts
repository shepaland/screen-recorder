export interface DeviceTokenResponse {
  id: string;
  token?: string;
  token_preview?: string;
  name: string;
  max_uses: number | null;
  current_uses: number;
  device_count: number;
  expires_at: string | null;
  is_active: boolean;
  recording_enabled: boolean;
  created_by_username?: string;
  created_ts: string;
}

export interface CreateDeviceTokenRequest {
  name: string;
  max_uses?: number | null;
  expires_at?: string | null;
  recording_enabled?: boolean;
}

export interface UpdateDeviceTokenRequest {
  name?: string;
  max_uses?: number | null;
  expires_at?: string | null;
  is_active?: boolean;
  recording_enabled?: boolean;
}

export type DeviceStatus = 'offline' | 'online' | 'recording' | 'error';

export interface DeviceStatusLogEntry {
  id: string;
  device_id: string;
  status: string;
  previous_status: string | null;
  new_status: string;
  trigger: string | null;
  changed_ts: string;
  created_ts: string;
}

export interface DeviceSettings {
  capture_fps: number;
  resolution: string;
  quality: string;
  segment_duration_sec: number;
  session_max_duration_hours: number;
  auto_start: boolean;
  recording_enabled?: boolean;
  [key: string]: unknown;
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
  device_group_id: string | null;
  device_group_name: string | null;
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
  command_type: 'START_RECORDING' | 'STOP_RECORDING' | 'UPDATE_SETTINGS' | 'RESTART_AGENT' | 'UNREGISTER' | 'UPLOAD_LOGS';
  payload?: Record<string, unknown>;
}

export interface DeviceDayEntry {
  date: string;
  session_count: number;
  segment_count: number;
  total_duration_ms: number;
  total_bytes: number;
  live: boolean;
}

export interface DeviceDaysResponse {
  device_id: string;
  days: DeviceDayEntry[];
  timezone: string;
  page: number;
  size: number;
  total_elements: number;
  total_pages: number;
}

export interface TimelineSegment {
  id: string;
  session_id: string;
  segment_index: number;
  sequence_num: number;
  s3_key: string;
  started_ts: string;
  ended_ts: string;
  duration_ms: number;
  size_bytes: number;
}

export interface TimelineSession {
  session_id: string;
  status: string;
  started_ts: string;
  ended_ts: string | null;
  segment_count: number;
  total_duration_ms: number;
  total_bytes: number;
  segments: TimelineSegment[];
}

export interface DayTimelineResponse {
  device_id: string;
  date: string;
  timezone: string;
  sessions: TimelineSession[];
}
