import ingestApiClient from './ingest';
import type {
  GroupType,
  CreateGroupRequest,
  UpdateGroupRequest,
  AppGroup,
  AddItemRequest,
  GroupItem,
  AppAlias,
  UngroupedResponse,
  DashboardMetrics,
  GroupTimelineResponse,
  GroupSummaryResponse,
  TopUngroupedResponse,
  TopUsersUngroupedResponse,
  CreateAliasRequest,
  UpdateAliasRequest,
} from '../types/catalogs';

// ---- Groups ----

export async function getGroups(groupType: GroupType): Promise<AppGroup[]> {
  const { data } = await ingestApiClient.get<AppGroup[]>('/catalogs/groups', {
    params: { group_type: groupType },
  });
  return data;
}

export async function createGroup(request: CreateGroupRequest): Promise<AppGroup> {
  const { data } = await ingestApiClient.post<AppGroup>('/catalogs/groups', request);
  return data;
}

export async function updateGroup(groupId: string, request: UpdateGroupRequest): Promise<AppGroup> {
  const { data } = await ingestApiClient.put<AppGroup>(`/catalogs/groups/${groupId}`, request);
  return data;
}

export async function deleteGroup(groupId: string): Promise<void> {
  await ingestApiClient.delete(`/catalogs/groups/${groupId}`);
}

// ---- Items ----

export async function getGroupItems(groupId: string): Promise<GroupItem[]> {
  const { data } = await ingestApiClient.get<GroupItem[]>(
    `/catalogs/groups/${groupId}/items`,
  );
  return data;
}

export async function addGroupItem(groupId: string, request: AddItemRequest): Promise<GroupItem> {
  const { data } = await ingestApiClient.post<GroupItem>(
    `/catalogs/groups/${groupId}/items`,
    request,
  );
  return data;
}

export async function addGroupItemsBatch(
  groupId: string,
  items: AddItemRequest[],
): Promise<GroupItem[]> {
  const { data } = await ingestApiClient.post<GroupItem[]>(
    `/catalogs/groups/${groupId}/items/batch`,
    { items },
  );
  return data;
}

export async function deleteGroupItem(itemId: string): Promise<void> {
  await ingestApiClient.delete(`/catalogs/items/${itemId}`);
}

export async function moveGroupItem(itemId: string, targetGroupId: string): Promise<GroupItem> {
  const { data } = await ingestApiClient.put<GroupItem>(`/catalogs/items/${itemId}/move`, {
    target_group_id: targetGroupId,
  });
  return data;
}

// ---- Aliases ----

export async function getAliases(
  aliasType: GroupType,
  params?: { page?: number; size?: number; search?: string },
): Promise<{ content: AppAlias[]; page: number; size: number; total_elements: number; total_pages: number }> {
  const { data } = await ingestApiClient.get('/catalogs/aliases', {
    params: { alias_type: aliasType, ...params },
  });
  return data;
}

export async function createAlias(request: CreateAliasRequest): Promise<AppAlias> {
  const { data } = await ingestApiClient.post<AppAlias>('/catalogs/aliases', request);
  return data;
}

export async function updateAlias(aliasId: string, request: UpdateAliasRequest): Promise<AppAlias> {
  const { data } = await ingestApiClient.put<AppAlias>(`/catalogs/aliases/${aliasId}`, request);
  return data;
}

export async function deleteAlias(aliasId: string): Promise<void> {
  await ingestApiClient.delete(`/catalogs/aliases/${aliasId}`);
}

// ---- Ungrouped ----

export async function getUngrouped(
  itemType: GroupType,
  params?: { from?: string; to?: string; page?: number; size?: number; search?: string },
): Promise<UngroupedResponse> {
  const { data } = await ingestApiClient.get<UngroupedResponse>('/catalogs/ungrouped', {
    params: { item_type: itemType, ...params },
  });
  return data;
}

// ---- Dashboard ----

export async function getDashboardMetrics(): Promise<DashboardMetrics> {
  const { data } = await ingestApiClient.get<DashboardMetrics>('/dashboard/metrics');
  return data;
}

export async function getGroupTimeline(
  groupType: GroupType,
  from: string,
  to: string,
  timezone?: string,
  employeeGroupId?: string,
): Promise<GroupTimelineResponse> {
  const { data } = await ingestApiClient.get<GroupTimelineResponse>('/dashboard/group-timeline', {
    params: { group_type: groupType, from, to, timezone, employee_group_id: employeeGroupId },
  });
  return data;
}

export async function getGroupSummary(
  groupType: GroupType,
  from: string,
  to: string,
): Promise<GroupSummaryResponse> {
  const { data } = await ingestApiClient.get<GroupSummaryResponse>('/dashboard/group-summary', {
    params: { group_type: groupType, from, to },
  });
  return data;
}

export async function getTopUngrouped(
  itemType: GroupType,
  from?: string,
  to?: string,
  limit?: number,
): Promise<TopUngroupedResponse> {
  const { data } = await ingestApiClient.get<TopUngroupedResponse>('/dashboard/top-ungrouped', {
    params: { item_type: itemType, from, to, limit },
  });
  return data;
}

export async function getTopUsersUngrouped(
  itemType: GroupType,
  from?: string,
  to?: string,
  limit?: number,
): Promise<TopUsersUngroupedResponse> {
  const { data } = await ingestApiClient.get<TopUsersUngroupedResponse>(
    '/dashboard/top-users-ungrouped',
    { params: { item_type: itemType, from, to, limit } },
  );
  return data;
}

// ---- Seed ----

export async function seedCatalogs(
  groupType?: GroupType,
  force?: boolean,
): Promise<void> {
  await ingestApiClient.post('/catalogs/seed', null, {
    params: { group_type: groupType, force },
  });
}
