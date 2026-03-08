// ---- Group types ----

export type GroupType = 'APP' | 'SITE';
export type MatchType = 'EXACT' | 'SUFFIX' | 'CONTAINS';

// ---- Groups ----

export interface AppGroup {
  id: string;
  group_type: GroupType;
  name: string;
  description: string | null;
  color: string | null;
  sort_order: number;
  is_default: boolean;
  item_count: number;
  created_at: string;
  updated_at: string;
}

export interface GroupListResponse {
  groups: AppGroup[];
  total: number;
}

export interface CreateGroupRequest {
  group_type: GroupType;
  name: string;
  description?: string;
  color?: string;
  sort_order?: number;
}

export interface UpdateGroupRequest {
  name?: string;
  description?: string;
  color?: string;
  sort_order?: number;
}

// ---- Items ----

export interface GroupItem {
  id: string;
  item_type: GroupType;
  pattern: string;
  match_type: MatchType;
  display_name: string | null;
  created_at: string;
}

export interface GroupItemsResponse {
  items: GroupItem[];
  total: number;
}

export interface AddItemRequest {
  pattern: string;
  match_type?: MatchType;
}

export interface BatchAddItemsResponse {
  created: number;
  skipped: number;
  items: GroupItem[];
}

// ---- Aliases ----

export interface AppAlias {
  id: string;
  alias_type: GroupType;
  original: string;
  display_name: string;
  icon_url: string | null;
  created_at: string;
  updated_at: string;
}

export interface CreateAliasRequest {
  alias_type: GroupType;
  original: string;
  display_name: string;
}

export interface UpdateAliasRequest {
  display_name?: string;
  icon_url?: string | null;
}

// ---- Ungrouped ----

export interface UngroupedItem {
  name: string;
  display_name: string | null;
  total_duration_ms: number;
  interval_count: number;
  user_count: number;
  last_seen_at: string;
}

export interface UngroupedResponse {
  content: UngroupedItem[];
  page: number;
  size: number;
  total_elements: number;
  total_pages: number;
  total_ungrouped_duration_ms: number;
}

// ---- Dashboard Metrics ----

export interface DashboardMetrics {
  connected_devices: number;
  total_devices: number;
  active_devices: number;
  tokens_used: number;
  tokens_total: number;
  video_size_bytes: number;
}

// ---- Dashboard Group Timeline ----

export interface GroupTimelineDay {
  date: string;
  total_duration_ms: number;
  breakdown: Array<{
    group_id: string;
    duration_ms: number;
    percentage: number;
  }>;
  ungrouped_duration_ms: number;
  ungrouped_percentage: number;
}

export interface GroupTimelineResponse {
  group_type: GroupType;
  period: { from: string; to: string };
  groups: Array<{ group_id: string; group_name: string; color: string }>;
  days: GroupTimelineDay[];
}

// ---- Dashboard Group Summary ----

export interface GroupSummaryResponse {
  group_type: GroupType;
  period: { from: string; to: string };
  total_duration_ms: number;
  total_users: number;
  total_devices: number;
  groups: Array<{
    group_id: string;
    group_name: string;
    color: string;
    total_duration_ms: number;
    percentage: number;
    interval_count: number;
    user_count: number;
  }>;
  ungrouped: {
    total_duration_ms: number;
    percentage: number;
    interval_count: number;
    unique_items: number;
  };
}

// ---- Dashboard Top Ungrouped ----

export interface TopUngroupedItem {
  name: string;
  display_name: string | null;
  total_duration_ms: number;
  percentage: number;
  user_count: number;
  interval_count: number;
}

export interface TopUngroupedResponse {
  item_type: GroupType;
  period: { from: string; to: string };
  total_ungrouped_duration_ms: number;
  items: TopUngroupedItem[];
}

// ---- Dashboard Top Users Ungrouped ----

export interface TopUserUngrouped {
  username: string;
  display_name: string | null;
  total_ungrouped_duration_ms: number;
  total_active_duration_ms: number;
  ungrouped_percentage: number;
  top_ungrouped_items: Array<{ name: string; duration_ms: number }>;
}

export interface TopUsersUngroupedResponse {
  item_type: GroupType;
  period: { from: string; to: string };
  users: TopUserUngrouped[];
}

// ---- Seed ----

export interface SeedResponse {
  created_groups: number;
  created_items: number;
  created_aliases: number;
  message: string;
}
