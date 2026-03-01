import { useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { ArrowLeftIcon } from '@heroicons/react/20/solid';
import { createTenant } from '../api/tenants';
import { useToast } from '../contexts/ToastContext';
import LoadingSpinner from '../components/LoadingSpinner';
import { AxiosError } from 'axios';
import type { ErrorResponse } from '../types';

export default function TenantCreatePage() {
  const navigate = useNavigate();
  const { addToast } = useToast();

  // Tenant fields
  const [name, setName] = useState('');
  const [slug, setSlug] = useState('');
  const [maxUsers, setMaxUsers] = useState('100');
  const [maxRetentionDays, setMaxRetentionDays] = useState('90');

  // Admin user fields
  const [adminUsername, setAdminUsername] = useState('admin');
  const [adminEmail, setAdminEmail] = useState('');
  const [adminPassword, setAdminPassword] = useState('');
  const [adminFirstName, setAdminFirstName] = useState('');
  const [adminLastName, setAdminLastName] = useState('');

  const [isSubmitting, setIsSubmitting] = useState(false);
  const [errors, setErrors] = useState<Record<string, string>>({});

  const validate = (): boolean => {
    const newErrors: Record<string, string> = {};

    if (!name.trim()) {
      newErrors.name = 'Name is required';
    }
    if (!slug.trim() || !/^[a-z0-9-]+$/.test(slug)) {
      newErrors.slug = 'Slug must be lowercase alphanumeric with dashes';
    }
    if (!adminUsername.trim() || adminUsername.length < 3) {
      newErrors.adminUsername = 'Username must be at least 3 characters';
    }
    if (!adminEmail.trim() || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(adminEmail)) {
      newErrors.adminEmail = 'Valid email is required';
    }
    if (!adminPassword || adminPassword.length < 8) {
      newErrors.adminPassword = 'Password must be at least 8 characters';
    } else if (!/(?=.*[a-z])(?=.*[A-Z])(?=.*\d)/.test(adminPassword)) {
      newErrors.adminPassword = 'Must contain uppercase, lowercase, and a digit';
    }

    setErrors(newErrors);
    return Object.keys(newErrors).length === 0;
  };

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault();
    if (!validate()) return;

    setIsSubmitting(true);
    try {
      await createTenant({
        name: name.trim(),
        slug: slug.trim().toLowerCase(),
        settings: {
          max_users: parseInt(maxUsers) || 100,
          max_retention_days: parseInt(maxRetentionDays) || 90,
          features: {
            ocr_search: true,
            export_enabled: true,
          },
        },
        admin_user: {
          username: adminUsername.trim(),
          email: adminEmail.trim(),
          password: adminPassword,
          first_name: adminFirstName.trim() || undefined,
          last_name: adminLastName.trim() || undefined,
        },
      });

      addToast('success', 'Tenant created successfully');
      navigate('/tenants');
    } catch (err) {
      if (err instanceof AxiosError) {
        const data = err.response?.data as ErrorResponse | undefined;
        addToast('error', data?.error || 'Failed to create tenant');
      } else {
        addToast('error', 'An unexpected error occurred');
      }
    } finally {
      setIsSubmitting(false);
    }
  };

  const handleSlugChange = (value: string) => {
    setSlug(value.toLowerCase().replace(/[^a-z0-9-]/g, '-'));
  };

  return (
    <div>
      {/* Header */}
      <div className="mb-6">
        <button
          type="button"
          onClick={() => navigate('/tenants')}
          className="flex items-center text-sm text-gray-500 hover:text-gray-700 mb-4"
        >
          <ArrowLeftIcon className="h-4 w-4 mr-1" />
          Back to Tenants
        </button>
        <h1 className="text-2xl font-bold tracking-tight text-gray-900">Create Tenant</h1>
        <p className="mt-2 text-sm text-gray-600">
          Register a new organization and create its administrator account.
        </p>
      </div>

      <form onSubmit={handleSubmit}>
        {/* Tenant details */}
        <div className="card max-w-2xl">
          <h2 className="text-lg font-semibold text-gray-900 mb-4">Organization</h2>
          <div className="space-y-6">
            <div>
              <label htmlFor="name" className="label">
                Name *
              </label>
              <input
                id="name"
                type="text"
                value={name}
                onChange={(e) => setName(e.target.value)}
                className={`input-field mt-1 ${errors.name ? 'ring-red-300' : ''}`}
                placeholder="Company ABC"
              />
              {errors.name && <p className="mt-1 text-sm text-red-600">{errors.name}</p>}
            </div>

            <div>
              <label htmlFor="slug" className="label">
                Slug *
              </label>
              <input
                id="slug"
                type="text"
                value={slug}
                onChange={(e) => handleSlugChange(e.target.value)}
                className={`input-field mt-1 font-mono ${errors.slug ? 'ring-red-300' : ''}`}
                placeholder="company-abc"
              />
              {errors.slug && <p className="mt-1 text-sm text-red-600">{errors.slug}</p>}
              <p className="mt-1 text-xs text-gray-500">
                URL-safe identifier. Lowercase letters, numbers, and dashes only.
              </p>
            </div>

            <div className="grid grid-cols-2 gap-6">
              <div>
                <label htmlFor="maxUsers" className="label">
                  Max Users
                </label>
                <input
                  id="maxUsers"
                  type="number"
                  value={maxUsers}
                  onChange={(e) => setMaxUsers(e.target.value)}
                  className="input-field mt-1"
                  min="1"
                />
              </div>
              <div>
                <label htmlFor="maxRetention" className="label">
                  Retention (days)
                </label>
                <input
                  id="maxRetention"
                  type="number"
                  value={maxRetentionDays}
                  onChange={(e) => setMaxRetentionDays(e.target.value)}
                  className="input-field mt-1"
                  min="1"
                />
              </div>
            </div>
          </div>
        </div>

        {/* Admin user */}
        <div className="card max-w-2xl mt-6">
          <h2 className="text-lg font-semibold text-gray-900 mb-4">Administrator Account</h2>
          <div className="space-y-6">
            <div className="grid grid-cols-1 gap-6 sm:grid-cols-2">
              <div>
                <label htmlFor="adminUsername" className="label">
                  Username *
                </label>
                <input
                  id="adminUsername"
                  type="text"
                  value={adminUsername}
                  onChange={(e) => setAdminUsername(e.target.value)}
                  className={`input-field mt-1 ${errors.adminUsername ? 'ring-red-300' : ''}`}
                />
                {errors.adminUsername && (
                  <p className="mt-1 text-sm text-red-600">{errors.adminUsername}</p>
                )}
              </div>
              <div>
                <label htmlFor="adminEmail" className="label">
                  Email *
                </label>
                <input
                  id="adminEmail"
                  type="email"
                  value={adminEmail}
                  onChange={(e) => setAdminEmail(e.target.value)}
                  className={`input-field mt-1 ${errors.adminEmail ? 'ring-red-300' : ''}`}
                  placeholder="admin@company-abc.com"
                />
                {errors.adminEmail && (
                  <p className="mt-1 text-sm text-red-600">{errors.adminEmail}</p>
                )}
              </div>
            </div>

            <div>
              <label htmlFor="adminPassword" className="label">
                Password *
              </label>
              <input
                id="adminPassword"
                type="password"
                value={adminPassword}
                onChange={(e) => setAdminPassword(e.target.value)}
                className={`input-field mt-1 ${errors.adminPassword ? 'ring-red-300' : ''}`}
                placeholder="Min 8 chars, uppercase, lowercase, digit"
              />
              {errors.adminPassword && (
                <p className="mt-1 text-sm text-red-600">{errors.adminPassword}</p>
              )}
            </div>

            <div className="grid grid-cols-1 gap-6 sm:grid-cols-2">
              <div>
                <label htmlFor="adminFirstName" className="label">
                  First Name
                </label>
                <input
                  id="adminFirstName"
                  type="text"
                  value={adminFirstName}
                  onChange={(e) => setAdminFirstName(e.target.value)}
                  className="input-field mt-1"
                  placeholder="Admin"
                />
              </div>
              <div>
                <label htmlFor="adminLastName" className="label">
                  Last Name
                </label>
                <input
                  id="adminLastName"
                  type="text"
                  value={adminLastName}
                  onChange={(e) => setAdminLastName(e.target.value)}
                  className="input-field mt-1"
                  placeholder="User"
                />
              </div>
            </div>
          </div>
        </div>

        {/* Actions */}
        <div className="mt-6 flex justify-end gap-3 max-w-2xl">
          <button
            type="button"
            onClick={() => navigate('/tenants')}
            className="btn-secondary"
          >
            Cancel
          </button>
          <button type="submit" disabled={isSubmitting} className="btn-primary">
            {isSubmitting && <LoadingSpinner size="sm" className="mr-2" />}
            {isSubmitting ? 'Creating...' : 'Create Tenant'}
          </button>
        </div>
      </form>
    </div>
  );
}
