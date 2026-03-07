import { useState, useEffect, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import { getDevices } from '../api/devices';
import type { DeviceResponse } from '../types/device';
import DeviceStatusBadge from '../components/DeviceStatusBadge';
import LoadingSpinner from '../components/LoadingSpinner';
import {
  ComputerDesktopIcon,
  ChevronLeftIcon,
  ChevronRightIcon,
} from '@heroicons/react/24/outline';

const PAGE_SIZE = 24;

function getOsIcon(osType: string | null, osVersion: string | null): { icon: string; label: string } {
  const type = osType?.toLowerCase() || '';
  const ver = (osVersion || '').toLowerCase();

  if (type === 'windows' || ver.includes('windows')) {
    return { icon: 'W', label: osVersion || 'Windows' };
  }
  if (type === 'macos' || ver.includes('mac') || ver.includes('darwin')) {
    return { icon: 'M', label: osVersion || 'macOS' };
  }
  if (type === 'linux' || ver.includes('linux') || ver.includes('ubuntu')) {
    return { icon: 'L', label: osVersion || 'Linux' };
  }
  return { icon: 'W', label: osVersion || 'Unknown OS' };
}

function getOsIconColor(osType: string | null, osVersion: string | null): string {
  const type = osType?.toLowerCase() || '';
  const ver = (osVersion || '').toLowerCase();

  if (type === 'windows' || ver.includes('windows')) {
    return 'bg-blue-600';
  }
  if (type === 'macos' || ver.includes('mac') || ver.includes('darwin')) {
    return 'bg-gray-600';
  }
  if (type === 'linux' || ver.includes('linux') || ver.includes('ubuntu')) {
    return 'bg-orange-600';
  }
  return 'bg-gray-500';
}

export default function DeviceGridPage() {
  const navigate = useNavigate();
  const [devices, setDevices] = useState<DeviceResponse[]>([]);
  const [loading, setLoading] = useState(true);
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [totalElements, setTotalElements] = useState(0);

  const fetchDevices = useCallback(async () => {
    try {
      setLoading(true);
      const resp = await getDevices({ page, size: PAGE_SIZE, include_deleted: false });
      setDevices(resp.content);
      setTotalPages(resp.total_pages);
      setTotalElements(resp.total_elements);
    } catch (err) {
      console.error('Failed to load devices', err);
    } finally {
      setLoading(false);
    }
  }, [page]);

  useEffect(() => {
    fetchDevices();
  }, [fetchDevices]);

  const handleDeviceClick = (deviceId: string) => {
    navigate(`/recordings/${deviceId}`);
  };

  if (loading && devices.length === 0) {
    return (
      <div className="flex items-center justify-center h-64">
        <LoadingSpinner size="lg" />
      </div>
    );
  }

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-gray-900">Архив записей</h1>
          <p className="mt-1 text-sm text-gray-500">
            Выберите устройство для просмотра записей
          </p>
        </div>
        <div className="text-sm text-gray-500">
          {totalElements} {totalElements === 1 ? 'устройство' : 'устройств'}
        </div>
      </div>

      {/* Grid */}
      {devices.length === 0 ? (
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
            const osInfo = getOsIcon(device.os_type, device.os_version);
            const osColor = getOsIconColor(device.os_type, device.os_version);
            return (
              <div
                key={device.id}
                onClick={() => handleDeviceClick(device.id)}
                className="relative flex flex-col items-center p-4 bg-white rounded-xl border border-gray-200 shadow-sm hover:shadow-md hover:border-red-300 transition-all cursor-pointer group"
              >
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
                  {/* OS badge */}
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

                {/* OS version (short) */}
                <p className="text-xs text-gray-500 truncate w-full text-center mt-0.5" title={osInfo.label}>
                  {osInfo.label}
                </p>

                {/* Status badge */}
                <div className="mt-2">
                  <DeviceStatusBadge status={device.status} />
                </div>
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
  );
}
