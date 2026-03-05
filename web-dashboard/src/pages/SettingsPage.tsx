import { useState, useEffect, type FormEvent } from 'react';
import { useAuth } from '../hooks/useAuth';
import { updateMySettings, updateMyProfile, setMyPassword } from '../api/auth';
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

  // Profile form state
  const [firstName, setFirstName] = useState('');
  const [lastName, setLastName] = useState('');
  const [isProfileSubmitting, setIsProfileSubmitting] = useState(false);

  // Password form state
  const [currentPassword, setCurrentPassword] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [isPasswordSubmitting, setIsPasswordSubmitting] = useState(false);

  useEffect(() => {
    if (user) {
      setFirstName(user.first_name || '');
      setLastName(user.last_name || '');
    }
    setIsLoaded(true);
  }, [user]);

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

  const handleSaveProfile = async (e: FormEvent) => {
    e.preventDefault();
    setIsProfileSubmitting(true);
    try {
      await updateMyProfile({
        first_name: firstName.trim() || undefined,
        last_name: lastName.trim() || undefined,
      });
      addToast('success', 'Профиль обновлён');
    } catch (err) {
      if (err instanceof AxiosError) {
        const data = err.response?.data as ErrorResponse | undefined;
        addToast('error', data?.error || 'Не удалось обновить профиль');
      } else {
        addToast('error', 'Произошла непредвиденная ошибка');
      }
    } finally {
      setIsProfileSubmitting(false);
    }
  };

  const handleSetPassword = async (e: FormEvent) => {
    e.preventDefault();
    if (newPassword !== confirmPassword) {
      addToast('error', 'Пароли не совпадают');
      return;
    }
    if (newPassword.length < 8) {
      addToast('error', 'Пароль должен быть не менее 8 символов');
      return;
    }
    setIsPasswordSubmitting(true);
    try {
      await setMyPassword({
        current_password: user?.is_password_set ? currentPassword : undefined,
        new_password: newPassword,
      });
      addToast('success', user?.is_password_set ? 'Пароль изменён' : 'Пароль установлен');
      setCurrentPassword('');
      setNewPassword('');
      setConfirmPassword('');
    } catch (err) {
      if (err instanceof AxiosError) {
        const data = err.response?.data as ErrorResponse | undefined;
        const code = data?.code;
        if (code === 'INVALID_CURRENT_PASSWORD') {
          addToast('error', 'Текущий пароль неверен');
        } else if (code === 'CURRENT_PASSWORD_REQUIRED') {
          addToast('error', 'Введите текущий пароль');
        } else {
          addToast('error', data?.error || 'Не удалось установить пароль');
        }
      } else {
        addToast('error', 'Произошла непредвиденная ошибка');
      }
    } finally {
      setIsPasswordSubmitting(false);
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
  const isEmailUser = user.auth_provider === 'email';
  const showPasswordSetup = user.is_password_set === false;
  const showProfileSetup = !user.first_name;

  return (
    <div>
      <h1 className="text-2xl font-bold tracking-tight text-gray-900">Настройки</h1>
      <p className="mt-2 text-sm text-gray-600">
        Управление настройками аккаунта и сессии.
      </p>

      {/* Onboarding banner */}
      {(showPasswordSetup || showProfileSetup) && (
        <div className="mt-4 rounded-lg border border-blue-200 bg-blue-50 p-4">
          <div className="flex">
            <div className="flex-shrink-0">
              <svg className="h-5 w-5 text-blue-400" viewBox="0 0 20 20" fill="currentColor">
                <path fillRule="evenodd" d="M18 10a8 8 0 11-16 0 8 8 0 0116 0zm-7-4a1 1 0 11-2 0 1 1 0 012 0zM9 9a.75.75 0 000 1.5h.253a.25.25 0 01.244.304l-.459 2.066A1.75 1.75 0 0010.747 15H11a.75.75 0 000-1.5h-.253a.25.25 0 01-.244-.304l.459-2.066A1.75 1.75 0 009.253 9H9z" clipRule="evenodd" />
              </svg>
            </div>
            <div className="ml-3">
              <p className="text-sm text-blue-700">
                {showProfileSetup && showPasswordSetup
                  ? 'Заполните профиль и установите пароль для дополнительной безопасности'
                  : showProfileSetup
                    ? 'Заполните профиль (имя и фамилию)'
                    : 'Установите пароль для входа по паролю'}
              </p>
            </div>
          </div>
        </div>
      )}

      {/* Profile section */}
      <div className={`card mt-6 max-w-2xl ${showProfileSetup ? 'ring-2 ring-blue-200' : ''}`}>
        <h2 className="text-lg font-semibold text-gray-900 mb-4">
          Профиль
          {showProfileSetup && (
            <span className="ml-2 inline-flex items-center rounded-full bg-blue-100 px-2 py-0.5 text-xs font-medium text-blue-700">
              Заполните
            </span>
          )}
        </h2>

        {/* Read-only info */}
        <div className="mb-4 flex items-start gap-6">
          {isOAuthUser && user.avatar_url ? (
            <img src={user.avatar_url} alt="Аватар" className="h-16 w-16 rounded-full ring-2 ring-gray-200" />
          ) : (
            <div className="flex h-16 w-16 items-center justify-center rounded-full bg-red-600 text-xl font-semibold text-white">
              {user.first_name?.[0] || user.email?.[0]?.toUpperCase() || 'U'}
            </div>
          )}
          <dl className="grid flex-1 grid-cols-1 gap-3 sm:grid-cols-2">
            <div>
              <dt className="text-sm font-medium text-gray-500">Email</dt>
              <dd className="mt-1 text-sm text-gray-900">{user.email}</dd>
            </div>
            <div>
              <dt className="text-sm font-medium text-gray-500">Авторизация</dt>
              <dd className="mt-1">
                <span className={`inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-medium ring-1 ring-inset ${
                  isOAuthUser ? 'bg-yellow-50 text-yellow-800 ring-yellow-600/20'
                  : isEmailUser ? 'bg-green-50 text-green-800 ring-green-600/20'
                  : 'bg-gray-50 text-gray-800 ring-gray-600/20'
                }`}>
                  {isOAuthUser ? 'Яндекс OAuth' : isEmailUser ? 'Email' : 'Пароль'}
                </span>
              </dd>
            </div>
            <div>
              <dt className="text-sm font-medium text-gray-500">Роли</dt>
              <dd className="mt-1">
                <div className="flex flex-wrap gap-1">
                  {user.roles.map((role) => (
                    <span key={role} className="inline-flex items-center rounded-full bg-red-50 px-2.5 py-0.5 text-xs font-medium text-red-700 ring-1 ring-inset ring-red-700/10">
                      {role}
                    </span>
                  ))}
                </div>
              </dd>
            </div>
          </dl>
        </div>

        {/* Editable name fields */}
        <form onSubmit={handleSaveProfile} className="space-y-4 border-t border-gray-200 pt-4">
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            <div>
              <label htmlFor="firstName" className="label">Имя</label>
              <input
                id="firstName"
                type="text"
                value={firstName}
                onChange={(e) => setFirstName(e.target.value)}
                className="input-field mt-1"
                placeholder="Иван"
              />
            </div>
            <div>
              <label htmlFor="lastName" className="label">Фамилия</label>
              <input
                id="lastName"
                type="text"
                value={lastName}
                onChange={(e) => setLastName(e.target.value)}
                className="input-field mt-1"
                placeholder="Петров"
              />
            </div>
          </div>
          <div className="flex justify-end">
            <button type="submit" disabled={isProfileSubmitting} className="btn-primary">
              {isProfileSubmitting && <LoadingSpinner size="sm" className="mr-2" />}
              {isProfileSubmitting ? 'Сохранение...' : 'Сохранить профиль'}
            </button>
          </div>
        </form>
      </div>

      {/* Password section */}
      <div className={`card mt-6 max-w-2xl ${showPasswordSetup ? 'ring-2 ring-blue-200' : ''}`}>
        <h2 className="text-lg font-semibold text-gray-900 mb-4">
          {showPasswordSetup ? 'Установить пароль' : 'Изменить пароль'}
          {showPasswordSetup && (
            <span className="ml-2 inline-flex items-center rounded-full bg-blue-100 px-2 py-0.5 text-xs font-medium text-blue-700">
              Рекомендуется
            </span>
          )}
        </h2>
        {showPasswordSetup && (
          <p className="text-sm text-gray-600 mb-4">
            Установите пароль, чтобы входить в систему без кода подтверждения.
          </p>
        )}
        <form onSubmit={handleSetPassword} className="space-y-4">
          {user.is_password_set && (
            <div>
              <label htmlFor="currentPassword" className="label">Текущий пароль</label>
              <input
                id="currentPassword"
                type="password"
                value={currentPassword}
                onChange={(e) => setCurrentPassword(e.target.value)}
                className="input-field mt-1 max-w-sm"
                autoComplete="current-password"
              />
            </div>
          )}
          <div>
            <label htmlFor="newPassword" className="label">
              {showPasswordSetup ? 'Пароль' : 'Новый пароль'}
            </label>
            <input
              id="newPassword"
              type="password"
              value={newPassword}
              onChange={(e) => setNewPassword(e.target.value)}
              className="input-field mt-1 max-w-sm"
              autoComplete="new-password"
              minLength={8}
              placeholder="Минимум 8 символов"
            />
          </div>
          <div>
            <label htmlFor="confirmPassword" className="label">Подтвердите пароль</label>
            <input
              id="confirmPassword"
              type="password"
              value={confirmPassword}
              onChange={(e) => setConfirmPassword(e.target.value)}
              className="input-field mt-1 max-w-sm"
              autoComplete="new-password"
              minLength={8}
            />
          </div>
          <div className="flex justify-end">
            <button
              type="submit"
              disabled={isPasswordSubmitting || !newPassword || !confirmPassword}
              className="btn-primary"
            >
              {isPasswordSubmitting && <LoadingSpinner size="sm" className="mr-2" />}
              {isPasswordSubmitting ? 'Сохранение...' : showPasswordSetup ? 'Установить пароль' : 'Изменить пароль'}
            </button>
          </div>
        </form>
      </div>

      {/* Session TTL settings */}
      <div className="card mt-6 max-w-2xl">
        <h2 className="text-lg font-semibold text-gray-900 mb-4">Настройки сессии</h2>
        <form onSubmit={handleSaveSession} className="space-y-4">
          <div>
            <label htmlFor="sessionTtl" className="label">Время жизни сессии</label>
            <select
              id="sessionTtl"
              value={sessionTtlDays}
              onChange={(e) => setSessionTtlDays(Number(e.target.value))}
              className="input-field mt-1 max-w-xs"
            >
              {SESSION_TTL_OPTIONS.filter((opt) => opt.value <= maxSessionTtlDays).map((opt) => (
                <option key={opt.value} value={opt.value}>{opt.label}</option>
              ))}
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
