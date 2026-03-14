import ingestApiClient from './ingest';

export interface InputEventItem {
  id: string;
  event_ts: string;
  event_type: 'mouse_click' | 'keyboard_metric' | 'scroll' | 'clipboard';
  username: string;
  device_id: string;
  device_hostname: string;
  session_id: string | null;
  segment_id: string | null;
  segment_offset_ms: number | null;

  // Mouse click
  click_x: number | null;
  click_y: number | null;
  click_button: string | null;
  click_type: string | null;

  // UI element
  ui_element_type: string | null;
  ui_element_name: string | null;

  // Keyboard
  keystroke_count: number | null;
  has_typing_burst: boolean | null;

  // Scroll
  scroll_direction: string | null;
  scroll_total_delta: number | null;
  scroll_event_count: number | null;

  // Clipboard
  clipboard_action: string | null;
  clipboard_content_type: string | null;
  clipboard_content_length: number | null;

  // Context
  process_name: string | null;
  window_title: string | null;
}

export interface InputEventsPageResponse {
  content: InputEventItem[];
  total_elements: number;
  total_pages: number;
  number: number;
  size: number;
}

export interface InputEventsQueryParams {
  from: string;
  to: string;
  event_type?: string;
  username?: string;
  device_id?: string;
  search?: string;
  page?: number;
  size?: number;
  sort_by?: string;
  sort_dir?: string;
}

export async function getInputEvents(params: InputEventsQueryParams): Promise<InputEventsPageResponse> {
  const { data } = await ingestApiClient.get<InputEventsPageResponse>('/activity/input-events', { params });
  return data;
}
