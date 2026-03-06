import { useState, useEffect, useCallback, useRef, Fragment } from 'react';
import { useNavigate } from 'react-router-dom';
import { Dialog, Transition } from '@headlessui/react';
import { MagnifyingGlassIcon } from '@heroicons/react/20/solid';
import { XMarkIcon, Cog6ToothIcon } from '@heroicons/react/24/outline';
import DataTable, { type Column } from '../components/DataTable';
import DeviceStatusBadge from '../components/DeviceStatusBadge';
import DeviceSettingsForm from '../components/DeviceSettingsForm';
import { getDevices, updateDeviceSettings, type DevicesListParams } from '../api/devices';
import type { DeviceResponse, DeviceSettings } from '../types';
import { useToast } from '../contexts/ToastContext';
import { usePermissions } from '../hooks/usePermissions';

const RESOLUTION_LABELS: Record<string, string> = {
  '720p': '720p',
  '1080p': '1080p',
  native: 'Нативное',
};

const QUALITY_LABELS: Record<string, string> = {
  low: 'Низкое',
  medium: 'Среднее',
  high: 'Высокое',
};

export default function RecordingSettingsPage() {
  const navigate = useNavigate();
  const { addToast } = useToast();
  const { hasPermission } = usePermissions();
  const canUpdate = hasPermission('DEVICES:UPDATE');

  const [devices, setDevices] = useState<DeviceResponse[]>([]);
  const [loading, setLoading] = useState(true);
  const [page, setPage] = useState(0);
  const [totalElements, setTotalElements] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [size] = useState(20);

  // Search
  const [search, setSearch] = useState('');
  const [searchInput, setSearchInput] = useState('');

  // Settings modal
  const [editingDevice, setEditingDevice] = useState<DeviceResponse | null>(null);

  const isAutoRefresh = useRef(false);

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
  }, [page, size, search, addToast]);

  useEffect(() => {
    fetchDevices();
  }, [fetchDevices]);

  const handleSaveSettings = async (settings: DeviceSettings) => {
    if (!editingDevice) return;
    try {
      await updateDeviceSettings(editingDevice.id, settings);
      addToast('success', `Настройки записи для "${editingDevice.hostname}" сохранены`);
      setEditingDevice(null);
      fetchDevices();
    } catch {
      addToast('error', 'Не удалось сохранить настройки');
    }
  };

  const getSettingsSummary = (device: DeviceResponse) => {
    const s = device.settings as Partial<DeviceSettings> | null;
    if (!s || Object.keys(s).length === 0) {
      return 'По умолчанию';
    }
    const parts: string[] = [];
    if (s.capture_fps != null) parts.push(`${s.capture_fps} FPS`);
    if (s.resolution) parts.push(RESOLUTION_LABELS[s.resolution] || s.resolution);
    if (s.quality) parts.push(QUALITY_LABELS[s.quality] || s.quality);
    return parts.join(', ') || 'По умолчанию';
  };

  const columns: Column<DeviceResponse>[] = [
    {
      key: 'hostname',
      title: 'Устройство',
      render: (device) => (
        <div>
          <span className="font-medium text-gray-900">{device.hostname}</span>
          <p className="text-xs text-gray-500 mt-0.5">{device.os_version || '--'}</p>
        </div>
      ),
    },
    {
      key: 'status',
      title: 'Статус',
      render: (device) => <DeviceStatusBadge status={device.status} />,
    },
    {
      key: 'settings_summary',
      title: 'Настройки записи',
      render: (device) => {
        const s = device.settings as Partial<DeviceSettings> | null;
        const hasSettings = s && Object.keys(s).length > 0;
        return (
          <div>
            <span className={`text-sm ${hasSettings ? 'text-gray-900' : 'text-gray-400'}`}>
              {getSettingsSummary(device)}
            </span>
            {hasSettings && s?.auto_start != null && (
              <p className="text-xs text-gray-500 mt-0.5">
                Автостарт: {s.auto_start ? 'Да' : 'Нет'}
              </p>
            )}
          </div>
        );
      },
    },
    {
      key: 'segment_duration',
      title: 'Сегмент',
      render: (device) => {
        const s = device.settings as Partial<DeviceSettings> | null;
        return (
          <span className="text-sm text-gray-700">
            {s?.segment_duration_sec ? `${s.segment_duration_sec} сек` : '--'}
          </span>
        );
      },
    },
    {
      key: 'session_max',
      title: 'Макс. сессия',
      render: (device) => {
        const s = device.settings as Partial<DeviceSettings> | null;
        return (
          <span className="text-sm text-gray-700">
            {s?.session_max_duration_hours ? `${s.session_max_duration_hours} ч` : '--'}
          </span>
        );
      },
    },
    {
      key: 'actions',
      title: '',
      render: (device) => (
        <div className="flex items-center gap-3">
          {canUpdate && (
            <button
              type="button"
              onClick={(e) => {
                e.stopPropagation();
                setEditingDevice(device);
              }}
              className="inline-flex items-center text-sm text-indigo-600 hover:text-indigo-800"
            >
              <Cog6ToothIcon className="h-4 w-4 mr-1" />
              Настроить
            </button>
          )}
          <button
            type="button"
            onClick={(e) => {
              e.stopPropagation();
              navigate(`/devices/${device.id}`);
            }}
            className="text-sm text-gray-500 hover:text-gray-700"
          >
            Детали
          </button>
        </div>
      ),
    },
  ];

  return (
    <div>
      {/* Header */}
      <div className="sm:flex sm:items-center sm:justify-between">
        <div>
          <h1 className="text-2xl font-bold tracking-tight text-gray-900">
            Настройки записи
          </h1>
          <p className="mt-2 text-sm text-gray-600">
            Конфигурация параметров записи для каждого устройства.
          </p>
        </div>
      </div>

      {/* Search */}
      <div className="mt-6 flex flex-col gap-4 sm:flex-row sm:items-end">
        <div className="flex-1">
          <label htmlFor="settingsSearch" className="label">
            Поиск
          </label>
          <div className="relative mt-1">
            <div className="pointer-events-none absolute inset-y-0 left-0 flex items-center pl-3">
              <MagnifyingGlassIcon className="h-5 w-5 text-gray-400" aria-hidden="true" />
            </div>
            <input
              id="settingsSearch"
              type="text"
              placeholder="Поиск по hostname..."
              value={searchInput}
              onChange={(e) => setSearchInput(e.target.value)}
              className="input-field pl-10"
            />
          </div>
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
          onRowClick={(device) => canUpdate ? setEditingDevice(device) : navigate(`/devices/${device.id}`)}
          keyExtractor={(device) => device.id}
          emptyMessage="Устройства не найдены"
        />
      </div>

      {/* Settings modal */}
      <Transition.Root show={!!editingDevice} as={Fragment}>
        <Dialog
          as="div"
          className="relative z-50"
          onClose={() => setEditingDevice(null)}
        >
          <Transition.Child
            as={Fragment}
            enter="ease-out duration-300"
            enterFrom="opacity-0"
            enterTo="opacity-100"
            leave="ease-in duration-200"
            leaveFrom="opacity-100"
            leaveTo="opacity-0"
          >
            <div className="fixed inset-0 bg-gray-500 bg-opacity-75 transition-opacity" />
          </Transition.Child>

          <div className="fixed inset-0 z-10 w-screen overflow-y-auto">
            <div className="flex min-h-full items-end justify-center p-4 text-center sm:items-center sm:p-0">
              <Transition.Child
                as={Fragment}
                enter="ease-out duration-300"
                enterFrom="opacity-0 translate-y-4 sm:translate-y-0 sm:scale-95"
                enterTo="opacity-100 translate-y-0 sm:scale-100"
                leave="ease-in duration-200"
                leaveFrom="opacity-100 translate-y-0 sm:scale-100"
                leaveTo="opacity-0 translate-y-4 sm:translate-y-0 sm:scale-95"
              >
                <Dialog.Panel className="relative transform overflow-hidden rounded-lg bg-white px-4 pb-4 pt-5 text-left shadow-xl transition-all sm:my-8 sm:w-full sm:max-w-lg sm:p-6">
                  <div className="flex items-center justify-between mb-4">
                    <Dialog.Title
                      as="h3"
                      className="text-lg font-semibold leading-6 text-gray-900"
                    >
                      Настройки записи: {editingDevice?.hostname}
                    </Dialog.Title>
                    <button
                      type="button"
                      onClick={() => setEditingDevice(null)}
                      className="rounded-md bg-white text-gray-400 hover:text-gray-500 focus:outline-none focus:ring-2 focus:ring-red-500 focus:ring-offset-2"
                    >
                      <span className="sr-only">Закрыть</span>
                      <XMarkIcon className="h-6 w-6" aria-hidden="true" />
                    </button>
                  </div>
                  {editingDevice && (
                    <DeviceSettingsForm
                      deviceId={editingDevice.id}
                      currentSettings={editingDevice.settings as unknown as DeviceSettings | null}
                      onSave={handleSaveSettings}
                      onCancel={() => setEditingDevice(null)}
                    />
                  )}
                </Dialog.Panel>
              </Transition.Child>
            </div>
          </div>
        </Dialog>
      </Transition.Root>
    </div>
  );
}
