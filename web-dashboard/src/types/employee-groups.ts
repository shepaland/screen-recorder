export interface EmployeeGroup {
  id: string
  name: string
  description: string | null
  color: string | null
  sort_order: number
  member_count: number
  created_at: string
  updated_at: string
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
