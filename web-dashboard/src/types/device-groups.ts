export interface DeviceGroupResponse {
  id: string;
  parent_id: string | null;
  name: string;
  description: string | null;
  color: string | null;
  sort_order: number;
  stats?: DeviceGroupStats;
  created_at: string;
  updated_at: string;
}

export interface DeviceGroupStats {
  total_devices: number;
  online_devices: number;
  total_video_gb: number;
}

export interface CreateDeviceGroupRequest {
  name: string;
  description?: string;
  color?: string;
  sort_order?: number;
  parent_id?: string | null;
}

export interface UpdateDeviceGroupRequest {
  name?: string;
  description?: string;
  color?: string;
  sort_order?: number;
}

export interface AssignDeviceGroupRequest {
  device_group_id: string | null;
}

export interface BulkAssignDevicesRequest {
  device_ids: string[];
}

export interface BulkAssignDevicesResponse {
  assigned: number;
  errors: string[];
}
