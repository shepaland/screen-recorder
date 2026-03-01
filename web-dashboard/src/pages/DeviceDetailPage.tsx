import { useState, useEffect, useCallback } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { ArrowLeftIcon } from '@heroicons/react/20/solid';
import {
  PlayIcon,
  StopIcon,
  ArrowPathIcon,
  Cog6ToothIcon,
} from '@heroicons/react/24/outline';
import { getDevice, deleteDevice, sendCommand } from '../api/devices';
import type { DeviceDetailResponse, CreateCommandRequest } from '../types';
import DeviceStatusBadge from '../components/DeviceStatusBadge';
import StatusBadge from '../components/StatusBadge';
import PermissionGate from '../components/PermissionGate';
import ConfirmDialog from '../components/ConfirmDialog';
import LoadingSpinner from '../components/LoadingSpinner';
import { useToast } from '../contexts/ToastContext';
import { formatDateTime } from '../utils/format';
import { timeAgo, formatBytes, formatDuration } from '../utils/timeAgo';

const commandStatusLabels: Record<string, string> = {
  PENDING: 'Ожидает',
  DELIVERED: 'Доставлена',
  ACKNOWLEDGED: 'Подтверждена',
  FAILED: 'Ошибка',
  EXPIRED: 'Истекла',
};

const commandTypeLabels: Record<string, string> = {
  START_RECORDING: 'Начать запись',
  STOP_RECORDING: 'Остановить запись',
  UPDATE_SETTINGS: 'Обновить настройки',
  RESTART_AGENT: 'Перезапустить агент',
  UNREGISTER: 'Отменить регистрацию',
};

