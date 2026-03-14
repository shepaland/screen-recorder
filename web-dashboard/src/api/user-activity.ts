import ingestApiClient from './ingest';
import type {
  UserListResponse,
  UserActivityResponse,
  AppsReportResponse,
  DomainsReportResponse,
  WorktimeResponse,
  TimesheetResponse,
  UserRecordingsResponse,
} from '../types/user-activity';
import type { TimelineResponse } from '../types/timeline';

export async function getUsers(params?: {
  page?: number;
  size?: number;
  search?: string;
  sortBy?: string;
  sortDir?: string;
  isActive?: boolean;
  groupId?: string;
  ungrouped?: boolean;
}): Promise<UserListResponse> {
  const { data } = await ingestApiClient.get<UserListResponse>('/users', {
    params: {
      page: params?.page ?? 0,
      size: params?.size ?? 20,
      search: params?.search,
      sort_by: params?.sortBy,
      sort_dir: params?.sortDir,
      is_active: params?.isActive,
      group_id: params?.groupId,
      ungrouped: params?.ungrouped,
    },
  });
  return data;
}

export async function getUserActivity(
  username: string,
  from: string,
  to: string,
  deviceId?: string,
): Promise<UserActivityResponse> {
  const { data } = await ingestApiClient.get<UserActivityResponse>('/users/activity', {
    params: { username, from, to, device_id: deviceId },
  });
  return data;
}

export async function getUserApps(
  username: string,
  from: string,
  to: string,
  params?: { page?: number; size?: number; deviceId?: string },
): Promise<AppsReportResponse> {
  const { data } = await ingestApiClient.get<AppsReportResponse>('/users/apps', {
    params: {
      username,
      from,
      to,
      page: params?.page,
      size: params?.size,
      device_id: params?.deviceId,
    },
  });
  return data;
}

export async function getUserDomains(
  username: string,
  from: string,
  to: string,
  params?: { page?: number; size?: number; deviceId?: string },
): Promise<DomainsReportResponse> {
  const { data } = await ingestApiClient.get<DomainsReportResponse>('/users/domains', {
    params: {
      username,
      from,
      to,
      page: params?.page,
      size: params?.size,
      device_id: params?.deviceId,
    },
  });
  return data;
}

export async function getUserWorktime(
  username: string,
  from: string,
  to: string,
  timezone = 'Europe/Moscow',
  deviceId?: string,
): Promise<WorktimeResponse> {
  const { data } = await ingestApiClient.get<WorktimeResponse>('/users/worktime', {
    params: { username, from, to, timezone, device_id: deviceId },
  });
  return data;
}

export async function getUserTimesheet(
  username: string,
  month: string,
  params?: {
    workStart?: string;
    workEnd?: string;
    timezone?: string;
    deviceId?: string;
  },
): Promise<TimesheetResponse> {
  const { data } = await ingestApiClient.get<TimesheetResponse>('/users/timesheet', {
    params: {
      username,
      month,
      work_start: params?.workStart,
      work_end: params?.workEnd,
      timezone: params?.timezone ?? 'Europe/Moscow',
      device_id: params?.deviceId,
    },
  });
  return data;
}

export async function getUserRecordings(
  username: string,
  from: string,
  to: string,
  params?: { page?: number; size?: number },
): Promise<UserRecordingsResponse> {
  const { data } = await ingestApiClient.get<UserRecordingsResponse>('/users/recordings', {
    params: {
      username,
      from,
      to,
      page: params?.page ?? 0,
      size: params?.size ?? 20,
    },
  });
  return data;
}

export async function getTimeline(
  date: string,
  timezone?: string,
): Promise<TimelineResponse> {
  const { data } = await ingestApiClient.get<TimelineResponse>('/users/timeline', {
    params: { date, timezone: timezone ?? 'Europe/Moscow' },
  });
  return data;
}
