import { useState, useEffect, type FormEvent } from 'react';
import { useAuth } from '../hooks/useAuth';
import { updateMySettings } from '../api/auth';
import { useToast } from '../contexts/ToastContext';
import LoadingSpinner from '../components/LoadingSpinner';
import { AxiosError } from 'axios';
import type { ErrorResponse } from '../types';

const SESSION_TTL_OPTIONS = [
  { label: '7 дней', value: 7 },
  { label: '14 дней', value: 14 },
  { label: '30 дней', value: 30 },
  { label: '60 дней', value: 60 },
  { label: '90 дней', value: 90 },
];

export default function SettingsPage() {
  const { user } = useAuth();
  const { addToast } = useToast();

  const [sessionTtlDays, setSessionTtlDays] = useState<number>(30);
  const [maxSessionTtlDays, setMaxSessionTtlDays] = useState<number>(90);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [isLoaded, setIsLoaded] = useState(false);

  // Load current settings on mount
  useEffect(() => {
    // We start with defaults; the actual settings come from user or a dedicated endpoint
    // For now, set reasonable defaults
    setIsLoaded(true);
  }, []);

  const handleSaveSession = async (e: FormEvent) => {
    e.preventDefault();
    setIsSubmitting(true);

    try {
      const response = await updateMySettings({ session_ttl_days: sessionTtlDays });
      setMaxSessionTtlDays(response.tenant_max_session_ttl_days);
      addToast('success', 'Настройки сессии сохранены');
    } catch (err) {
      if (err instanceof AxiosError) {
        const data = err.response?.data as ErrorResponse | undefined;
        addToast('error', data?.error || 'Не удалось сохранить настройки');
      } else {
        addToast('error', 'Произошла непредвиденная ошибка');
      }
    } finally {
      setIsSubmitting(false);
    }
  };

  if (!user || !isLoaded) {
    return (
      <div className="flex items-center justify-center py-12">
        <LoadingSpinner size="lg" />
      </div>
    );
  }

  const isOAuthUser = user.auth_provider === 'oauth';

  return (
    <div>
      <h1 className="text-2xl font-bold tracking-tight text-gray-900">Настройки</h1>
      <p className="mt-2 text-sm text-gray-600">
        Управление настройками аккаунта и сессии.
      </p>

      {/* Profile section (OAuth users) */}
      {isOAuthUser && (
        <div className="card mt-6 max-w-2xl">
          <h2 className="text-lg font-semibold text-gray-900 mb-4">Профиль</h2>
          <div className="flex items-start gap-6">
            {/* Avatar */}
            {user.avatar_url ? (
              <img
                src={user.avatar_url}
                alt="Аватар"
                className="h-16 w-16 rounded-full ring-2 ring-gray-200"
              />
            ) : (
              <div className="flex h-16 w-16 items-center justify-center rounded-full bg-indigo-600 text-xl font-semibold text-white">
                {user.first_name?.[0] || user.username?.[0] || 'U'}
              </div>
            )}

            <dl className="grid flex-1 grid-cols-1 gap-4 sm:grid-cols-2">
              <div>
                <dt className="text-sm font-medium text-gray-500">Email</dt>
                <dd className="mt-1 text-sm text-gray-900">{user.email}</dd>
              </div>
              <div>
                <dt className="text-sm font-medium text-gray-500">Имя</dt>
                <dd className="mt-1 text-sm text-gray-900">
                  {user.first_name && user.last_name
                    ? `${user.first_name} ${user.last_name}`
                    : user.first_name || '--'}
                </dd>
              </div>
              <div>
                <dt className="text-sm font-medium text-gray-500">Роли</dt>
                <dd className="mt-1">
                  <div className="flex flex-wrap gap-1">
                    {user.roles.map((role) => (
                      <span
                        key={role}
                        className="inline-flex items-center rounded-full bg-indigo-50 px-2.5 py-0.5 text-xs font-medium text-indigo-700 ring-1 ring-inset ring-indigo-700/10"
                      >
                        {role}
                      </span>
                    ))}
                  </div>
                </dd>
              </div>
              <div>
                <dt className="text-sm font-medium text-gray-500">Авторизация</dt>
                <dd className="mt-1">
                  <span className="inline-flex items-center rounded-full bg-yellow-50 px-2.5 py-0.5 text-xs font-medium text-yellow-800 ring-1 ring-inset ring-yellow-600/20">
                    Яндекс OAuth
                  </span>
                </dd>
              </div>
            </dl>
          </div>
        </div>
      )}

      {/* Session TTL settings */}
      <div className="card mt-6 max-w-2xl">
        <h2 className="text-lg font-semibold text-gray-900 mb-4">Настройки сессии</h2>
        <form onSubmit={handleSaveSession} className="space-y-4">
          <div>
            <label htmlFor="sessionTtl" className="label">
              Время жизни сессии
            </label>
            <select
              id="sessionTtl"
              value={sessionTtlDays}
              onChange={(e) => setSessionTtlDays(Number(e.target.value))}
              className="input-field mt-1 max-w-xs"
            >
              {SESSION_TTL_OPTIONS.filter((opt) => opt.value <= maxSessionTtlDays).map(
                (opt) => (
                  <option key={opt.value} value={opt.value}>
                    {opt.label}
                  </option>
                ),
              )}
            </select>
            <p className="mt-1 text-xs text-gray-500">
              Максимум для вашей компании: {maxSessionTtlDays} дней
            </p>
          </div>

          <div className="flex justify-end">
            <button type="submit" disabled={isSubmitting} className="btn-primary">
              {isSubmitting && <LoadingSpinner size="sm" className="mr-2" />}
              {isSubmitting ? 'Сохранение...' : 'Сохранить'}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
