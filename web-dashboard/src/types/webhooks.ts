export interface WebhookSubscription {
  id: string;
  tenant_id: string;
  url: string;
  event_types: string[];
  secret: string | null;
  active: boolean;
  created_ts: string;
  updated_ts: string;
  created_by: string | null;
}

export interface WebhookDelivery {
  id: string;
  subscription_id: string;
  event_id: string;
  event_type: string;
  status: string;
  response_code: number | null;
  attempts: number;
  last_attempt_ts: string | null;
  error_message: string | null;
  created_ts: string;
}

export interface CreateWebhookRequest {
  url: string;
  event_types: string[];
  secret?: string;
  active?: boolean;
}
