import { useState, useEffect, useCallback } from 'react';
import DataTable, { type Column } from '../components/DataTable';
import { getAuditLogs } from '../api/audit';
import { getUsers } from '../api/users';
import type { AuditLogResponse, AuditLogParams, UserResponse } from '../types';
import { useToast } from '../contexts/ToastContext';

const AUDIT_ACTIONS = [
  'LOGIN',
  'LOGIN_FAILED',
  'LOGOUT',
  'TOKEN_REFRESHED',
  'USER_CREATED',
  'USER_UPDATED',
  'USER_DEACTIVATED',
  'USER_PASSWORD_CHANGED',
  'ROLE_CREATED',
  'ROLE_UPDATED',
  'ROLE_DELETED',
  'USER_ROLE_ASSIGNED',
  'USER_ROLE_REVOKED',
  'TENANT_CREATED',
  'TENANT_UPDATED',
  'ACCESS_DENIED',
];

export default function AuditLogPage() {
  const { addToast } = useToast();

  const [logs, setLogs] = useState<AuditLogResponse[]>([]);
  const [loading, setLoading] = useState(true);
  const [page, setPage] = useState(0);
  const [totalElements, setTotalElements] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [size] = useState(50);

  // Filters
  const [fromTs, setFromTs] = useState('');
  const [toTs, setToTs] = useState('');
  const [userId, setUserId] = useState('');
  const [action, setAction] = useState('');
  const [users, setUsers] = useState<UserResponse[]>([]);

  // Load users for filter dropdown
  useEffect(() => {
    getUsers({ size: 100 })
      .then((data) => setUsers(data.content))
      .catch(() => {});
  }, []);

  const fetchLogs = useCallback(async () => {
    setLoading(true);
    try {
      const params: AuditLogParams = { page, size };
      if (fromTs) params.from_ts = new Date(fromTs).toISOString();
      if (toTs) params.to_ts = new Date(toTs + 'T23:59:59').toISOString();
      if (userId) params.user_id = userId;
      if (action) params.action = action;

      const data = await getAuditLogs(params);
      setLogs(data.content);
      setTotalElements(data.total_elements);
      setTotalPages(data.total_pages);
    } catch {
      addToast('error', 'Failed to load audit logs');
    } finally {
      setLoading(false);
    }
  }, [page, size, fromTs, toTs, userId, action, addToast]);

  useEffect(() => {
    fetchLogs();
  }, [fetchLogs]);

  const columns: Column<AuditLogResponse>[] = [
    {
      key: 'created_ts',
      title: 'Time',
      render: (log) => (
        <span className="text-gray-900 whitespace-nowrap">
          {new Date(log.created_ts).toLocaleString()}
        </span>
      ),
    },
    {
      key: 'username',
      title: 'User',
      render: (log) => log.username || '--',
    },
    {
      key: 'action',
      title: 'Action',
      render: (log) => (
        <span className="inline-flex items-center rounded-full bg-gray-100 px-2.5 py-0.5 text-xs font-medium text-gray-800">
          {log.action}
        </span>
      ),
    },
    {
      key: 'resource_type',
      title: 'Resource Type',
      className: 'hidden md:table-cell',
      render: (log) => log.resource_type,
    },
    {
      key: 'resource_id',
      title: 'Resource ID',
      className: 'hidden lg:table-cell',
      render: (log) =>
        log.resource_id ? (
          <span className="font-mono text-xs text-gray-500">
            {log.resource_id.substring(0, 8)}...
          </span>
        ) : (
          '--'
        ),
    },
    {
      key: 'ip_address',
      title: 'IP Address',
      className: 'hidden sm:table-cell',
      render: (log) => log.ip_address || '--',
    },
  ];

  const handleFilterApply = () => {
    setPage(0);
  };

  return (
    <div>
      {/* Page header */}
      <div>
        <h1 className="text-2xl font-bold tracking-tight text-gray-900">Audit Log</h1>
        <p className="mt-2 text-sm text-gray-600">
          View security and activity events across your organization.
        </p>
      </div>

      {/* Filters */}
      <div className="mt-6 card">
        <h2 className="text-sm font-semibold text-gray-900 mb-4">Filters</h2>
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
          {/* Date range */}
          <div>
            <label htmlFor="fromTs" className="label">
              From
            </label>
            <input
              id="fromTs"
              type="date"
              value={fromTs}
              onChange={(e) => setFromTs(e.target.value)}
              className="input-field mt-1"
            />
          </div>
          <div>
            <label htmlFor="toTs" className="label">
              To
            </label>
            <input
              id="toTs"
              type="date"
              value={toTs}
              onChange={(e) => setToTs(e.target.value)}
              className="input-field mt-1"
            />
          </div>

          {/* User filter */}
          <div>
            <label htmlFor="userFilter" className="label">
              User
            </label>
            <select
              id="userFilter"
              value={userId}
              onChange={(e) => setUserId(e.target.value)}
              className="input-field mt-1"
            >
              <option value="">All Users</option>
              {users.map((u) => (
                <option key={u.id} value={u.id}>
                  {u.username}
                </option>
              ))}
            </select>
          </div>

          {/* Action filter */}
          <div>
            <label htmlFor="actionFilter" className="label">
              Action
            </label>
            <select
              id="actionFilter"
              value={action}
              onChange={(e) => setAction(e.target.value)}
              className="input-field mt-1"
            >
              <option value="">All Actions</option>
              {AUDIT_ACTIONS.map((a) => (
                <option key={a} value={a}>
                  {a}
                </option>
              ))}
            </select>
          </div>
        </div>
        <div className="mt-4 flex justify-end">
          <button type="button" onClick={handleFilterApply} className="btn-primary">
            Apply Filters
          </button>
        </div>
      </div>

      {/* Table */}
      <div className="mt-6">
        <DataTable<AuditLogResponse>
          columns={columns}
          data={logs}
          loading={loading}
          page={page}
          size={size}
          totalElements={totalElements}
          totalPages={totalPages}
          onPageChange={setPage}
          keyExtractor={(log) => log.id}
          emptyMessage="No audit log entries found"
        />
      </div>
    </div>
  );
}
