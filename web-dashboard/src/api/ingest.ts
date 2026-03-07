import axios from 'axios';
import { getAccessToken } from './client';

const basePath = import.meta.env.BASE_URL.replace(/\/$/, '');
const INGEST_API_BASE_URL = import.meta.env.VITE_INGEST_API_BASE_URL || `${basePath}/api/ingest/v1/ingest`;

const ingestApiClient = axios.create({
  baseURL: INGEST_API_BASE_URL,
  headers: { 'Content-Type': 'application/json' },
  withCredentials: true,
});

// Request interceptor: attach Authorization header
ingestApiClient.interceptors.request.use(
  (config) => {
    const token = getAccessToken();
    if (token && config.headers) {
      config.headers.Authorization = `Bearer ${token}`;
    }
    return config;
  },
  (error) => Promise.reject(error),
);

export interface Recording {
  id: string;
  device_id: string;
  device_hostname: string;
  device_deleted?: boolean;
  status: string;
  started_ts: string;
  ended_ts: string | null;
  segment_count: number;
  total_bytes: number;
  total_duration_ms: number;
  metadata: Record<string, unknown>;
}

export interface RecordingsResponse {
  content: Recording[];
  page: number;
  size: number;
  total_elements: number;
  total_pages: number;
}

export interface Segment {
  id: string;
  sequence_num: number;
  duration_ms: number;
  size_bytes: number;
  status: string;
  s3_key: string;
}

export interface RecordingSegmentsResponse {
  session_id: string;
  segments: Segment[];
}

export async function getRecordings(params?: {
  page?: number;
  size?: number;
  status?: string;
  device_id?: string;
  from?: string;
  to?: string;
}): Promise<RecordingsResponse> {
  const response = await ingestApiClient.get<RecordingsResponse>('/recordings', { params });
  return response.data;
}

export async function getRecordingSegments(id: string): Promise<RecordingSegmentsResponse> {
  const response = await ingestApiClient.get<RecordingSegmentsResponse>(`/recordings/${id}/segments`);
  return response.data;
}

export async function deleteRecording(id: string): Promise<void> {
  await ingestApiClient.delete(`/recordings/${id}`);
}

export async function downloadRecording(id: string): Promise<{ blob: Blob; filename: string }> {
  const response = await ingestApiClient.get(`/recordings/${id}/download`, {
    responseType: 'blob',
  });
  const contentDisposition = response.headers['content-disposition'];
  let filename = `recording-${id}`;
  if (contentDisposition) {
    const match = contentDisposition.match(/filename="?([^"]+)"?/);
    if (match) filename = match[1];
  }
  return { blob: response.data, filename };
}

// --- Device Recording Archive API ---

import type { DeviceDaysResponse, DayTimelineResponse } from '../types/device';

export async function getDeviceRecordingDays(
  deviceId: string,
  params?: { page?: number; size?: number },
): Promise<DeviceDaysResponse> {
  const response = await ingestApiClient.get<DeviceDaysResponse>(
    `/recordings/by-device/${deviceId}/days`,
    { params },
  );
  return response.data;
}

export async function getDeviceDayTimeline(
  deviceId: string,
  date: string,
): Promise<DayTimelineResponse> {
  const response = await ingestApiClient.get<DayTimelineResponse>(
    `/recordings/by-device/${deviceId}/days/${date}/timeline`,
  );
  return response.data;
}

export default ingestApiClient;
