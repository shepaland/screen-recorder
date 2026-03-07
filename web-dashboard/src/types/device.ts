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
  os_type: string | null;
  agent_version: string;
  status: 'offline' | 'online' | 'recording' | 'error';
  last_heartbeat_ts: string | null;
  last_recording_ts: string | null;
  ip_address: string | null;
  timezone: string | null;
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

// --- Recording Archive types ---

export interface DeviceDaysResponse {
  device_id: string;
  device_hostname: string;
  timezone: string;
  days: RecordingDayItem[];
  page: number;
  size: number;
  total_elements: number;
  total_pages: number;
}

export interface RecordingDayItem {
  date: string;               // YYYY-MM-DD
  session_count: number;
  segment_count: number;
  total_bytes: number;
  total_duration_ms: number;
  live: boolean;
  first_started_ts: string;
  last_ended_ts: string | null;
}

export interface DayTimelineResponse {
  device_id: string;
  date: string;
  timezone: string;
  sessions: TimelineSession[];
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

export interface TimelineSegment {
  id: string;
  sequence_num: number;
  duration_ms: number;
  size_bytes: number;
  status: string;
  s3_key: string;
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

export interface DeviceSettings {
  capture_fps: number;
  resolution: string;
  quality: string;
  segment_duration_sec: number;
  session_max_duration_hours: number;
  auto_start: boolean;
}
