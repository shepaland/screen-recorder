import { useState, useEffect, useMemo, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { ArrowLeftIcon } from '@heroicons/react/20/solid';
import { getPermissions } from '../api/permissions';
import { createRole } from '../api/roles';
import type { PermissionResponse } from '../types';
import { useToast } from '../contexts/ToastContext';
import LoadingSpinner from '../components/LoadingSpinner';
import { AxiosError } from 'axios';
import type { ErrorResponse } from '../types';

export default function RoleCreatePage() {
  const navigate = useNavigate();
  const { addToast } = useToast();

  const [code, setCode] = useState('');
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [selectedPermissionIds, setSelectedPermissionIds] = useState<Set<string>>(new Set());
  const [permissions, setPermissions] = useState<PermissionResponse[]>([]);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [errors, setErrors] = useState<Record<string, string>>({});

  useEffect(() => {
    getPermissions()
      .then(setPermissions)
      .catch(() => addToast('error', 'Failed to load permissions'));
  }, [addToast]);

  // Group permissions by resource
  const groupedPermissions = useMemo(() => {
    const groups: Record<string, PermissionResponse[]> = {};
    permissions.forEach((perm) => {
      if (!groups[perm.resource]) {
        groups[perm.resource] = [];
      }
      groups[perm.resource].push(perm);
    });
    return groups;
  }, [permissions]);

  const resourceOrder = [
    'USERS',
    'ROLES',
    'DEVICES',
    'RECORDINGS',
    'POLICIES',
    'AUDIT',
    'TENANTS',
    'DASHBOARD',
  ];

  const sortedResources = useMemo(() => {
    return Object.keys(groupedPermissions).sort(
      (a, b) => resourceOrder.indexOf(a) - resourceOrder.indexOf(b),
    );
  }, [groupedPermissions]);

  const togglePermission = (permId: string) => {
    setSelectedPermissionIds((prev) => {
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
    const resourcePerms = groupedPermissions[resource] || [];
    const allSelected = resourcePerms.every((p) => selectedPermissionIds.has(p.id));

    setSelectedPermissionIds((prev) => {
      const next = new Set(prev);
      resourcePerms.forEach((p) => {
        if (allSelected) {
          next.delete(p.id);
        } else {
          next.add(p.id);
        }
      });
      return next;
    });
  };

  const selectAll = () => {
    setSelectedPermissionIds(new Set(permissions.map((p) => p.id)));
  };

  const deselectAll = () => {
    setSelectedPermissionIds(new Set());
  };

  const validate = (): boolean => {
    const newErrors: Record<string, string> = {};

    if (!code.trim() || code.length < 3) {
      newErrors.code = 'Code must be at least 3 characters';
    }
    if (!name.trim() || name.length < 3) {
      newErrors.name = 'Name must be at least 3 characters';
    }
    if (selectedPermissionIds.size === 0) {
      newErrors.permissions = 'At least one permission must be selected';
    }

    setErrors(newErrors);
    return Object.keys(newErrors).length === 0;
  };

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault();
    if (!validate()) return;

    setIsSubmitting(true);
    try {
      await createRole({
        code: code.trim().toUpperCase().replace(/\s+/g, '_'),
        name: name.trim(),
        description: description.trim() || undefined,
        permission_ids: Array.from(selectedPermissionIds),
      });

      addToast('success', 'Role created successfully');
      navigate('/roles');
    } catch (err) {
      if (err instanceof AxiosError) {
        const data = err.response?.data as ErrorResponse | undefined;
        addToast('error', data?.error || 'Failed to create role');
      } else {
        addToast('error', 'An unexpected error occurred');
      }
    } finally {
      setIsSubmitting(false);
    }
  };

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
        <h1 className="text-2xl font-bold tracking-tight text-gray-900">Create Role</h1>
        <p className="mt-2 text-sm text-gray-600">
          Define a custom role with specific permissions.
        </p>
      </div>

      <form onSubmit={handleSubmit}>
        {/* Role details */}
        <div className="card max-w-2xl">
          <h2 className="text-lg font-semibold text-gray-900 mb-4">Role Details</h2>
          <div className="space-y-6">
            <div>
              <label htmlFor="code" className="label">
                Code *
              </label>
              <input
                id="code"
                type="text"
                value={code}
                onChange={(e) => setCode(e.target.value.toUpperCase().replace(/\s+/g, '_'))}
                className={`input-field mt-1 font-mono ${errors.code ? 'ring-red-300 focus:ring-red-500' : ''}`}
                placeholder="QUALITY_ANALYST"
              />
              {errors.code && <p className="mt-1 text-sm text-red-600">{errors.code}</p>}
              <p className="mt-1 text-xs text-gray-500">
                Uppercase with underscores. Auto-formatted.
              </p>
            </div>

            <div>
              <label htmlFor="name" className="label">
                Name *
              </label>
              <input
                id="name"
                type="text"
                value={name}
                onChange={(e) => setName(e.target.value)}
                className={`input-field mt-1 ${errors.name ? 'ring-red-300 focus:ring-red-500' : ''}`}
                placeholder="Quality Analyst"
              />
              {errors.name && <p className="mt-1 text-sm text-red-600">{errors.name}</p>}
            </div>

            <div>
              <label htmlFor="description" className="label">
                Description
              </label>
              <textarea
                id="description"
                value={description}
                onChange={(e) => setDescription(e.target.value)}
                rows={3}
                className="input-field mt-1"
                placeholder="Optional description of this role's purpose"
              />
            </div>
          </div>
        </div>

        {/* Permissions matrix */}
        <div className="card mt-6">
          <div className="flex items-center justify-between mb-4">
            <div>
              <h2 className="text-lg font-semibold text-gray-900">Permissions</h2>
              <p className="text-sm text-gray-500">
                Selected: {selectedPermissionIds.size} of {permissions.length}
              </p>
            </div>
            <div className="flex gap-2">
              <button type="button" onClick={selectAll} className="btn-secondary text-xs">
                Select All
              </button>
              <button type="button" onClick={deselectAll} className="btn-secondary text-xs">
                Deselect All
              </button>
            </div>
          </div>

          {errors.permissions && (
            <p className="mb-4 text-sm text-red-600">{errors.permissions}</p>
          )}

          <div className="space-y-6">
            {sortedResources.map((resource) => {
              const perms = groupedPermissions[resource];
              const allSelected = perms.every((p) => selectedPermissionIds.has(p.id));
              const someSelected =
                perms.some((p) => selectedPermissionIds.has(p.id)) && !allSelected;

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
                      <label
                        key={perm.id}
                        className="flex items-center gap-2 cursor-pointer"
                      >
                        <input
                          type="checkbox"
                          checked={selectedPermissionIds.has(perm.id)}
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
        </div>

        {/* Actions */}
        <div className="mt-6 flex justify-end gap-3">
          <button
            type="button"
            onClick={() => navigate('/roles')}
            className="btn-secondary"
          >
            Cancel
          </button>
          <button type="submit" disabled={isSubmitting} className="btn-primary">
            {isSubmitting && <LoadingSpinner size="sm" className="mr-2" />}
            {isSubmitting ? 'Creating...' : 'Create Role'}
          </button>
        </div>
      </form>
    </div>
  );
}