export default function DeviceDetailPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const { addToast } = useToast();

  const [device, setDevice] = useState<DeviceDetailResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [sendingCommand, setSendingCommand] = useState<string | null>(null);

  // Deactivate dialog
  const [showDeactivateDialog, setShowDeactivateDialog] = useState(false);

  const fetchDevice = useCallback(async () => {
    if (!id) return;
    setLoading(true);
    try {
      const data = await getDevice(id);
      setDevice(data);
    } catch {
      addToast('error', 'Не удалось загрузить устройство');
      navigate('/devices');
    } finally {
      setLoading(false);
    }
  }, [id, addToast, navigate]);

  useEffect(() => {
    fetchDevice();
  }, [fetchDevice]);

  const handleSendCommand = async (commandType: CreateCommandRequest['command_type']) => {
    if (!id) return;
    setSendingCommand(commandType);
    try {
      await sendCommand(id, { command_type: commandType });
      addToast('success', `Команда "${commandTypeLabels[commandType]}" отправлена`);
      // Refresh device data to show new command
      const updated = await getDevice(id);
      setDevice(updated);
    } catch {
      addToast('error', 'Не удалось отправить команду');
    } finally {
      setSendingCommand(null);
    }
  };

  const handleDeactivate = async () => {
    if (!id) return;
    try {
      await deleteDevice(id);
      addToast('success', 'Устройство деактивировано');
      navigate('/devices');
    } catch {
      addToast('error', 'Не удалось деактивировать устройство');
    }
    setShowDeactivateDialog(false);
  };

  if (loading) {
    return <LoadingSpinner size="lg" className="mt-12" />;
  }

  if (!device) {
    return <p className="text-center text-gray-500 mt-12">Устройство не найдено</p>;
  }

  return (
    <div>
      {/* Header */}
      <div className="mb-6">
        <button
          type="button"
          onClick={() => navigate('/devices')}
          className="flex items-center text-sm text-gray-500 hover:text-gray-700 mb-4"
        >
          <ArrowLeftIcon className="h-4 w-4 mr-1" />
          Назад к устройствам
        </button>
        <div className="flex items-center justify-between">
          <div>
            <div className="flex items-center gap-3">
              <h1 className="text-2xl font-bold tracking-tight text-gray-900">
                {device.hostname}
              </h1>
              <DeviceStatusBadge status={device.status} />
            </div>
            <p className="mt-1 text-sm text-gray-500">
              ID: {device.id}
            </p>
          </div>
          <PermissionGate permission="DEVICES:DELETE">
            {device.is_active && (
              <button
                type="button"
                onClick={() => setShowDeactivateDialog(true)}
                className="btn-danger"
              >
                Деактивировать
              </button>
            )}
          </PermissionGate>
        </div>
      </div>

      {/* Device info card */}
      <div className="card">
        <h2 className="text-lg font-semibold text-gray-900 mb-4">Информация об устройстве</h2>
        <dl className="grid grid-cols-1 gap-6 sm:grid-cols-2 lg:grid-cols-3">
          <div>
            <dt className="text-sm font-medium text-gray-500">Hostname</dt>
            <dd className="mt-1 text-sm text-gray-900">{device.hostname}</dd>
          </div>
          <div>
            <dt className="text-sm font-medium text-gray-500">Операционная система</dt>
            <dd className="mt-1 text-sm text-gray-900">{device.os_version || '--'}</dd>
          </div>
          <div>
            <dt className="text-sm font-medium text-gray-500">Версия агента</dt>
            <dd className="mt-1">
              <code className="rounded bg-gray-100 px-2 py-0.5 text-sm font-mono text-gray-700">
                {device.agent_version || '--'}
              </code>
            </dd>
          </div>
          <div>
            <dt className="text-sm font-medium text-gray-500">IP-адрес</dt>
            <dd className="mt-1 text-sm font-mono text-gray-900">{device.ip_address || '--'}</dd>
          </div>
          <div>
            <dt className="text-sm font-medium text-gray-500">Статус</dt>
            <dd className="mt-1">
              <StatusBadge
                active={device.is_active}
                activeText="Активно"
                inactiveText="Деактивировано"
              />
            </dd>
          </div>
          <div>
            <dt className="text-sm font-medium text-gray-500">Оператор</dt>
            <dd className="mt-1 text-sm text-gray-900">
              {device.user
                ? `${device.user.first_name || ''} ${device.user.last_name || ''} (${device.user.username})`.trim()
                : '--'}
            </dd>
          </div>
          <div>
            <dt className="text-sm font-medium text-gray-500">Последний heartbeat</dt>
            <dd className="mt-1 text-sm text-gray-900">{timeAgo(device.last_heartbeat_ts)}</dd>
          </div>
          <div>
            <dt className="text-sm font-medium text-gray-500">Последняя запись</dt>
            <dd className="mt-1 text-sm text-gray-900">{timeAgo(device.last_recording_ts)}</dd>
          </div>
          <div>
            <dt className="text-sm font-medium text-gray-500">Дата регистрации</dt>
            <dd className="mt-1 text-sm text-gray-900">{formatDateTime(device.created_ts)}</dd>
          </div>
        </dl>
      </div>

      {/* Commands section */}
      <PermissionGate permission="DEVICES:COMMAND">
        <div className="card mt-6">
          <h2 className="text-lg font-semibold text-gray-900 mb-4">Команды</h2>
          <div className="flex flex-wrap gap-3 mb-6">
            <button
              type="button"
              onClick={() => handleSendCommand('START_RECORDING')}
              disabled={!!sendingCommand}
              className="inline-flex items-center rounded-md bg-green-600 px-3 py-2 text-sm font-semibold text-white shadow-sm hover:bg-green-500 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-green-600 disabled:opacity-50 disabled:cursor-not-allowed"
            >
              {sendingCommand === 'START_RECORDING' ? (
                <LoadingSpinner size="sm" className="mr-2" />
              ) : (
                <PlayIcon className="h-4 w-4 mr-1.5" />
              )}
              Начать запись
            </button>
            <button
              type="button"
              onClick={() => handleSendCommand('STOP_RECORDING')}
              disabled={!!sendingCommand}
              className="inline-flex items-center rounded-md bg-red-600 px-3 py-2 text-sm font-semibold text-white shadow-sm hover:bg-red-500 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-red-600 disabled:opacity-50 disabled:cursor-not-allowed"
            >
              {sendingCommand === 'STOP_RECORDING' ? (
                <LoadingSpinner size="sm" className="mr-2" />
              ) : (
                <StopIcon className="h-4 w-4 mr-1.5" />
              )}
              Остановить запись
            </button>
            <button
              type="button"
              onClick={() => handleSendCommand('UPDATE_SETTINGS')}
              disabled={!!sendingCommand}
              className="inline-flex items-center rounded-md bg-yellow-600 px-3 py-2 text-sm font-semibold text-white shadow-sm hover:bg-yellow-500 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-yellow-600 disabled:opacity-50 disabled:cursor-not-allowed"
            >
              {sendingCommand === 'UPDATE_SETTINGS' ? (
                <LoadingSpinner size="sm" className="mr-2" />
              ) : (
                <Cog6ToothIcon className="h-4 w-4 mr-1.5" />
              )}
              Обновить настройки
            </button>
            <button
              type="button"
              onClick={() => handleSendCommand('RESTART_AGENT')}
              disabled={!!sendingCommand}
              className="inline-flex items-center rounded-md bg-indigo-600 px-3 py-2 text-sm font-semibold text-white shadow-sm hover:bg-indigo-500 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-indigo-600 disabled:opacity-50 disabled:cursor-not-allowed"
            >
              {sendingCommand === 'RESTART_AGENT' ? (
                <LoadingSpinner size="sm" className="mr-2" />
              ) : (
                <ArrowPathIcon className="h-4 w-4 mr-1.5" />
              )}
              Перезапустить агент
            </button>
          </div>

          {/* Recent commands table */}
          <h3 className="text-sm font-semibold text-gray-700 mb-3">Последние команды</h3>
          {device.recent_commands.length === 0 ? (
            <p className="text-sm text-gray-500">Команды не отправлялись</p>
          ) : (
            <div className="overflow-hidden shadow ring-1 ring-black ring-opacity-5 sm:rounded-lg">
              <table className="min-w-full divide-y divide-gray-300">
                <thead className="bg-gray-50">
                  <tr>
                    <th className="px-3 py-3.5 text-left text-sm font-semibold text-gray-900">Тип</th>
                    <th className="px-3 py-3.5 text-left text-sm font-semibold text-gray-900">Статус</th>
                    <th className="px-3 py-3.5 text-left text-sm font-semibold text-gray-900">Создана</th>
                    <th className="px-3 py-3.5 text-left text-sm font-semibold text-gray-900">Доставлена</th>
                    <th className="px-3 py-3.5 text-left text-sm font-semibold text-gray-900">Подтверждена</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-gray-200 bg-white">
                  {device.recent_commands.map((cmd) => (
                    <tr key={cmd.id}>
                      <td className="whitespace-nowrap px-3 py-4 text-sm text-gray-900">
                        {commandTypeLabels[cmd.command_type] || cmd.command_type}
                      </td>
                      <td className="whitespace-nowrap px-3 py-4 text-sm">
                        <span
                          className={`inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-medium ring-1 ring-inset ${
                            cmd.status === 'ACKNOWLEDGED'
                              ? 'bg-green-50 text-green-700 ring-green-600/20'
                              : cmd.status === 'DELIVERED'
                                ? 'bg-blue-50 text-blue-700 ring-blue-600/20'
                                : cmd.status === 'PENDING'
                                  ? 'bg-yellow-50 text-yellow-700 ring-yellow-600/20'
                                  : cmd.status === 'FAILED'
                                    ? 'bg-red-50 text-red-700 ring-red-600/20'
                                    : 'bg-gray-50 text-gray-700 ring-gray-600/20'
                          }`}
                        >
                          {commandStatusLabels[cmd.status] || cmd.status}
                        </span>
                      </td>
                      <td className="whitespace-nowrap px-3 py-4 text-sm text-gray-500">
                        {formatDateTime(cmd.created_ts)}
                      </td>
                      <td className="whitespace-nowrap px-3 py-4 text-sm text-gray-500">
                        {formatDateTime(cmd.delivered_ts)}
                      </td>
                      <td className="whitespace-nowrap px-3 py-4 text-sm text-gray-500">
                        {formatDateTime(cmd.acknowledged_ts)}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      </PermissionGate>

      {/* Active session section */}
      {device.active_session && (
        <div className="card mt-6">
          <h2 className="text-lg font-semibold text-gray-900 mb-4">Активная сессия записи</h2>
          <dl className="grid grid-cols-1 gap-6 sm:grid-cols-2 lg:grid-cols-4">
            <div>
              <dt className="text-sm font-medium text-gray-500">ID сессии</dt>
              <dd className="mt-1 text-sm font-mono text-gray-900">
                {device.active_session.id.substring(0, 8)}...
              </dd>
            </div>
            <div>
              <dt className="text-sm font-medium text-gray-500">Длительность</dt>
              <dd className="mt-1 text-sm text-gray-900">
                {formatDuration(device.active_session.total_duration_ms)}
              </dd>
            </div>
            <div>
              <dt className="text-sm font-medium text-gray-500">Сегментов</dt>
              <dd className="mt-1 text-sm text-gray-900">
                {device.active_session.segment_count}
              </dd>
            </div>
            <div>
              <dt className="text-sm font-medium text-gray-500">Объём</dt>
              <dd className="mt-1 text-sm text-gray-900">
                {formatBytes(device.active_session.total_bytes)}
              </dd>
            </div>
            <div>
              <dt className="text-sm font-medium text-gray-500">Статус</dt>
              <dd className="mt-1">
                <span className="inline-flex items-center rounded-full bg-green-50 px-2.5 py-0.5 text-xs font-medium text-green-700 ring-1 ring-inset ring-green-600/20">
                  {device.active_session.status}
                </span>
              </dd>
            </div>
            <div>
              <dt className="text-sm font-medium text-gray-500">Начало</dt>
              <dd className="mt-1 text-sm text-gray-900">
                {formatDateTime(device.active_session.started_ts)}
              </dd>
            </div>
          </dl>
        </div>
      )}

      {/* Deactivate confirmation dialog */}
      <ConfirmDialog
        open={showDeactivateDialog}
        title="Деактивировать устройство"
        message={`Вы уверены, что хотите деактивировать устройство "${device.hostname}"? Устройство будет отключено и не сможет отправлять записи.`}
        confirmText="Деактивировать"
        cancelText="Отмена"
        onConfirm={handleDeactivate}
        onCancel={() => setShowDeactivateDialog(false)}
      />
    </div>
  );
}
