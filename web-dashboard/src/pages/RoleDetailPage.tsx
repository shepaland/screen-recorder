import { useState, useEffect, useCallback, useMemo } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { ArrowLeftIcon, PencilIcon, TrashIcon } from '@heroicons/react/20/solid';
import { getRole, updateRole, deleteRole } from '../api/roles';
import { getPermissions } from '../api/permissions';
import type { RoleResponse, PermissionResponse } from '../types';
import StatusBadge from '../components/StatusBadge';
import PermissionGate from '../components/PermissionGate';
import ConfirmDialog from '../components/ConfirmDialog';
import LoadingSpinner from '../components/LoadingSpinner';
import { useToast } from '../contexts/ToastContext';
import { AxiosError } from 'axios';
import type { ErrorResponse } from '../types';

export default function RoleDetailPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const { addToast } = useToast();

  const [role, setRole] = useState<RoleResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [allPermissions, setAllPermissions] = useState<PermissionResponse[]>([]);

  // Edit state
  const [isEditing, setIsEditing] = useState(false);
  const [editName, setEditName] = useState('');
  const [editDescription, setEditDescription] = useState('');
  const [editPermissionIds, setEditPermissionIds] = useState<Set<string>>(new Set());
  const [isSaving, setIsSaving] = useState(false);

  // Delete dialog
  const [showDeleteDialog, setShowDeleteDialog] = useState(false);

  const fetchRole = useCallback(async () => {
    if (!id) return;
    setLoading(true);
    try {
      const [roleData, perms] = await Promise.all([getRole(id), getPermissions()]);
      setRole(roleData);
      setAllPermissions(perms);
      setEditName(roleData.name);
      setEditDescription(roleData.description || '');
      setEditPermissionIds(
        new Set(roleData.permissions?.map((p) => p.id) || []),
      );
    } catch {
      addToast('error', 'Failed to load role');
      navigate('/roles');
    } finally {
      setLoading(false);
    }
  }, [id, addToast, navigate]);

  useEffect(() => {
    fetchRole();
  }, [fetchRole]);

  // Group permissions by resource
  const groupedPermissions = useMemo(() => {
    const groups: Record<string, PermissionResponse[]> = {};
    allPermissions.forEach((perm) => {
      if (!groups[perm.resource]) {
        groups[perm.resource] = [];
      }
      groups[perm.resource].push(perm);
    });
    return groups;
  }, [allPermissions]);

  const resourceOrder = [
    'USERS', 'ROLES', 'DEVICES', 'RECORDINGS', 'POLICIES', 'AUDIT', 'TENANTS', 'DASHBOARD',
  ];

  const sortedResources = useMemo(() => {
    return Object.keys(groupedPermissions).sort(
      (a, b) => resourceOrder.indexOf(a) - resourceOrder.indexOf(b),
    );
  }, [groupedPermissions]);

  const togglePermission = (permId: string) => {
    setEditPermissionIds((prev) => {
      const next = new Set(prev);
      if (next.has(permId)) {
        next.delete(permId);
      } else {
        next.add(permId);
      }
      return next;
    });
  };

  const toggleResource = (resource: string) => {
    const perms = groupedPermissions[resource] || [];
    const allSelected = perms.every((p) => editPermissionIds.has(p.id));
    setEditPermissionIds((prev) => {
      const next = new Set(prev);
      perms.forEach((p) => {
        if (allSelected) next.delete(p.id);
        else next.add(p.id);
      });
      return next;
    });
  };

  const handleSave = async () => {
    if (!id) return;
    if (editPermissionIds.size === 0) {
      addToast('error', 'At least one permission must be selected');
      return;
    }

    setIsSaving(true);
    try {
      const updated = await updateRole(id, {
        name: editName.trim(),
        description: editDescription.trim() || undefined,
        permission_ids: Array.from(editPermissionIds),
      });
      setRole(updated);
      setIsEditing(false);
      addToast('success', 'Role updated successfully');
      // Refresh to get full permissions list
      fetchRole();
    } catch (err) {
      if (err instanceof AxiosError) {
        const data = err.response?.data as ErrorResponse | undefined;
        addToast('error', data?.error || 'Failed to update role');
      } else {
        addToast('error', 'Failed to update role');
      }
    } finally {
      setIsSaving(false);
    }
  };

  const handleDelete = async () => {
    if (!id) return;
    try {
      await deleteRole(id);
      addToast('success', 'Role deleted successfully');
      navigate('/roles');
    } catch (err) {
      if (err instanceof AxiosError) {
        const data = err.response?.data as ErrorResponse | undefined;
        if (data?.code === 'ROLE_HAS_USERS') {
          addToast('error', 'Cannot delete role: it is assigned to users');
        } else {
          addToast('error', data?.error || 'Failed to delete role');
        }
      } else {
        addToast('error', 'Failed to delete role');
      }
    }
    setShowDeleteDialog(false);
  };

  if (loading) {
    return <LoadingSpinner size="lg" className="mt-12" />;
  }

  if (!role) {
    return <p className="text-center text-gray-500 mt-12">Role not found</p>;
  }

  const isReadonly = role.is_system;

  return (
    <div>
      {/* Header */}
      <div className="mb-6">
        <button
          type="button"
          onClick={() => navigate('/roles')}
          className="flex items-center text-sm text-gray-500 hover:text-gray-700 mb-4"
        >
          <ArrowLeftIcon className="h-4 w-4 mr-1" />
          Back to Roles
        </button>
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-3">
            <h1 className="text-2xl font-bold tracking-tight text-gray-900">{role.name}</h1>
            <StatusBadge
              active={role.is_system}
              activeText="System"
              inactiveText="Custom"
            />
          </div>
          {!isReadonly && (
            <div className="flex gap-3">
              <PermissionGate permission="ROLES:UPDATE">
                {!isEditing ? (
                  <button
                    type="button"
                    onClick={() => setIsEditing(true)}
                    className="btn-secondary"
                  >
                    <PencilIcon className="-ml-0.5 mr-1.5 h-4 w-4" />
                    Edit
                  </button>
                ) : (
                  <>
                    <button
                      type="button"
                      onClick={() => {
                        setIsEditing(false);
                        setEditName(role.name);
                        setEditDescription(role.description || '');
                        setEditPermissionIds(
                          new Set(role.permissions?.map((p) => p.id) || []),
                        );
                      }}
                      className="btn-secondary"
                    >
                      Cancel
                    </button>
                    <button
                      type="button"
                      onClick={handleSave}
                      disabled={isSaving}
                      className="btn-primary"
                    >
                      {isSaving && <LoadingSpinner size="sm" className="mr-2" />}
                      Save
                    </button>
                  </>
                )}
              </PermissionGate>
              <PermissionGate permission="ROLES:DELETE">
                <button
                  type="button"
                  onClick={() => setShowDeleteDialog(true)}
                  className="btn-danger"
                >
                  <TrashIcon className="-ml-0.5 mr-1.5 h-4 w-4" />
                  Delete
                </button>
              </PermissionGate>
            </div>
          )}
        </div>
      </div>

      {/* Role details */}
      <div className="card">
        <dl className="grid grid-cols-1 gap-6 sm:grid-cols-2">
          <div>
            <dt className="text-sm font-medium text-gray-500">Code</dt>
            <dd className="mt-1 font-mono text-sm text-gray-900">{role.code}</dd>
          </div>
          <div>
            <dt className="text-sm font-medium text-gray-500">Name</dt>
            <dd className="mt-1 text-sm text-gray-900">
              {isEditing && !isReadonly ? (
                <input
                  type="text"
                  value={editName}
                  onChange={(e) => setEditName(e.target.value)}
                  className="input-field"
                />
              ) : (
                role.name
              )}
            </dd>
          </div>
          <div className="sm:col-span-2">
            <dt className="text-sm font-medium text-gray-500">Description</dt>
            <dd className="mt-1 text-sm text-gray-900">
              {isEditing && !isReadonly ? (
                <textarea
                  value={editDescription}
                  onChange={(e) => setEditDescription(e.target.value)}
                  rows={2}
                  className="input-field"
                />
              ) : (
                role.description || '--'
              )}
            </dd>
          </div>
          <div>
            <dt className="text-sm font-medium text-gray-500">Created</dt>
            <dd className="mt-1 text-sm text-gray-900">
              {new Date(role.created_ts).toLocaleString()}
            </dd>
          </div>
        </dl>
      </div>

      {/* Permissions */}
      <div className="card mt-6">
        <h2 className="text-lg font-semibold text-gray-900 mb-4">
          Permissions ({isEditing ? editPermissionIds.size : (role.permissions?.length ?? 0)})
        </h2>

        {isEditing && !isReadonly ? (
          <div className="space-y-4">
            {sortedResources.map((resource) => {
              const perms = groupedPermissions[resource];
              const allSelected = perms.every((p) => editPermissionIds.has(p.id));
              const someSelected =
                perms.some((p) => editPermissionIds.has(p.id)) && !allSelected;

              return (
                <div key={resource} className="rounded-lg border border-gray-200 p-4">
                  <div className="flex items-center gap-3 mb-3">
                    <input
                      type="checkbox"
                      checked={allSelected}
                      ref={(input) => {
                        if (input) input.indeterminate = someSelected;
                      }}
                      onChange={() => toggleResource(resource)}
                      className="h-4 w-4 rounded border-gray-300 text-indigo-600 focus:ring-indigo-600"
                    />
                    <h3 className="text-sm font-semibold text-gray-900">{resource}</h3>
                  </div>
                  <div className="ml-7 grid grid-cols-2 gap-2 sm:grid-cols-3 md:grid-cols-4">
                    {perms.map((perm) => (
                      <label key={perm.id} className="flex items-center gap-2 cursor-pointer">
                        <input
                          type="checkbox"
                          checked={editPermissionIds.has(perm.id)}
                          onChange={() => togglePermission(perm.id)}
                          className="h-4 w-4 rounded border-gray-300 text-indigo-600 focus:ring-indigo-600"
                        />
                        <span className="text-sm text-gray-700">{perm.action}</span>
                      </label>
                    ))}
                  </div>
                </div>
              );
            })}
          </div>
        ) : (
          <div className="space-y-4">
            {sortedResources.map((resource) => {
              const rolePermCodes = new Set(role.permissions?.map((p) => p.code) || []);
              const perms = groupedPermissions[resource];
              const activePerms = perms?.filter((p) => rolePermCodes.has(p.code)) || [];

              if (activePerms.length === 0) return null;

              return (
                <div key={resource} className="rounded-lg border border-gray-200 p-4">
                  <h3 className="text-sm font-semibold text-gray-900 mb-2">{resource}</h3>
                  <div className="flex flex-wrap gap-2">
                    {activePerms.map((perm) => (
                      <span
                        key={perm.id}
                        className="inline-flex items-center rounded-full bg-indigo-50 px-2.5 py-0.5 text-xs font-medium text-indigo-700 ring-1 ring-inset ring-indigo-700/10"
                      >
                        {perm.action}
                      </span>
                    ))}
                  </div>
                </div>
              );
            })}
          </div>
        )}

        {isReadonly && (
          <p className="mt-4 text-xs text-gray-500">
            System roles cannot be modified.
          </p>
        )}
      </div>

      {/* Delete confirmation */}
      <ConfirmDialog
        open={showDeleteDialog}
        title="Delete Role"
        message={`Are you sure you want to delete the role "${role.name}"? This action cannot be undone.`}
        confirmText="Delete"
        onConfirm={handleDelete}
        onCancel={() => setShowDeleteDialog(false)}
      />
    </div>
  );
}
