import { useState, useEffect, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import { PlusIcon } from '@heroicons/react/20/solid';
import DataTable, { type Column } from '../components/DataTable';
import StatusBadge from '../components/StatusBadge';
import PermissionGate from '../components/PermissionGate';
import ConfirmDialog from '../components/ConfirmDialog';
import { getDeviceTokens, deleteDeviceToken } from '../api/deviceTokens';
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

  // Deactivate dialog
  const [deactivateTarget, setDeactivateTarget] = useState<DeviceTokenResponse | null>(null);

  const fetchTokens = useCallback(async () => {
    setLoading(true);
    try {
      const data = await getDeviceTokens({ page, size });
      setTokens(data.content);
      setTotalElements(data.total_elements);
      setTotalPages(data.total_pages);
    } catch {
      addToast('error', 'Не удалось загрузить токены');
    } finally {
      setLoading(false);
    }
  }, [page, size, addToast]);

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
        <span className="text-gray-900">{token.current_uses}</span>
      ),
    },
    {
      key: 'max_uses',
      title: 'Максимум',
      render: (token) => (
        <span className="text-gray-900">
          {token.max_uses !== null ? token.max_uses : 'Без ограничений'}
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
      key: 'actions',
      title: 'Действия',
      render: (token) => (
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
    </div>
  );
}
