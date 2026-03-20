import { useState, useEffect, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import { PlusIcon, MagnifyingGlassIcon } from '@heroicons/react/20/solid';
import DataTable, { type Column } from '../components/DataTable';
import StatusBadge from '../components/StatusBadge';
import PermissionGate from '../components/PermissionGate';
import { getUsers, type UsersListParams } from '../api/users';
import { getRoles } from '../api/roles';
import type { UserResponse, RoleResponse } from '../types';
import { useToast } from '../contexts/ToastContext';

export default function UsersListPage() {
  const navigate = useNavigate();
  const { addToast } = useToast();

  const [users, setUsers] = useState<UserResponse[]>([]);
  const [loading, setLoading] = useState(true);
  const [page, setPage] = useState(0);
  const [totalElements, setTotalElements] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [size] = useState(20);
  const [sortField, setSortField] = useState('created_ts');
  const [sortDirection, setSortDirection] = useState<'asc' | 'desc'>('desc');

  // Filters
  const [search, setSearch] = useState('');
  const [searchInput, setSearchInput] = useState('');
  const [statusFilter, setStatusFilter] = useState<string>('');
  const [roleFilter, setRoleFilter] = useState<string>('');
  const [availableRoles, setAvailableRoles] = useState<RoleResponse[]>([]);

  // Debounce search input
  useEffect(() => {
    const timer = setTimeout(() => {
      setSearch(searchInput);
      setPage(0);
    }, 300);
    return () => clearTimeout(timer);
  }, [searchInput]);

  // Load available roles for filter
  useEffect(() => {
    getRoles({ size: 100 })
      .then((data) => setAvailableRoles(data.content))
      .catch(() => {});
  }, []);

  const fetchUsers = useCallback(async () => {
    setLoading(true);
    try {
      const params: UsersListParams = {
        page,
        size,
        sort: `${sortField},${sortDirection}`,
      };
      if (search) params.search = search;
      if (statusFilter !== '') params.is_active = statusFilter === 'active';
      if (roleFilter) params.role_code = roleFilter;

      const data = await getUsers(params);
      setUsers(data.content);
      setTotalElements(data.total_elements);
      setTotalPages(data.total_pages);
    } catch {
      addToast('error', 'Failed to load users');
    } finally {
      setLoading(false);
    }
  }, [page, size, sortField, sortDirection, search, statusFilter, roleFilter, addToast]);

  useEffect(() => {
    fetchUsers();
  }, [fetchUsers]);

  const columns: Column<UserResponse>[] = [
    {
      key: 'email',
      title: 'Email',
      sortable: true,
      render: (user) => (
        <span className="font-medium text-gray-900">{user.email}</span>
      ),
    },
    {
      key: 'name',
      title: 'Имя',
      render: (user) => {
        const name = [user.first_name, user.last_name].filter(Boolean).join(' ');
        return name || '--';
      },
    },
    {
      key: 'roles',
      title: 'Roles',
      render: (user) => (
        <div className="flex flex-wrap gap-1">
          {user.roles.map((role) => (
            <span
              key={role.code}
              className="inline-flex items-center rounded-full bg-indigo-50 px-2 py-0.5 text-xs font-medium text-indigo-700 ring-1 ring-inset ring-indigo-700/10"
            >
              {role.name}
            </span>
          ))}
        </div>
      ),
    },
    {
      key: 'is_active',
      title: 'Status',
      render: (user) => <StatusBadge active={user.is_active} />,
    },
    {
      key: 'last_login_ts',
      title: 'Last Login',
      sortable: true,
      render: (user) =>
        user.last_login_ts
          ? new Date(user.last_login_ts).toLocaleString()
          : 'Never',
    },
  ];

  const handleSort = (field: string, direction: 'asc' | 'desc') => {
    setSortField(field);
    setSortDirection(direction);
  };

  return (
    <div>
      {/* Page header */}
      <div className="sm:flex sm:items-center sm:justify-between">
        <div>
          <h1 className="text-2xl font-bold tracking-tight text-gray-900">Users</h1>
          <p className="mt-2 text-sm text-gray-600">
            Manage user accounts for your organization.
          </p>
        </div>
        <PermissionGate permission="USERS:CREATE">
          <button
            type="button"
            onClick={() => navigate('/users/new')}
            className="btn-primary mt-4 sm:mt-0"
          >
            <PlusIcon className="-ml-0.5 mr-1.5 h-5 w-5" aria-hidden="true" />
            Add User
          </button>
        </PermissionGate>
      </div>

      {/* Filters */}
      <div className="mt-6 flex flex-col gap-4 sm:flex-row sm:items-end">
        {/* Search */}
        <div className="flex-1">
          <label htmlFor="search" className="label">
            Search
          </label>
          <div className="relative mt-1">
            <div className="pointer-events-none absolute inset-y-0 left-0 flex items-center pl-3">
              <MagnifyingGlassIcon className="h-5 w-5 text-gray-400" aria-hidden="true" />
            </div>
            <input
              id="search"
              type="text"
              placeholder="Search by username, email, name..."
              value={searchInput}
              onChange={(e) => setSearchInput(e.target.value)}
              className="input-field pl-10"
            />
          </div>
        </div>

        {/* Status filter */}
        <div className="sm:w-40">
          <label htmlFor="status" className="label">
            Status
          </label>
          <select
            id="status"
            value={statusFilter}
            onChange={(e) => {
              setStatusFilter(e.target.value);
              setPage(0);
            }}
            className="input-field mt-1"
          >
            <option value="">All</option>
            <option value="active">Active</option>
            <option value="inactive">Inactive</option>
          </select>
        </div>

        {/* Role filter */}
        <div className="sm:w-48">
          <label htmlFor="role" className="label">
            Role
          </label>
          <select
            id="role"
            value={roleFilter}
            onChange={(e) => {
              setRoleFilter(e.target.value);
              setPage(0);
            }}
            className="input-field mt-1"
          >
            <option value="">All Roles</option>
            {availableRoles.map((role) => (
              <option key={role.id} value={role.code}>
                {role.name}
              </option>
            ))}
          </select>
        </div>
      </div>

      {/* Table */}
      <div className="mt-6">
        <DataTable<UserResponse>
          columns={columns}
          data={users}
          loading={loading}
          page={page}
          size={size}
          totalElements={totalElements}
          totalPages={totalPages}
          sortField={sortField}
          sortDirection={sortDirection}
          onPageChange={setPage}
          onSort={handleSort}
          onRowClick={(user) => navigate(`/users/${user.id}`)}
          keyExtractor={(user) => user.id}
          emptyMessage="No users found"
        />
      </div>
    </div>
  );
}
