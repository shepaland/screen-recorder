import { useState, useEffect, useCallback, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import { MagnifyingGlassIcon } from '@heroicons/react/20/solid';
import DataTable, { type Column } from '../components/DataTable';
import DeviceStatusBadge from '../components/DeviceStatusBadge';
import PermissionGate from '../components/PermissionGate';
import ConfirmDialog from '../components/ConfirmDialog';
import DeviceGroupTree from '../components/devices/DeviceGroupTree';
import DeviceGroupStats from '../components/devices/DeviceGroupStats';
import DeviceGroupDialog from '../components/devices/DeviceGroupDialog';
import AssignGroupDropdown from '../components/devices/AssignGroupDropdown';
import { getDevices, deleteDevice, restoreDevice, type DevicesListParams } from '../api/devices';
import {
  getDeviceGroups,
  createDeviceGroup,
  updateDeviceGroup,
  deleteDeviceGroup,
  assignDeviceToGroup,
} from '../api/device-groups';
import type { DeviceResponse } from '../types';
import type { DeviceGroupResponse, CreateDeviceGroupRequest, UpdateDeviceGroupRequest } from '../types/device-groups';
import { useToast } from '../contexts/ToastContext';
import { timeAgo } from '../utils/timeAgo';
import { displayName } from '../utils/format';

const AUTO_REFRESH_INTERVAL = 10_000;

export default function DevicesListPage() {
  const navigate = useNavigate();
  const { addToast } = useToast();

  const [devices, setDevices] = useState<DeviceResponse[]>([]);
  const [loading, setLoading] = useState(true);
  const [page, setPage] = useState(0);
  const [totalElements, setTotalElements] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [size] = useState(20);

  // Filters
  const [search, setSearch] = useState('');
  const [searchInput, setSearchInput] = useState('');
  const [statusFilter, setStatusFilter] = useState<string>('');

  // Groups
  const [groups, setGroups] = useState<DeviceGroupResponse[]>([]);
  const [selectedGroupId, setSelectedGroupId] = useState<string | null>(null); // null=all, 'ungrouped', or UUID

  // Group dialog
  const [groupDialogOpen, setGroupDialogOpen] = useState(false);
  const [editingGroup, setEditingGroup] = useState<DeviceGroupResponse | null>(null);
  const [createParentId, setCreateParentId] = useState<string | null>(null);

  // Delete dialogs
  const [deleteTarget, setDeleteTarget] = useState<DeviceResponse | null>(null);
  const [deleteGroupTarget, setDeleteGroupTarget] = useState<DeviceGroupResponse | null>(null);

  const isAutoRefresh = useRef(false);

  // Debounce search
  useEffect(() => {
    const timer = setTimeout(() => {
      setSearch(searchInput);
      setPage(0);
    }, 300);
    return () => clearTimeout(timer);
  }, [searchInput]);

  // Fetch groups
  const fetchGroups = useCallback(async () => {
    try {
      const data = await getDeviceGroups(true);
      setGroups(data);
    } catch {
      // silently fail for groups
    }
  }, []);

  useEffect(() => {
    fetchGroups();
  }, [fetchGroups]);

  // Fetch devices
  const fetchDevices = useCallback(async () => {
    if (!isAutoRefresh.current) setLoading(true);
    try {
      const params: DevicesListParams = { page, size };
      if (search) params.search = search;
      if (statusFilter === 'deleted') {
        params.status = 'deleted';
        params.include_deleted = true;
      } else if (statusFilter) {
        params.status = statusFilter;
      }
      if (selectedGroupId === 'ungrouped') {
        params.device_group_id = 'ungrouped';
      } else if (selectedGroupId) {
        params.device_group_id = selectedGroupId;
      }

      const data = await getDevices(params);
      setDevices(data.content);
      setTotalElements(data.total_elements);
      setTotalPages(data.total_pages);
    } catch {
      if (!isAutoRefresh.current) {
        addToast('error', 'Не удалось загрузить устройства');
      }
    } finally {
      setLoading(false);
      isAutoRefresh.current = false;
    }
  }, [page, size, search, statusFilter, selectedGroupId, addToast]);

  useEffect(() => {
    fetchDevices();
  }, [fetchDevices]);

  useEffect(() => {
    const interval = setInterval(() => {
      isAutoRefresh.current = true;
      fetchDevices();
    }, AUTO_REFRESH_INTERVAL);
    return () => clearInterval(interval);
  }, [fetchDevices]);

  // Compute stats for selected group
  const computedStats = (() => {
    if (selectedGroupId === null) {
      // All devices
      const online = groups.reduce((sum, g) => {
        if (g.parent_id === null) return sum + (g.stats?.online_devices ?? 0);
        return sum;
      }, 0);
      return { totalDevices: totalElements, onlineDevices: online, totalVideoGb: 0 };
    }
    if (selectedGroupId === 'ungrouped') {
      return { totalDevices: totalElements, onlineDevices: 0, totalVideoGb: 0 };
    }
    const group = groups.find((g) => g.id === selectedGroupId);
    if (group?.stats) {
      return {
        totalDevices: group.stats.total_devices,
        onlineDevices: group.stats.online_devices,
        totalVideoGb: group.stats.total_video_gb,
      };
    }
    return { totalDevices: totalElements, onlineDevices: 0, totalVideoGb: 0 };
  })();

  // Total devices across all groups (for sidebar "All" count)
  // We use the sum of root group stats + ungrouped
  const allDevicesCount = totalElements;

  // Group CRUD handlers
  const handleCreateGroup = (parentId?: string) => {
    setEditingGroup(null);
    setCreateParentId(parentId ?? null);
    setGroupDialogOpen(true);
  };

  const handleEditGroup = (group: DeviceGroupResponse) => {
    setEditingGroup(group);
    setCreateParentId(null);
    setGroupDialogOpen(true);
  };

  const handleGroupDialogSubmit = async (data: CreateDeviceGroupRequest | UpdateDeviceGroupRequest) => {
    try {
      if (editingGroup) {
        await updateDeviceGroup(editingGroup.id, data as UpdateDeviceGroupRequest);
        addToast('success', 'Группа обновлена');
      } else {
        await createDeviceGroup(data as CreateDeviceGroupRequest);
        addToast('success', 'Группа создана');
      }
      setGroupDialogOpen(false);
      fetchGroups();
    } catch (err: unknown) {
      const msg =
        err && typeof err === 'object' && 'response' in err
          ? ((err as { response?: { data?: { error?: string } } }).response?.data?.error ?? 'Ошибка')
          : 'Ошибка';
      addToast('error', msg);
    }
  };

  const handleDeleteGroup = async () => {
    if (!deleteGroupTarget) return;
    try {
      await deleteDeviceGroup(deleteGroupTarget.id);
      addToast('success', `Группа "${deleteGroupTarget.name}" удалена`);
      if (selectedGroupId === deleteGroupTarget.id) {
        setSelectedGroupId(null);
      }
      fetchGroups();
      fetchDevices();
    } catch {
      addToast('error', 'Не удалось удалить группу');
    }
    setDeleteGroupTarget(null);
  };

  const handleAssignGroup = async (deviceId: string, groupId: string | null) => {
    try {
      await assignDeviceToGroup(deviceId, groupId);
      fetchDevices();
      fetchGroups();
    } catch {
      addToast('error', 'Не удалось назначить группу');
    }
  };

  const columns: Column<DeviceResponse>[] = [
    {
      key: 'hostname',
      title: 'Hostname',
      render: (device) => (
        <span className="font-medium text-gray-900">{device.hostname}</span>
      ),
    },
    {
      key: 'os_version',
      title: 'ОС',
      render: (device) => (
        <span className="text-gray-900">{device.os_version || '--'}</span>
      ),
    },
    {
      key: 'agent_version',
      title: 'Агент',
      render: (device) => (
        <code className="rounded bg-gray-100 px-1.5 py-0.5 text-xs font-mono text-gray-700">
          {device.agent_version || '--'}
        </code>
      ),
    },
    {
      key: 'status',
      title: 'Статус',
      render: (device) =>
        device.is_deleted ? (
          <span className="inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-medium bg-red-100 text-red-800 ring-1 ring-inset ring-red-600/20">
            Удалено
          </span>
        ) : (
          <DeviceStatusBadge status={device.status} />
        ),
    },
    {
      key: 'group',
      title: 'Группа',
      render: (device) => (
        <PermissionGate permission="DEVICES:MANAGE" fallback={
          <span className="text-xs text-gray-500">{device.device_group_name || 'Без группы'}</span>
        }>
          <AssignGroupDropdown
            groups={groups}
            currentGroupId={device.device_group_id}
            onAssign={(groupId) => handleAssignGroup(device.id, groupId)}
          />
        </PermissionGate>
      ),
    },
    {
      key: 'ip_address',
      title: 'IP',
      render: (device) => (
        <span className="font-mono text-xs text-gray-700">{device.ip_address || '--'}</span>
      ),
    },
    {
      key: 'last_heartbeat_ts',
      title: 'Последний heartbeat',
      render: (device) => (
        <span className="text-gray-600">{timeAgo(device.last_heartbeat_ts)}</span>
      ),
    },
    {
      key: 'user',
      title: 'Оператор',
      render: (device) => {
        if (!device.user) return <span className="text-gray-400">--</span>;
        return (
          <span className="text-gray-900">
            {displayName(device.user.first_name, device.user.last_name, device.user.username)}
          </span>
        );
      },
    },
    {
      key: 'actions',
      title: 'Действия',
      render: (device) => (
        <div className="flex items-center gap-3">
          <button
            type="button"
            onClick={(e) => {
              e.stopPropagation();
              navigate(`/devices/${device.id}`);
            }}
            className="text-sm text-indigo-600 hover:text-indigo-800"
          >
            Детали
          </button>
          {device.is_deleted ? (
            <PermissionGate permission="DEVICES:UPDATE">
              <button
                type="button"
                onClick={(e) => {
                  e.stopPropagation();
                  handleRestore(device);
                }}
                className="text-sm text-green-600 hover:text-green-800"
              >
                Восстановить
              </button>
            </PermissionGate>
          ) : (
            <PermissionGate permission="DEVICES:DELETE">
              <button
                type="button"
                onClick={(e) => {
                  e.stopPropagation();
                  setDeleteTarget(device);
                }}
                className="text-sm text-red-600 hover:text-red-800"
              >
                Удалить
              </button>
            </PermissionGate>
          )}
        </div>
      ),
    },
  ];

  const handleDelete = async () => {
    if (!deleteTarget) return;
    try {
      await deleteDevice(deleteTarget.id);
      addToast('success', `Устройство "${deleteTarget.hostname}" удалено`);
      fetchDevices();
    } catch {
      addToast('error', 'Не удалось удалить устройство');
    }
    setDeleteTarget(null);
  };

  const handleRestore = async (device: DeviceResponse) => {
    try {
      await restoreDevice(device.id);
      addToast('success', 'Устройство восстановлено');
      fetchDevices();
    } catch {
      addToast('error', 'Не удалось восстановить устройство');
    }
  };

  const rootGroups = groups.filter((g) => g.parent_id === null);

  return (
    <div className="flex h-full">
      {/* Sidebar with group tree */}
      <DeviceGroupTree
        groups={groups}
        selectedGroupId={selectedGroupId}
        onSelectAll={() => {
          setSelectedGroupId(null);
          setPage(0);
        }}
        onSelectGroup={(id) => {
          setSelectedGroupId(id);
          setPage(0);
        }}
        onSelectUngrouped={() => {
          setSelectedGroupId('ungrouped');
          setPage(0);
        }}
        onCreateGroup={(parentId) => handleCreateGroup(parentId)}
        onEditGroup={handleEditGroup}
        onDeleteGroup={(group) => setDeleteGroupTarget(group)}
        totalDevices={allDevicesCount}
      />

      {/* Main content */}
      <div className="flex-1 overflow-auto p-6">
        {/* Page header */}
        <div className="sm:flex sm:items-center sm:justify-between">
          <div>
            <h1 className="text-2xl font-bold tracking-tight text-gray-900">Устройства</h1>
            <p className="mt-2 text-sm text-gray-600">
              Мониторинг и управление зарегистрированными устройствами.
            </p>
          </div>
        </div>

        {/* Stats cards */}
        <div className="mt-6">
          <DeviceGroupStats
            totalDevices={computedStats.totalDevices}
            onlineDevices={computedStats.onlineDevices}
            totalVideoGb={computedStats.totalVideoGb}
          />
        </div>

        {/* Filters */}
        <div className="flex flex-col gap-4 sm:flex-row sm:items-end">
          <div className="flex-1">
            <label htmlFor="deviceSearch" className="label">
              Поиск
            </label>
            <div className="relative mt-1">
              <div className="pointer-events-none absolute inset-y-0 left-0 flex items-center pl-3">
                <MagnifyingGlassIcon className="h-5 w-5 text-gray-400" aria-hidden="true" />
              </div>
              <input
                id="deviceSearch"
                type="text"
                placeholder="Поиск по hostname..."
                value={searchInput}
                onChange={(e) => setSearchInput(e.target.value)}
                className="input-field pl-10"
              />
            </div>
          </div>

          <div className="sm:w-48">
            <label htmlFor="deviceStatus" className="label">
              Статус
            </label>
            <select
              id="deviceStatus"
              value={statusFilter}
              onChange={(e) => {
                setStatusFilter(e.target.value);
                setPage(0);
              }}
              className="input-field mt-1"
            >
              <option value="">Все</option>
              <option value="online">Онлайн</option>
              <option value="recording">Запись</option>
              <option value="offline">Офлайн</option>
              <option value="error">Ошибка</option>
              <option value="deleted">Удалённые</option>
            </select>
          </div>
        </div>

        {/* Table */}
        <div className="mt-6">
          <DataTable<DeviceResponse>
            columns={columns}
            data={devices}
            loading={loading}
            page={page}
            size={size}
            totalElements={totalElements}
            totalPages={totalPages}
            onPageChange={setPage}
            onRowClick={(device) => navigate(`/devices/${device.id}`)}
            keyExtractor={(device) => device.id}
            emptyMessage="Устройства не найдены"
          />
        </div>
      </div>

      {/* Group create/edit dialog */}
      <DeviceGroupDialog
        isOpen={groupDialogOpen}
        onClose={() => setGroupDialogOpen(false)}
        onSubmit={handleGroupDialogSubmit}
        editGroup={editingGroup}
        parentId={createParentId}
        rootGroups={rootGroups}
      />

      {/* Delete device confirmation */}
      <ConfirmDialog
        open={!!deleteTarget}
        title="Удалить устройство"
        message={`Вы уверены, что хотите удалить устройство "${deleteTarget?.hostname}"?\n\nУстройство будет скрыто из списка. Активные сессии записи будут прерваны.\n\nЕсли агент продолжает работу, устройство восстановится автоматически.`}
        confirmText="Удалить"
        cancelText="Отмена"
        onConfirm={handleDelete}
        onCancel={() => setDeleteTarget(null)}
      />

      {/* Delete group confirmation */}
      <ConfirmDialog
        open={!!deleteGroupTarget}
        title="Удалить группу"
        message={`Вы уверены, что хотите удалить группу "${deleteGroupTarget?.name}"?\n\nВсе подгруппы будут удалены. Устройства из удалённых групп станут "без группы".`}
        confirmText="Удалить"
        cancelText="Отмена"
        onConfirm={handleDeleteGroup}
        onCancel={() => setDeleteGroupTarget(null)}
      />
    </div>
  );
}
