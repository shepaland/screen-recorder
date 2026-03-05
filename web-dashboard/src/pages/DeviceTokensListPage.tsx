import { Fragment, useState, useEffect, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import { Dialog, Transition } from '@headlessui/react';
import { PlusIcon, MagnifyingGlassIcon } from '@heroicons/react/20/solid';
import { XMarkIcon } from '@heroicons/react/24/outline';
import DataTable, { type Column } from '../components/DataTable';
import StatusBadge from '../components/StatusBadge';
import PermissionGate from '../components/PermissionGate';
import ConfirmDialog from '../components/ConfirmDialog';
import LoadingSpinner from '../components/LoadingSpinner';
import {
  getDeviceTokens,
  deleteDeviceToken,
  getTokenDevices,
  type DeviceTokensListParams,
  type TokenDeviceItem,
  type TokenDevicesResponse,
} from '../api/deviceTokens';
import type { DeviceTokenResponse } from '../types';
import { useToast } from '../contexts/ToastContext';
import { formatDateTime } from '../utils/format';

export default function DeviceTokensListPage() {
  const navigate = useNavigate();
  const { addToast } = useToast();

  const [tokens, setTokens] = useState<DeviceTokenResponse[]>([]);
  const [loading, setLoading] = useState(true);
  const [page, setPage] = useState(0);
  const [totalElements, setTotalElements] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [size] = useState(20);

  // Filters
  const [search, setSearch] = useState('');
  const [searchInput, setSearchInput] = useState('');
  const [statusFilter, setStatusFilter] = useState<string>('');

  // Deactivate dialog
  const [deactivateTarget, setDeactivateTarget] = useState<DeviceTokenResponse | null>(null);

  // Devices modal
  const [showDevicesModal, setShowDevicesModal] = useState(false);
  const [selectedTokenForDevices, setSelectedTokenForDevices] = useState<DeviceTokenResponse | null>(null);
  const [tokenDevicesData, setTokenDevicesData] = useState<TokenDevicesResponse | null>(null);
  const [loadingDevices, setLoadingDevices] = useState(false);

  // Debounce search input
  useEffect(() => {
    const timer = setTimeout(() => {
      setSearch(searchInput);
      setPage(0);
    }, 300);
    return () => clearTimeout(timer);
  }, [searchInput]);

  const fetchTokens = useCallback(async () => {
    setLoading(true);
    try {
      const params: DeviceTokensListParams = { page, size };
      if (search) params.search = search;
      if (statusFilter !== '') params.is_active = statusFilter === 'active';

      const data = await getDeviceTokens(params);
      setTokens(data.content);
      setTotalElements(data.total_elements);
      setTotalPages(data.total_pages);
    } catch {
      addToast('error', 'Не удалось загрузить токены');
    } finally {
      setLoading(false);
    }
  }, [page, size, search, statusFilter, addToast]);

  useEffect(() => {
    fetchTokens();
  }, [fetchTokens]);

  const handleDeactivate = async () => {
    if (!deactivateTarget) return;
    try {
      await deleteDeviceToken(deactivateTarget.id);
      addToast('success', 'Токен деактивирован');
      fetchTokens();
    } catch {
      addToast('error', 'Не удалось деактивировать токен');
    }
    setDeactivateTarget(null);
  };

  const handleShowDevices = async (token: DeviceTokenResponse) => {
    setSelectedTokenForDevices(token);
    setShowDevicesModal(true);
    setLoadingDevices(true);
    setTokenDevicesData(null);
    try {
      const data = await getTokenDevices(token.id);
      setTokenDevicesData(data);
    } catch {
      addToast('error', 'Не удалось загрузить устройства токена');
      setShowDevicesModal(false);
    } finally {
      setLoadingDevices(false);
    }
  };

  const columns: Column<DeviceTokenResponse>[] = [
    {
      key: 'name',
      title: 'Имя',
      render: (token) => (
        <span className="font-medium text-gray-900">{token.name}</span>
      ),
    },
    {
      key: 'token_preview',
      title: 'Токен',
      render: (token) => (
        <code className="rounded bg-gray-100 px-2 py-0.5 text-xs font-mono text-gray-700">
          {token.token_preview || '****'}
        </code>
      ),
    },
    {
      key: 'current_uses',
      title: 'Использований',
      render: (token) => (
        <span className="text-gray-900">
          {token.current_uses}
          {token.max_uses !== null ? ` / ${token.max_uses}` : ''}
        </span>
      ),
    },
    {
      key: 'expires_at',
      title: 'Срок действия',
      render: (token) => {
        if (!token.expires_at) return <span className="text-gray-500">Бессрочный</span>;
        const isExpired = new Date(token.expires_at) < new Date();
        return (
          <span className={isExpired ? 'text-red-600' : 'text-gray-900'}>
            {formatDateTime(token.expires_at)}
            {isExpired && ' (истёк)'}
          </span>
        );
      },
    },
    {
      key: 'is_active',
      title: 'Статус',
      render: (token) => (
        <StatusBadge
          active={token.is_active}
          activeText="Активен"
          inactiveText="Деактивирован"
        />
      ),
    },
    {
      key: 'created_by_username',
      title: 'Создал',
      render: (token) => (
        <span className="text-gray-700">{token.created_by_username || '--'}</span>
      ),
    },
    {
      key: 'actions',
      title: 'Действия',
      render: (token) => (
        <div className="flex items-center gap-3">
          <button
            type="button"
            onClick={(e) => {
              e.stopPropagation();
              handleShowDevices(token);
            }}
            className="text-sm text-blue-600 hover:text-blue-800"
          >
            Устройства ({token.device_count})
          </button>
          <PermissionGate permission="DEVICE_TOKENS:DELETE">
            {token.is_active && (
              <button
                type="button"
                onClick={(e) => {
                  e.stopPropagation();
                  setDeactivateTarget(token);
                }}
                className="text-sm text-red-600 hover:text-red-800"
              >
                Деактивировать
              </button>
            )}
          </PermissionGate>
        </div>
      ),
    },
  ];

  return (
    <div>
      {/* Page header */}
      <div className="sm:flex sm:items-center sm:justify-between">
        <div>
          <h1 className="text-2xl font-bold tracking-tight text-gray-900">
            Токены регистрации устройств
          </h1>
          <p className="mt-2 text-sm text-gray-600">
            Управление токенами для регистрации агентов записи.
          </p>
        </div>
        <PermissionGate permission="DEVICE_TOKENS:CREATE">
          <button
            type="button"
            onClick={() => navigate('/device-tokens/create')}
            className="btn-primary mt-4 sm:mt-0"
          >
            <PlusIcon className="-ml-0.5 mr-1.5 h-5 w-5" aria-hidden="true" />
            Создать токен
          </button>
        </PermissionGate>
      </div>

      {/* Filters */}
      <div className="mt-6 flex flex-col gap-4 sm:flex-row sm:items-end">
        {/* Search */}
        <div className="flex-1">
          <label htmlFor="tokenSearch" className="label">Поиск</label>
          <div className="relative mt-1">
            <div className="pointer-events-none absolute inset-y-0 left-0 flex items-center pl-3">
              <MagnifyingGlassIcon className="h-5 w-5 text-gray-400" aria-hidden="true" />
            </div>
            <input
              id="tokenSearch"
              type="text"
              placeholder="Поиск по имени..."
              value={searchInput}
              onChange={(e) => setSearchInput(e.target.value)}
              className="input-field pl-10"
            />
          </div>
        </div>

        {/* Status filter */}
        <div className="sm:w-48">
          <label htmlFor="tokenStatus" className="label">Статус</label>
          <select
            id="tokenStatus"
            value={statusFilter}
            onChange={(e) => {
              setStatusFilter(e.target.value);
              setPage(0);
            }}
            className="input-field mt-1"
          >
            <option value="">Все</option>
            <option value="active">Активные</option>
            <option value="inactive">Деактивированные</option>
          </select>
        </div>
      </div>

      {/* Table */}
      <div className="mt-6">
        <DataTable<DeviceTokenResponse>
          columns={columns}
          data={tokens}
          loading={loading}
          page={page}
          size={size}
          totalElements={totalElements}
          totalPages={totalPages}
          onPageChange={setPage}
          keyExtractor={(token) => token.id}
          emptyMessage="Токены не найдены"
        />
      </div>

      {/* Deactivate confirmation dialog */}
      <ConfirmDialog
        open={!!deactivateTarget}
        title="Деактивировать токен"
        message={`Вы уверены, что хотите деактивировать токен "${deactivateTarget?.name}"? Устройства больше не смогут зарегистрироваться с этим токеном.`}
        confirmText="Деактивировать"
        cancelText="Отмена"
        onConfirm={handleDeactivate}
        onCancel={() => setDeactivateTarget(null)}
      />

      {/* Token devices modal */}
      <Transition.Root show={showDevicesModal} as={Fragment}>
        <Dialog as="div" className="relative z-50" onClose={() => setShowDevicesModal(false)}>
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
                <Dialog.Panel className="relative transform overflow-hidden rounded-lg bg-white px-4 pb-4 pt-5 text-left shadow-xl transition-all sm:my-8 sm:w-full sm:max-w-5xl sm:p-6">
                  <div className="flex items-center justify-between mb-4">
                    <Dialog.Title as="h3" className="text-base font-semibold leading-6 text-gray-900">
                      Устройства токена {selectedTokenForDevices ? `\u00AB${selectedTokenForDevices.name}\u00BB` : ''}
                    </Dialog.Title>
                    <button
                      type="button"
                      onClick={() => setShowDevicesModal(false)}
                      className="rounded-md text-gray-400 hover:text-gray-500"
                    >
                      <XMarkIcon className="h-5 w-5" />
                    </button>
                  </div>

                  {loadingDevices ? (
                    <LoadingSpinner size="md" className="py-12" />
                  ) : tokenDevicesData && tokenDevicesData.devices.length > 0 ? (
                    <div className="overflow-x-auto shadow ring-1 ring-black ring-opacity-5 sm:rounded-lg">
                      <table className="min-w-full divide-y divide-gray-300">
                        <thead className="bg-gray-50">
                          <tr>
                            <th className="px-3 py-3.5 text-left text-sm font-semibold text-gray-900">Hostname</th>
                            <th className="px-3 py-3.5 text-left text-sm font-semibold text-gray-900">ОС</th>
                            <th className="px-3 py-3.5 text-left text-sm font-semibold text-gray-900">Статус</th>
                            <th className="px-3 py-3.5 text-left text-sm font-semibold text-gray-900">Последний heartbeat</th>
                            <th className="px-3 py-3.5 text-left text-sm font-semibold text-gray-900">Зарегистрирован</th>
                          </tr>
                        </thead>
                        <tbody className="divide-y divide-gray-200 bg-white">
                          {tokenDevicesData.devices.map((device: TokenDeviceItem) => (
                            <tr key={device.id}>
                              <td className="whitespace-nowrap px-3 py-4 text-sm font-medium text-gray-900">
                                {device.hostname}
                              </td>
                              <td className="px-3 py-4 text-sm text-gray-500 max-w-[200px]">
                                {device.os_info || '--'}
                              </td>
                              <td className="whitespace-nowrap px-3 py-4 text-sm">
                                {device.is_deleted ? (
                                  <span className="inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-medium bg-red-100 text-red-800 ring-1 ring-inset ring-red-600/20">
                                    Удалено
                                  </span>
                                ) : !device.is_active ? (
                                  <span className="inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-medium bg-gray-100 text-gray-600 ring-1 ring-inset ring-gray-500/20">
                                    Неактивно
                                  </span>
                                ) : (
                                  <span className={`inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-medium ring-1 ring-inset ${
                                    device.status === 'online'
                                      ? 'bg-yellow-100 text-yellow-800 ring-yellow-600/20'
                                      : device.status === 'recording'
                                        ? 'bg-green-100 text-green-800 ring-green-600/20'
                                        : device.status === 'error'
                                          ? 'bg-red-100 text-red-800 ring-red-600/20'
                                          : 'bg-gray-100 text-gray-600 ring-gray-500/20'
                                  }`}>
                                    {device.status === 'online' ? 'Онлайн'
                                      : device.status === 'recording' ? 'Запись'
                                      : device.status === 'offline' ? 'Офлайн'
                                      : device.status === 'error' ? 'Ошибка'
                                      : device.status}
                                  </span>
                                )}
                              </td>
                              <td className="whitespace-nowrap px-3 py-4 text-sm text-gray-500">
                                {device.last_heartbeat_ts
                                  ? new Date(device.last_heartbeat_ts).toLocaleString('ru-RU', {
                                      day: '2-digit', month: '2-digit', year: 'numeric',
                                      hour: '2-digit', minute: '2-digit',
                                    })
                                  : '--'}
                              </td>
                              <td className="whitespace-nowrap px-3 py-4 text-sm text-gray-500">
                                {formatDateTime(device.created_ts)}
                              </td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    </div>
                  ) : (
                    <div className="text-center py-12">
                      <p className="text-sm text-gray-500">Нет подключённых устройств</p>
                    </div>
                  )}

                  <div className="mt-5 sm:mt-4 flex justify-end">
                    <button
                      type="button"
                      className="inline-flex justify-center rounded-md bg-white px-3 py-2 text-sm font-semibold text-gray-900 shadow-sm ring-1 ring-inset ring-gray-300 hover:bg-gray-50"
                      onClick={() => setShowDevicesModal(false)}
                    >
                      Закрыть
                    </button>
                  </div>
                </Dialog.Panel>
              </Transition.Child>
            </div>
          </div>
        </Dialog>
      </Transition.Root>
    </div>
  );
}
