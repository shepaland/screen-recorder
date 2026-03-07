export type AuditEventType =
  | 'SESSION_LOCK'
  | 'SESSION_UNLOCK'
  | 'SESSION_LOGON'
  | 'SESSION_LOGOFF'
  | 'PROCESS_START'
  | 'PROCESS_STOP';

export interface AuditEvent {
  id: string;
  event_type: AuditEventType;
  event_ts: string; // ISO 8601
  session_id: string | null;
  details: {
    process_name?: string;
    pid?: number;
    window_title?: string;
    exe_path?: string;
    reason?: string;
  };
}

export interface DeviceAuditEventsResponse {
  device_id: string;
  date: string;
  timezone: string;
  total_elements: number;
  events: AuditEvent[];
}
