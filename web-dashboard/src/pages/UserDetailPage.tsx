import { useState, useEffect, useCallback } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { ArrowLeftIcon, PencilIcon } from '@heroicons/react/20/solid';
import { getUser, updateUser, deleteUser, changePassword } from '../api/users';
import { getRoles } from '../api/roles';
import type { UserResponse, RoleResponse } from '../types';
import StatusBadge from '../components/StatusBadge';
import PermissionGate from '../components/PermissionGate';
import ConfirmDialog from '../components/ConfirmDialog';
import LoadingSpinner from '../components/LoadingSpinner';
import { useToast } from '../contexts/ToastContext';
import { AxiosError } from 'axios';
import type { ErrorResponse } from '../types';

export default function UserDetailPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const { addToast } = useToast();

  const [user, setUser] = useState<UserResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [isEditing, setIsEditing] = useState(false);

  // Edit form state
  const [editEmail, setEditEmail] = useState('');
  const [editFirstName, setEditFirstName] = useState('');
  const [editLastName, setEditLastName] = useState('');
  const [editRoleIds, setEditRoleIds] = useState<string[]>([]);
  const [allRoles, setAllRoles] = useState<RoleResponse[]>([]);
  const [isSaving, setIsSaving] = useState(false);

  // Password reset
  const [showPasswordForm, setShowPasswordForm] = useState(false);
  const [newPassword, setNewPassword] = useState('');
  const [isResettingPassword, setIsResettingPassword] = useState(false);

  // Deactivate dialog
  const [showDeactivateDialog, setShowDeactivateDialog] = useState(false);

  const fetchUser = useCallback(async () => {
    if (!id) return;
    setLoading(true);
    try {
      const data = await getUser(id);
      setUser(data);
      setEditEmail(data.email);
      setEditFirstName(data.first_name || '');
      setEditLastName(data.last_name || '');
      setEditRoleIds(data.roles.map((r) => r.id).filter((rid): rid is string => !!rid));
    } catch {
      addToast('error', 'Failed to load user');
      navigate('/users');
    } finally {
      setLoading(false);
    }
  }, [id, addToast, navigate]);

  useEffect(() => {
    fetchUser();
  }, [fetchUser]);

  useEffect(() => {
    getRoles({ size: 100 })
      .then((data) => setAllRoles(data.content))
      .catch(() => {});
  }, []);

  const handleSave = async () => {
    if (!id) return;
    setIsSaving(true);
    try {
      const updated = await updateUser(id, {
        email: editEmail.trim(),
        first_name: editFirstName.trim() || undefined,
        last_name: editLastName.trim() || undefined,
        role_ids: editRoleIds,
      });
      setUser(updated);
      setIsEditing(false);
      addToast('success', 'User updated successfully');
    } catch (err) {
      if (err instanceof AxiosError) {
        const data = err.response?.data as ErrorResponse | undefined;
        addToast('error', data?.error || 'Failed to update user');
      } else {
        addToast('error', 'Failed to update user');
      }
    } finally {
      setIsSaving(false);
    }
  };

  const handleDeactivate = async () => {
    if (!id) return;
    try {
      await deleteUser(id);
      addToast('success', 'User deactivated successfully');
      navigate('/users');
    } catch {
      addToast('error', 'Failed to deactivate user');
    }
    setShowDeactivateDialog(false);
  };

  const handlePasswordReset = async () => {
    if (!id || !newPassword) return;
    setIsResettingPassword(true);
    try {
      await changePassword(id, { new_password: newPassword });
      addToast('success', 'Password reset successfully');
      setShowPasswordForm(false);
      setNewPassword('');
    } catch {
      addToast('error', 'Failed to reset password');
    } finally {
      setIsResettingPassword(false);
    }
  };

  const toggleEditRole = (roleId: string) => {
    setEditRoleIds((prev) =>
      prev.includes(roleId) ? prev.filter((rid) => rid !== roleId) : [...prev, roleId],
    );
  };

  if (loading) {
    return <LoadingSpinner size="lg" className="mt-12" />;
  }

  if (!user) {
    return <p className="text-center text-gray-500 mt-12">User not found</p>;
  }

  return (
    <div>
      {/* Header */}
      <div className="mb-6">
        <button
          type="button"
          onClick={() => navigate('/users')}
          className="flex items-center text-sm text-gray-500 hover:text-gray-700 mb-4"
        >
          <ArrowLeftIcon className="h-4 w-4 mr-1" />
          Back to Users
        </button>
        <div className="flex items-center justify-between">
          <div>
            <h1 className="text-2xl font-bold tracking-tight text-gray-900">
              {user.first_name && user.last_name
                ? `${user.first_name} ${user.last_name}`
                : user.username}
            </h1>
            <p className="mt-1 text-sm text-gray-500">@{user.username}</p>
          </div>
          <div className="flex gap-3">
            <PermissionGate permission="USERS:UPDATE">
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
                    onClick={() => setIsEditing(false)}
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
            <PermissionGate permission="USERS:DELETE">
              {user.is_active && (
                <button
                  type="button"
                  onClick={() => setShowDeactivateDialog(true)}
                  className="btn-danger"
                >
                  Deactivate
                </button>
              )}
            </PermissionGate>
          </div>
        </div>
      </div>

      {/* User info card */}
      <div className="card">
        <dl className="grid grid-cols-1 gap-6 sm:grid-cols-2">
          <div>
            <dt className="text-sm font-medium text-gray-500">Username</dt>
            <dd className="mt-1 text-sm text-gray-900">{user.username}</dd>
          </div>

          <div>
            <dt className="text-sm font-medium text-gray-500">Email</dt>
            <dd className="mt-1 text-sm text-gray-900">
              {isEditing ? (
                <input
                  type="email"
                  value={editEmail}
                  onChange={(e) => setEditEmail(e.target.value)}
                  className="input-field"
                />
              ) : (
                user.email
              )}
            </dd>
          </div>

          <div>
            <dt className="text-sm font-medium text-gray-500">First Name</dt>
            <dd className="mt-1 text-sm text-gray-900">
              {isEditing ? (
                <input
                  type="text"
                  value={editFirstName}
                  onChange={(e) => setEditFirstName(e.target.value)}
                  className="input-field"
                />
              ) : (
                user.first_name || '--'
              )}
            </dd>
          </div>

          <div>
            <dt className="text-sm font-medium text-gray-500">Last Name</dt>
            <dd className="mt-1 text-sm text-gray-900">
              {isEditing ? (
                <input
                  type="text"
                  value={editLastName}
                  onChange={(e) => setEditLastName(e.target.value)}
                  className="input-field"
                />
              ) : (
                user.last_name || '--'
              )}
            </dd>
          </div>

          <div>
            <dt className="text-sm font-medium text-gray-500">Status</dt>
            <dd className="mt-1">
              <StatusBadge active={user.is_active} />
            </dd>
          </div>

          <div>
            <dt className="text-sm font-medium text-gray-500">Last Login</dt>
            <dd className="mt-1 text-sm text-gray-900">
              {user.last_login_ts ? new Date(user.last_login_ts).toLocaleString() : 'Never'}
            </dd>
          </div>

          <div>
            <dt className="text-sm font-medium text-gray-500">Created</dt>
            <dd className="mt-1 text-sm text-gray-900">
              {new Date(user.created_ts).toLocaleString()}
            </dd>
          </div>

          {user.updated_ts && (
            <div>
              <dt className="text-sm font-medium text-gray-500">Updated</dt>
              <dd className="mt-1 text-sm text-gray-900">
                {new Date(user.updated_ts).toLocaleString()}
              </dd>
            </div>
          )}
        </dl>
      </div>

      {/* Roles section */}
      <div className="card mt-6">
        <h2 className="text-lg font-semibold text-gray-900 mb-4">Roles</h2>
        {isEditing ? (
          <div className="space-y-2">
            {allRoles.map((role) => (
              <label
                key={role.id}
                className="flex items-center gap-3 rounded-md border border-gray-200 p-3 hover:bg-gray-50 cursor-pointer"
              >
                <input
                  type="checkbox"
                  checked={editRoleIds.includes(role.id)}
                  onChange={() => toggleEditRole(role.id)}
                  className="h-4 w-4 rounded border-gray-300 text-indigo-600 focus:ring-indigo-600"
                />
                <div>
                  <p className="text-sm font-medium text-gray-900">{role.name}</p>
                  <p className="text-xs text-gray-500">{role.code}</p>
                </div>
              </label>
            ))}
          </div>
        ) : (
          <div className="flex flex-wrap gap-2">
            {user.roles.map((role) => (
              <span
                key={role.code}
                className="inline-flex items-center rounded-full bg-indigo-50 px-3 py-1 text-sm font-medium text-indigo-700 ring-1 ring-inset ring-indigo-700/10"
              >
                {role.name}
              </span>
            ))}
            {user.roles.length === 0 && (
              <p className="text-sm text-gray-500">No roles assigned</p>
            )}
          </div>
        )}
      </div>

      {/* Password reset section */}
      <PermissionGate permission="USERS:UPDATE">
        <div className="card mt-6">
          <h2 className="text-lg font-semibold text-gray-900 mb-4">Password</h2>
          {!showPasswordForm ? (
            <button
              type="button"
              onClick={() => setShowPasswordForm(true)}
              className="btn-secondary"
            >
              Reset Password
            </button>
          ) : (
            <div className="max-w-md space-y-4">
              <div>
                <label htmlFor="newPassword" className="label">
                  New Password
                </label>
                <input
                  id="newPassword"
                  type="password"
                  value={newPassword}
                  onChange={(e) => setNewPassword(e.target.value)}
                  className="input-field mt-1"
                  placeholder="Min 8 characters"
                />
              </div>
              <div className="flex gap-3">
                <button
                  type="button"
                  onClick={() => {
                    setShowPasswordForm(false);
                    setNewPassword('');
                  }}
                  className="btn-secondary"
                >
                  Cancel
                </button>
                <button
                  type="button"
                  onClick={handlePasswordReset}
                  disabled={isResettingPassword || newPassword.length < 8}
                  className="btn-primary"
                >
                  {isResettingPassword && <LoadingSpinner size="sm" className="mr-2" />}
                  Reset Password
                </button>
              </div>
            </div>
          )}
        </div>
      </PermissionGate>

      {/* Deactivate confirmation dialog */}
      <ConfirmDialog
        open={showDeactivateDialog}
        title="Deactivate User"
        message={`Are you sure you want to deactivate user "${user.username}"? They will no longer be able to log in.`}
        confirmText="Deactivate"
        onConfirm={handleDeactivate}
        onCancel={() => setShowDeactivateDialog(false)}
      />
    </div>
  );
}
