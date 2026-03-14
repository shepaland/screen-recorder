import ingestApiClient from './ingest';
import type {
  EmployeeGroup,
  EmployeeGroupMember,
  EmployeeGroupCreateRequest,
  EmployeeGroupUpdateRequest,
  AssignEmployeeRequest,
  GroupMetricsResponse,
} from '../types/employee-groups';

export async function getEmployeeGroups(): Promise<EmployeeGroup[]> {
  const { data } = await ingestApiClient.get<EmployeeGroup[]>('/employee-groups');
  return data;
}

export async function createEmployeeGroup(request: EmployeeGroupCreateRequest): Promise<EmployeeGroup> {
  const { data } = await ingestApiClient.post<EmployeeGroup>('/employee-groups', request);
  return data;
}

export async function updateEmployeeGroup(groupId: string, request: EmployeeGroupUpdateRequest): Promise<EmployeeGroup> {
  const { data } = await ingestApiClient.put<EmployeeGroup>(`/employee-groups/${groupId}`, request);
  return data;
}

export async function deleteEmployeeGroup(groupId: string): Promise<void> {
  await ingestApiClient.delete(`/employee-groups/${groupId}`);
}

export async function getEmployeeGroupMembers(groupId: string): Promise<EmployeeGroupMember[]> {
  const { data } = await ingestApiClient.get<EmployeeGroupMember[]>(`/employee-groups/${groupId}/members`);
  return data;
}

export async function addEmployeeGroupMember(groupId: string, request: AssignEmployeeRequest): Promise<EmployeeGroupMember> {
  const { data } = await ingestApiClient.post<EmployeeGroupMember>(`/employee-groups/${groupId}/members`, request);
  return data;
}

export async function removeEmployeeGroupMember(memberId: string): Promise<void> {
  await ingestApiClient.delete(`/employee-groups/members/${memberId}`);
}

export async function getGroupMetrics(groupId?: string): Promise<GroupMetricsResponse> {
  const { data } = await ingestApiClient.get<GroupMetricsResponse>('/employee-groups/metrics', {
    params: groupId ? { group_id: groupId } : undefined,
  });
  return data;
}
