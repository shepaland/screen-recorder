export interface EmployeeGroup {
  id: string
  parent_id?: string | null
  name: string
  description: string | null
  color: string | null
  sort_order: number
  member_count: number
  total_member_count?: number
  children?: EmployeeGroup[]
  created_at: string
  updated_at: string
}

export interface GroupMetricsResponse {
  total_employees: number
  active_employees: number
}

export interface EmployeeGroupMember {
  id: string
  group_id: string
  username: string
  created_at: string
}

export interface EmployeeGroupCreateRequest {
  name: string
  description?: string
  color?: string
  sort_order?: number
  parent_id?: string
}

export interface EmployeeGroupUpdateRequest {
  name?: string
  description?: string
  color?: string
  sort_order?: number
}

export interface AssignEmployeeRequest {
  username: string
}
