import { useState, useEffect, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import { PlusIcon } from '@heroicons/react/20/solid';
import DataTable, { type Column } from '../components/DataTable';
import StatusBadge from '../components/StatusBadge';
import { getTenants } from '../api/tenants';
import type { TenantResponse } from '../types';
import { useToast } from '../contexts/ToastContext';

export default function TenantsPage() {
  const navigate = useNavigate();
  const { addToast } = useToast();

  const [tenants, setTenants] = useState<TenantResponse[]>([]);
  const [loading, setLoading] = useState(true);
  const [page, setPage] = useState(0);
  const [totalElements, setTotalElements] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [size] = useState(20);

  const fetchTenants = useCallback(async () => {
    setLoading(true);
    try {
      const data = await getTenants({ page, size });
      setTenants(data.content);
      setTotalElements(data.total_elements);
      setTotalPages(data.total_pages);
    } catch {
      addToast('error', 'Failed to load tenants');
    } finally {
      setLoading(false);
    }
  }, [page, size, addToast]);

  useEffect(() => {
    fetchTenants();
  }, [fetchTenants]);

  const columns: Column<TenantResponse>[] = [
    {
      key: 'name',
      title: 'Name',
      render: (tenant) => (
        <span className="font-medium text-gray-900">{tenant.name}</span>
      ),
    },
    {
      key: 'slug',
      title: 'Slug',
      render: (tenant) => (
        <span className="font-mono text-sm text-gray-500">{tenant.slug}</span>
      ),
    },
    {
      key: 'is_active',
      title: 'Status',
      render: (tenant) => <StatusBadge active={tenant.is_active} />,
    },
    {
      key: 'settings',
      title: 'Max Users',
      render: (tenant) => (
        <span className="text-gray-500">{tenant.settings?.max_users ?? '--'}</span>
      ),
    },
    {
      key: 'created_ts',
      title: 'Created',
      render: (tenant) => new Date(tenant.created_ts).toLocaleDateString(),
    },
  ];

  return (
    <div>
      {/* Page header */}
      <div className="sm:flex sm:items-center sm:justify-between">
        <div>
          <h1 className="text-2xl font-bold tracking-tight text-gray-900">Компании</h1>
          <p className="mt-2 text-sm text-gray-600">
            Управление организациями на платформе.
          </p>
        </div>
        <button
          type="button"
          onClick={() => navigate('/tenants/new')}
          className="btn-primary mt-4 sm:mt-0"
        >
          <PlusIcon className="-ml-0.5 mr-1.5 h-5 w-5" aria-hidden="true" />
          Создать компанию
        </button>
      </div>

      {/* Table */}
      <div className="mt-6">
        <DataTable<TenantResponse>
          columns={columns}
          data={tenants}
          loading={loading}
          page={page}
          size={size}
          totalElements={totalElements}
          totalPages={totalPages}
          onPageChange={setPage}
          keyExtractor={(tenant) => tenant.id}
          emptyMessage="Компании не найдены"
        />
      </div>
    </div>
  );
}
