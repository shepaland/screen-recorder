import apiClient from './client';
import type { PageResponse, AuditLogResponse, AuditLogParams } from '../types';

export async function getAuditLogs(
  params?: AuditLogParams,
): Promise<PageResponse<AuditLogResponse>> {
  const response = await apiClient.get<PageResponse<AuditLogResponse>>('/audit-logs', { params });
  return response.data;
}
