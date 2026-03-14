import { useState, useEffect, useCallback, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import { getDevices, type DevicesListParams } from '../api/devices';
import {
  getDeviceGroups,
  createDeviceGroup,
  updateDeviceGroup,
  deleteDeviceGroup,
  assignDeviceToGroup,
} from '../api/device-groups';
import type { DeviceResponse } from '../types/device';
import type { DeviceGroupResponse, CreateDeviceGroupRequest, UpdateDeviceGroupRequest } from '../types/device-groups';
import DeviceStatusBadge from '../components/DeviceStatusBadge';
import DeviceGroupTree from '../components/devices/DeviceGroupTree';
import DeviceGroupStats from '../components/devices/DeviceGroupStats';
import DeviceGroupDialog from '../components/devices/DeviceGroupDialog';
import ConfirmDialog from '../components/ConfirmDialog';
import LoadingSpinner from '../components/LoadingSpinner';
import { useToast } from '../contexts/ToastContext';
import {
  ComputerDesktopIcon,
  ChevronLeftIcon,
  ChevronRightIcon,
  MagnifyingGlassIcon,
  EllipsisVerticalIcon,
} from '@heroicons/react/24/outline';

const PAGE_SIZE = 24;
const AUTO_REFRESH_INTERVAL = 10_000;

function getOsIcon(osVersion: string | null): { icon: string; label: string } {
  const ver = (osVersion || '').toLowerCase();
  if (ver.includes('windows')) return { icon: 'W', label: osVersion || 'Windows' };
  if (ver.includes('mac') || ver.includes('darwin')) return { icon: 'M', label: osVersion || 'macOS' };
  if (ver.includes('linux') || ver.includes('ubuntu')) return { icon: 'L', label: osVersion || 'Linux' };
  return { icon: 'W', label: osVersion || 'Unknown OS' };
}

function getOsIconColor(osVersion: string | null): string {
  const ver = (osVersion || '').toLowerCase();
  if (ver.includes('windows')) return 'bg-blue-600';
  if (ver.includes('mac') || ver.includes('darwin')) return 'bg-gray-600';
  if (ver.includes('linux') || ver.includes('ubuntu')) return 'bg-orange-600';
  return 'bg-gray-500';
}

export default function DeviceGridPage() {
  const navigate = useNavigate();
  const { addToast } = useToast();

  const [devices, setDevices] = useState<DeviceResponse[]>([]);
  const [loading, setLoading] = useState(true);
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [totalElements, setTotalElements] = useState(0);
  const [searchInput, setSearchInput] = useState('');
  const [search, setSearch] = useState('');

  // Groups
  const [groups, setGroups] = useState<DeviceGroupResponse[]>([]);
  const [selectedGroupId, setSelectedGroupId] = useState<string | null>(null);

  // Sidebar counts — always reflect real totals, independent of current filter
  const [allDevicesCount, setAllDevicesCount] = useState(0);
  const [ungroupedCount, setUngroupedCount] = useState(0);

  // Group dialog
  const [groupDialogOpen, setGroupDialogOpen] = useState(false);
  const [editingGroup, setEditingGroup] = useState<DeviceGroupResponse | null>(null);
  const [createParentId, setCreateParentId] = useState<string | null>(null);
  const [deleteGroupTarget, setDeleteGroupTarget] = useState<DeviceGroupResponse | null>(null);

  // Context menu
  const [contextMenu, setContextMenu] = useState<{ device: DeviceResponse; x: number; y: number } | null>(null);

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
      // silently fail
    }
  }, []);

  // Fetch sidebar counts (all devices + ungrouped) — independent of current filter
  const fetchSidebarCounts = useCallback(async () => {
    try {
      const [allResp, ungroupedResp] = await Promise.all([
        getDevices({ page: 0, size: 1, include_deleted: false }),
        getDevices({ page: 0, size: 1, include_deleted: false, device_group_id: 'ungrouped' }),
      ]);
      setAllDevicesCount(allResp.total_elements);
      setUngroupedCount(ungroupedResp.total_elements);
    } catch {
      // silently fail
    }
  }, []);

  useEffect(() => {
    fetchGroups();
    fetchSidebarCounts();
  }, [fetchGroups, fetchSidebarCounts]);

  // Fetch devices
  const fetchDevices = useCallback(async () => {
    if (!isAutoRefresh.current) setLoading(true);
    try {
      const params: DevicesListParams = { page, size: PAGE_SIZE, include_deleted: false };
      if (search) params.search = search;
      if (selectedGroupId === 'ungrouped') {
        params.device_group_id = 'ungrouped';
      } else if (selectedGroupId) {
        params.device_group_id = selectedGroupId;
      }
      const resp = await getDevices(params);
      setDevices(resp.content);
      setTotalPages(resp.total_pages);
      setTotalElements(resp.total_elements);
    } catch {
      if (!isAutoRefresh.current) {
        addToast('error', 'Не удалось загрузить устройства');
      }
    } finally {
      setLoading(false);
      isAutoRefresh.current = false;
    }
  }, [page, search, selectedGroupId, addToast]);

  useEffect(() => {
    fetchDevices();
  }, [fetchDevices]);

  useEffect(() => {
    const interval = setInterval(() => {
      isAutoRefresh.current = true;
      fetchDevices();
      fetchSidebarCounts();
    }, AUTO_REFRESH_INTERVAL);
    return () => clearInterval(interval);
  }, [fetchDevices, fetchSidebarCounts]);

  // Compute stats from loaded devices
  const onlineCount = devices.filter((d) => d.status === 'online').length;
  const recordingCount = devices.filter((d) => d.status === 'recording').length;

  const handleDeviceClick = (deviceId: string) => {
    navigate(`/archive/devices/${deviceId}`);
  };

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
      if (selectedGroupId === deleteGroupTarget.id) setSelectedGroupId(null);
      fetchGroups();
      fetchDevices();
      fetchSidebarCounts();
    } catch {
      addToast('error', 'Не удалось удалить группу');
    }
    setDeleteGroupTarget(null);
  };

  // Assign device to group
  const handleAssignToGroup = async (deviceId: string, groupId: string | null) => {
    try {
      await assignDeviceToGroup(deviceId, groupId);
      setContextMenu(null);
      fetchDevices();
      fetchGroups();
      fetchSidebarCounts();
    } catch {
      addToast('error', 'Не удалось назначить группу');
    }
  };

  // Close context menu on click outside
  useEffect(() => {
    if (!contextMenu) return;
    const handler = () => setContextMenu(null);
    document.addEventListener('click', handler);
    return () => document.removeEventListener('click', handler);
  }, [contextMenu]);

  const rootGroups = groups.filter((g) => g.parent_id === null);

  // Build flat list of leaf groups for context menu
  const leafGroups: { id: string; name: string; parentName?: string }[] = [];
  for (const g of groups) {
    const children = groups.filter((c) => c.parent_id === g.id);
    if (children.length > 0) {
      for (const c of children) {
        leafGroups.push({ id: c.id, name: c.name, parentName: g.name });
      }
    } else if (g.parent_id === null) {
      leafGroups.push({ id: g.id, name: g.name });
    }
  }

  return (
    <div className="flex h-[calc(100vh-4rem)]">
      {/* Sidebar with group tree */}
      <DeviceGroupTree
        groups={groups}
        selectedGroupId={selectedGroupId}
        onSelectAll={() => { setSelectedGroupId(null); setPage(0); }}
        onSelectGroup={(id) => { setSelectedGroupId(id); setPage(0); }}
        onSelectUngrouped={() => { setSelectedGroupId('ungrouped'); setPage(0); }}
        onCreateGroup={(parentId) => handleCreateGroup(parentId)}
        onEditGroup={handleEditGroup}
        onDeleteGroup={(group) => setDeleteGroupTarget(group)}
        totalDevices={allDevicesCount}
        ungroupedDevices={ungroupedCount}
      />

      {/* Main content */}
      <div className="flex-1 overflow-y-auto p-6 space-y-6">
        {/* Header */}
        <div className="flex items-center justify-between">
          <div>
            <h1 className="text-2xl font-bold text-gray-900">Устройства</h1>
            <p className="mt-1 text-sm text-gray-500">
              Выберите устройство для просмотра записей
            </p>
          </div>
        </div>

        {/* Stats cards */}
        <DeviceGroupStats
          totalDevices={totalElements}
          onlineDevices={onlineCount}
          recordingDevices={recordingCount}
        />

        {/* Search */}
        <div className="max-w-md">
          <div className="relative">
            <MagnifyingGlassIcon className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 h-5 w-5 text-gray-400" />
            <input
              type="text"
              placeholder="Поиск по hostname..."
              value={searchInput}
              onChange={(e) => setSearchInput(e.target.value)}
              className="block w-full rounded-lg border border-gray-300 py-2 pl-10 pr-3 text-sm placeholder:text-gray-400 focus:border-red-500 focus:ring-1 focus:ring-red-500"
            />
          </div>
        </div>

        {/* Grid */}
        {loading && devices.length === 0 ? (
          <div className="flex items-center justify-center h-64">
            <LoadingSpinner size="lg" />
          </div>
        ) : devices.length === 0 ? (
          <div className="text-center py-12">
            <ComputerDesktopIcon className="mx-auto h-12 w-12 text-gray-400" />
            <h3 className="mt-2 text-sm font-semibold text-gray-900">Нет устройств</h3>
            <p className="mt-1 text-sm text-gray-500">
              Зарегистрируйте устройство для начала записи
            </p>
          </div>
        ) : (
          <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-6 xl:grid-cols-8 gap-4">
            {devices.map((device) => {
              const osInfo = getOsIcon(device.os_version);
              const osColor = getOsIconColor(device.os_version);
              return (
                <div
                  key={device.id}
                  onClick={() => handleDeviceClick(device.id)}
                  className="relative flex flex-col items-center p-4 bg-white rounded-xl border border-gray-200 shadow-sm hover:shadow-md hover:border-red-300 transition-all cursor-pointer group"
                >
                  {/* 3-dot menu button */}
                  <button
                    onClick={(e) => {
                      e.stopPropagation();
                      e.preventDefault();
                      const rect = e.currentTarget.getBoundingClientRect();
                      setContextMenu({ device, x: rect.right, y: rect.bottom + 4 });
                    }}
                    className="absolute top-1.5 left-1.5 p-1 rounded-full text-gray-400 hover:text-gray-700 hover:bg-gray-200 transition-colors z-10"
                    title="Действия"
                  >
                    <EllipsisVerticalIcon className="h-5 w-5" />
                  </button>

                  {/* Status indicator dot */}
                  <div
                    className={`absolute top-2 right-2 w-2.5 h-2.5 rounded-full ${
                      device.status === 'recording'
                        ? 'bg-green-500 animate-pulse'
                        : device.status === 'online'
                        ? 'bg-yellow-500'
                        : device.status === 'error'
                        ? 'bg-red-500'
                        : 'bg-gray-400'
                    }`}
                  />

                  {/* Computer icon */}
                  <div className="relative mb-3">
                    <ComputerDesktopIcon className="h-14 w-14 text-gray-400 group-hover:text-red-500 transition-colors" />
                    <span
                      className={`absolute -bottom-1 -right-1 w-6 h-6 rounded-full ${osColor} text-white text-xs font-bold flex items-center justify-center`}
                    >
                      {osInfo.icon}
                    </span>
                  </div>

                  {/* Hostname */}
                  <p className="text-sm font-medium text-gray-900 text-center truncate w-full" title={device.hostname}>
                    {device.hostname}
                  </p>

                  {/* OS version */}
                  <p className="text-xs text-gray-500 truncate w-full text-center mt-0.5" title={osInfo.label}>
                    {osInfo.label}
                  </p>

                  {/* Status badge */}
                  <div className="mt-2">
                    <DeviceStatusBadge status={device.status} />
                  </div>

                  {/* Group name */}
                  {device.device_group_name && (
                    <p className="text-[10px] text-gray-400 mt-1 truncate w-full text-center" title={device.device_group_name}>
                      {device.device_group_name}
                    </p>
                  )}
                </div>
              );
            })}
          </div>
        )}

        {/* Pagination */}
        {totalPages > 1 && (
          <div className="flex items-center justify-center gap-4 pt-4">
            <button
              onClick={() => setPage(Math.max(0, page - 1))}
              disabled={page === 0}
              className="flex items-center gap-1 px-3 py-2 text-sm font-medium text-gray-700 bg-white border border-gray-300 rounded-md hover:bg-gray-50 disabled:opacity-50 disabled:cursor-not-allowed"
            >
              <ChevronLeftIcon className="h-4 w-4" />
              Назад
            </button>
            <span className="text-sm text-gray-700">
              Страница {page + 1} из {totalPages}
            </span>
            <button
              onClick={() => setPage(Math.min(totalPages - 1, page + 1))}
              disabled={page >= totalPages - 1}
              className="flex items-center gap-1 px-3 py-2 text-sm font-medium text-gray-700 bg-white border border-gray-300 rounded-md hover:bg-gray-50 disabled:opacity-50 disabled:cursor-not-allowed"
            >
              Далее
              <ChevronRightIcon className="h-4 w-4" />
            </button>
          </div>
        )}
      </div>

      {/* Device context menu */}
      {contextMenu && (
        <div
          className="fixed z-50 bg-white rounded-lg shadow-lg border border-gray-200 py-1 min-w-[200px] max-h-[350px] overflow-y-auto"
          style={{
            left: Math.min(contextMenu.x, window.innerWidth - 220),
            top: Math.min(contextMenu.y, window.innerHeight - 350),
          }}
          onClick={(e) => e.stopPropagation()}
        >
          {contextMenu.device.device_group_id ? (
            <>
              <div className="px-3 py-1.5 text-xs font-medium text-gray-500 uppercase">
                Перенести в...
              </div>
              {leafGroups
                .filter((g) => g.id !== contextMenu.device.device_group_id)
                .map((g) => (
                  <button
                    key={g.id}
                    onClick={() => handleAssignToGroup(contextMenu.device.id, g.id)}
                    className="w-full flex items-center gap-2 px-3 py-1.5 text-sm text-gray-700 hover:bg-gray-100"
                  >
                    {g.parentName ? `${g.parentName} / ${g.name}` : g.name}
                  </button>
                ))}
              <div className="border-t border-gray-200 my-1" />
              <button
                onClick={() => handleAssignToGroup(contextMenu.device.id, null)}
                className="w-full flex items-center gap-2 px-3 py-1.5 text-sm text-red-600 hover:bg-red-50"
              >
                Удалить из группы
              </button>
            </>
          ) : (
            <>
              <div className="px-3 py-1.5 text-xs font-medium text-gray-500 uppercase">
                Добавить в группу
              </div>
              {leafGroups.map((g) => (
                <button
                  key={g.id}
                  onClick={() => handleAssignToGroup(contextMenu.device.id, g.id)}
                  className="w-full flex items-center gap-2 px-3 py-1.5 text-sm text-gray-700 hover:bg-gray-100"
                >
                  {g.parentName ? `${g.parentName} / ${g.name}` : g.name}
                </button>
              ))}
            </>
          )}
        </div>
      )}

      {/* Group create/edit dialog */}
      <DeviceGroupDialog
        isOpen={groupDialogOpen}
        onClose={() => setGroupDialogOpen(false)}
        onSubmit={handleGroupDialogSubmit}
        editGroup={editingGroup}
        parentId={createParentId}
        rootGroups={rootGroups}
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
