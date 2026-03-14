export interface UserSummary {
  username: string
  display_name: string | null
  windows_domain: string | null
  device_count: number
  device_ids: string[]
  first_seen_ts: string
  last_seen_ts: string
  is_active: boolean
}

export interface UserListResponse {
  content: UserSummary[]
  page: number
  size: number
  total_elements: number
  total_pages: number
}

export interface TopApp {
  process_name: string
  window_title_sample: string
  total_duration_ms: number
  percentage: number
  interval_count: number
}

export interface TopDomain {
  domain: string
  browser_name: string
  total_duration_ms: number
  percentage: number
  visit_count: number
}

export interface UserActivityResponse {
  username: string
  display_name: string | null
  period: { from: string; to: string }
  summary: {
    total_active_ms: number
    total_days_active: number
    avg_daily_active_ms: number
    total_sessions: number
    total_focus_intervals: number
    unique_apps: number
    unique_domains: number
  }
  top_apps: TopApp[]
  top_domains: TopDomain[]
  daily_breakdown: Array<{
    date: string
    total_active_ms: number
    first_activity_ts: string | null
    last_activity_ts: string | null
  }>
}

export interface AppItem {
  process_name: string
  window_title_sample: string
  total_duration_ms: number
  percentage: number
  interval_count: number
}

export interface AppsReportResponse {
  username: string
  period: { from: string; to: string }
  total_active_ms: number
  content: AppItem[]
  page: number
  size: number
  total_elements: number
  total_pages: number
}

export interface DomainItem {
  domain: string
  browser_name: string
  total_duration_ms: number
  percentage: number
  visit_count: number
  avg_visit_duration_ms: number
  first_visit_ts: string
  last_visit_ts: string
}

export interface DomainsReportResponse {
  username: string
  period: { from: string; to: string }
  total_browser_ms: number
  content: DomainItem[]
  page: number
  size: number
  total_elements: number
  total_pages: number
}

export interface WorktimeDay {
  date: string
  weekday: string
  is_workday: boolean
  status: 'present' | 'absent' | 'partial' | 'weekend' | 'holiday'
  arrival_time: string | null
  departure_time: string | null
  total_hours: number
  active_hours: number
  is_late: boolean
  is_early_leave: boolean
  overtime_hours: number
}

export interface WorktimeResponse {
  username: string
  period: { from: string; to: string }
  work_schedule: { start: string; end: string; timezone: string }
  days: WorktimeDay[]
}

export interface TimesheetDay {
  date: string
  weekday: string
  is_workday: boolean
  status: string
  arrival_time: string | null
  departure_time: string | null
  total_hours: number
  active_hours: number
  idle_hours: number
  is_late: boolean
  is_early_leave: boolean
  overtime_hours: number
}

export interface RecordingItem {
  id: string
  device_id: string
  device_hostname: string | null
  status: string
  started_ts: string | null
  ended_ts: string | null
  segment_count: number
  total_bytes: number
  total_duration_ms: number
}

export interface UserRecordingsResponse {
  username: string
  content: RecordingItem[]
  page: number
  size: number
  total_elements: number
  total_pages: number
}

export interface TimesheetResponse {
  username: string
  display_name: string | null
  month: string
  work_schedule: { start: string; end: string; timezone: string }
  summary: {
    work_days_in_month: number
    days_present: number
    days_absent: number
    days_partial: number
    total_expected_hours: number
    total_actual_hours: number
    total_overtime_hours: number
    avg_arrival_time: string | null
    avg_departure_time: string | null
  }
  days: TimesheetDay[]
}
