import { cpApiClient } from './client';
import type { WebhookSubscription, WebhookDelivery, CreateWebhookRequest } from '../types/webhooks';

export async function listWebhooks(): Promise<WebhookSubscription[]> {
  const response = await cpApiClient.get<WebhookSubscription[]>('/webhooks');
  return response.data;
}

export async function createWebhook(data: CreateWebhookRequest): Promise<WebhookSubscription> {
  const response = await cpApiClient.post<WebhookSubscription>('/webhooks', data);
  return response.data;
}

export async function updateWebhook(id: string, data: Partial<CreateWebhookRequest> & { active?: boolean }): Promise<WebhookSubscription> {
  const response = await cpApiClient.put<WebhookSubscription>(`/webhooks/${id}`, data);
  return response.data;
}

export async function deleteWebhook(id: string): Promise<void> {
  await cpApiClient.delete(`/webhooks/${id}`);
}

export async function getWebhookDeliveries(id: string, params?: { page?: number; size?: number }): Promise<{
  content: WebhookDelivery[];
  page: number;
  size: number;
  total_elements: number;
  total_pages: number;
}> {
  const response = await cpApiClient.get(`/webhooks/${id}/deliveries`, { params });
  return response.data;
}
