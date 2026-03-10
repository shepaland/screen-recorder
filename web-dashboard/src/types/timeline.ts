export interface TimelineSite {
  domain: string;
  duration_ms: number;
  has_recording: boolean;
}

export interface TimelineSiteGroup {
  group_id: string | null;
  group_name: string;
  color: string;
  duration_ms: number;
  sites: TimelineSite[];
}

export interface TimelineApp {
  process_name: string;
  duration_ms: number;
  has_recording: boolean;
}

export interface TimelineAppGroup {
  group_id: string | null;
  group_name: string;
  color: string;
  duration_ms: number;
  is_browser_group?: boolean;
  apps?: TimelineApp[];
  site_groups?: TimelineSiteGroup[];
}

export interface TimelineHour {
  hour: number;
  total_duration_ms: number;
  has_recording: boolean;
  recording_session_ids: string[];
  app_groups: TimelineAppGroup[];
}

export interface TimelineUser {
  username: string;
  display_name: string | null;
  device_ids: string[];
  hours: TimelineHour[];
}

export interface TimelineResponse {
  date: string;
  timezone: string;
  users: TimelineUser[];
}
