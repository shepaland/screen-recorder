import { useState, useEffect, useCallback, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import { MagnifyingGlassIcon } from '@heroicons/react/20/solid';
import DataTable, { type Column } from '../components/DataTable';
import DeviceStatusBadge from '../components/DeviceStatusBadge';
import PermissionGate from '../components/PermissionGate';
import ConfirmDialog from '../components/ConfirmDialog';
import { getDevices, deleteDevice, type DevicesListParams } from '../api/devices';
import type { DeviceResponse } from '../types';
import { useToast } from '../contexts/ToastContext';
import { timeAgo } from '../utils/timeAgo';
import { displayName } from '../utils/format';

const AUTO_REFRESH_INTERVAL = 10_000; // 10 seconds

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

  // Delete dialog
  const [deleteTarget, setDeleteTarget] = useState<DeviceResponse | null>(null);

  // Track whether this is a silent auto-refresh (don't show loading spinner)
  const isAutoRefresh = useRef(false);

  // Debounce search input
  useEffect(() => {
    const timer = setTimeout(() => {
      setSearch(searchInput);
      setPage(0);
    }, 300);
    return () => clearTimeout(timer);
  }, [searchInput]);

  const fetchDevices = useCallback(async () => {
    if (!isAutoRefresh.current) {
      setLoading(true);
    }
    try {
      const params: DevicesListParams = { page, size };
      if (search) params.search = search;
      if (statusFilter) params.status = statusFilter;

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
  }, [page, size, search, statusFilter, addToast]);

  useEffect(() => {
    fetchDevices();
  }, [fetchDevices]);

  // Auto-refresh every 10 seconds
  useEffect(() => {
    const interval = setInterval(() => {
      isAutoRefresh.current = true;
      fetchDevices();
    }, AUTO_REFRESH_INTERVAL);
    return () => clearInterval(interval);
  }, [fetchDevices]);

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
      render: (device) => <DeviceStatusBadge status={device.status} />,
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
          <PermissionGate permission="DEVICES:DELETE">
            {device.status === 'offline' && (
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
            )}
          </PermissionGate>
        </div>
      ),
    },
  ];

  const handleDelete = async () => {
    if (!deleteTarget) return;
    try {
      await deleteDevice(deleteTarget.id);
      addToast('success', `Устройство "${deleteTarget.hostname}" деактивировано`);
      fetchDevices();
    } catch {
      addToast('error', 'Не удалось удалить устройство');
    }
    setDeleteTarget(null);
  };

  return (
    <div>
      {/* Page header */}
      <div className="sm:flex sm:items-center sm:justify-between">
        <div>
          <h1 className="text-2xl font-bold tracking-tight text-gray-900">Устройства</h1>
          <p className="mt-2 text-sm text-gray-600">
            Мониторинг и управление зарегистрированными устройствами.
          </p>
        </div>
      </div>

      {/* Filters */}
      <div className="mt-6 flex flex-col gap-4 sm:flex-row sm:items-end">
        {/* Search */}
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

        {/* Status filter */}
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

      {/* Delete confirmation dialog */}
      <ConfirmDialog
        open={!!deleteTarget}
        title="Удалить устройство"
        message={`Вы уверены, что хотите удалить устройство "${deleteTarget?.hostname}"? Активные сессии записи будут прерваны, ожидающие команды отменены. Устройство будет деактивировано.`}
        confirmText="Удалить"
        cancelText="Отмена"
        onConfirm={handleDelete}
        onCancel={() => setDeleteTarget(null)}
      />
    </div>
  );
}
