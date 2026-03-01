import { useState, useEffect, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import { PlusIcon } from '@heroicons/react/20/solid';
import DataTable, { type Column } from '../components/DataTable';
import StatusBadge from '../components/StatusBadge';
import PermissionGate from '../components/PermissionGate';
import { getRoles } from '../api/roles';
import type { RoleResponse } from '../types';
import { useToast } from '../contexts/ToastContext';

export default function RolesListPage() {
  const navigate = useNavigate();
  const { addToast } = useToast();

  const [roles, setRoles] = useState<RoleResponse[]>([]);
  const [loading, setLoading] = useState(true);
  const [page, setPage] = useState(0);
  const [totalElements, setTotalElements] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [size] = useState(20);

  const fetchRoles = useCallback(async () => {
    setLoading(true);
    try {
      const data = await getRoles({ page, size });
      setRoles(data.content);
      setTotalElements(data.total_elements);
      setTotalPages(data.total_pages);
    } catch {
      addToast('error', 'Failed to load roles');
    } finally {
      setLoading(false);
    }
  }, [page, size, addToast]);

  useEffect(() => {
    fetchRoles();
  }, [fetchRoles]);

  const columns: Column<RoleResponse>[] = [
    {
      key: 'code',
      title: 'Code',
      render: (role) => (
        <span className="font-mono text-sm font-medium text-gray-900">{role.code}</span>
      ),
    },
    {
      key: 'name',
      title: 'Name',
      render: (role) => <span className="text-gray-900">{role.name}</span>,
    },
    {
      key: 'is_system',
      title: 'Type',
      render: (role) => (
        <StatusBadge
          active={role.is_system}
          activeText="System"
          inactiveText="Custom"
        />
      ),
    },
    {
      key: 'permissions_count',
      title: 'Permissions',
      render: (role) => (
        <span className="text-gray-500">{role.permissions_count ?? '--'}</span>
      ),
    },
    {
      key: 'users_count',
      title: 'Users',
      render: (role) => (
        <span className="text-gray-500">{role.users_count ?? '--'}</span>
      ),
    },
    {
      key: 'created_ts',
      title: 'Created',
      render: (role) => new Date(role.created_ts).toLocaleDateString(),
    },
  ];

  return (
    <div>
      {/* Page header */}
      <div className="sm:flex sm:items-center sm:justify-between">
        <div>
          <h1 className="text-2xl font-bold tracking-tight text-gray-900">Roles</h1>
          <p className="mt-2 text-sm text-gray-600">
            Manage roles and permissions for your organization.
          </p>
        </div>
        <PermissionGate permission="ROLES:CREATE">
          <button
            type="button"
            onClick={() => navigate('/roles/new')}
            className="btn-primary mt-4 sm:mt-0"
          >
            <PlusIcon className="-ml-0.5 mr-1.5 h-5 w-5" aria-hidden="true" />
            Add Role
          </button>
        </PermissionGate>
      </div>

      {/* Table */}
      <div className="mt-6">
        <DataTable<RoleResponse>
          columns={columns}
          data={roles}
          loading={loading}
          page={page}
          size={size}
          totalElements={totalElements}
          totalPages={totalPages}
          onPageChange={setPage}
          onRowClick={(role) => navigate(`/roles/${role.id}`)}
          keyExtractor={(role) => role.id}
          emptyMessage="No roles found"
        />
      </div>
    </div>
  );
}
