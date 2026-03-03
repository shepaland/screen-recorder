import { useState, type FormEvent } from 'react';
import { useNavigate, useSearchParams, Navigate } from 'react-router-dom';
import { useAuth } from '../hooks/useAuth';
import { getOAuthLoginUrl } from '../api/auth';
import LoadingSpinner from '../components/LoadingSpinner';
import { AxiosError } from 'axios';
import type { ErrorResponse } from '../types';

export default function LoginPage() {
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [isSubmitting, setIsSubmitting] = useState(false);

  const { login, isAuthenticated, isLoading } = useAuth();
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();

  // If already authenticated, redirect to home
  if (!isLoading && isAuthenticated) {
    return <Navigate to="/" replace />;
  }

  const errorMessage = searchParams.get('error');

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);

    if (!username.trim()) {
      setError('Введите имя пользователя');
      return;
    }
    if (!password.trim()) {
      setError('Введите пароль');
      return;
    }

    setIsSubmitting(true);

    try {
      await login({
        username: username.trim(),
        password,
      });

      const redirectTo = searchParams.get('redirect') || '/';
      navigate(redirectTo, { replace: true });
    } catch (err) {
      if (err instanceof AxiosError) {
        const data = err.response?.data as ErrorResponse | undefined;
        if (err.response?.status === 429) {
          setError('Слишком много попыток. Попробуйте позже.');
        } else if (data?.error) {
          setError(data.error);
        } else if (err.response?.status === 401) {
          setError('Неверное имя пользователя или пароль');
        } else if (err.response?.status === 404) {
          setError('Компания не найдена или неактивна');
        } else {
          setError('Произошла ошибка. Попробуйте ещё раз.');
        }
      } else {
        setError('Произошла ошибка. Попробуйте ещё раз.');
      }
    } finally {
      setIsSubmitting(false);
    }
  };

  const handleYandexLogin = () => {
    window.location.href = getOAuthLoginUrl();
  };

  return (
    <div className="card">
      <div className="space-y-6">
        {/* Error from OAuth callback */}
        {errorMessage && (
          <div className="rounded-md bg-red-50 p-3">
            <p className="text-sm text-red-700">{errorMessage}</p>
          </div>
        )}

        {/* Error from form submission */}
        {error && (
          <div className="rounded-md bg-red-50 p-4">
            <div className="flex">
              <div className="flex-shrink-0">
                <svg
                  className="h-5 w-5 text-red-400"
                  viewBox="0 0 20 20"
                  fill="currentColor"
                >
                  <path
                    fillRule="evenodd"
                    d="M10 18a8 8 0 100-16 8 8 0 000 16zM8.28 7.22a.75.75 0 00-1.06 1.06L8.94 10l-1.72 1.72a.75.75 0 101.06 1.06L10 11.06l1.72 1.72a.75.75 0 101.06-1.06L11.06 10l1.72-1.72a.75.75 0 00-1.06-1.06L10 8.94 8.28 7.22z"
                    clipRule="evenodd"
                  />
                </svg>
              </div>
              <div className="ml-3">
                <p className="text-sm font-medium text-red-800">{error}</p>
              </div>
            </div>
          </div>
        )}

        {/* Password login form */}
        <form onSubmit={handleSubmit} className="space-y-4">
          <div>
            <label htmlFor="username" className="label">
              Имя пользователя
            </label>
            <div className="mt-1">
              <input
                id="username"
                name="username"
                type="text"
                autoComplete="username"
                required
                value={username}
                onChange={(e) => setUsername(e.target.value)}
                className="input-field"
                placeholder="superadmin"
              />
            </div>
          </div>

          <div>
            <label htmlFor="password" className="label">
              Пароль
            </label>
            <div className="mt-1">
              <input
                id="password"
                name="password"
                type="password"
                autoComplete="current-password"
                required
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                className="input-field"
              />
            </div>
          </div>

          <button
            type="submit"
            disabled={isSubmitting}
            className="btn-primary w-full"
          >
            {isSubmitting ? (
              <LoadingSpinner size="sm" className="mr-2" />
            ) : null}
            {isSubmitting ? 'Вход...' : 'Войти'}
          </button>
        </form>

        {/* Divider */}
        <div className="relative">
          <div className="absolute inset-0 flex items-center">
            <div className="w-full border-t border-gray-200" />
          </div>
          <div className="relative flex justify-center text-sm">
            <span className="bg-white px-4 text-gray-500">или</span>
          </div>
        </div>

        {/* OAuth section */}
        <div>
          <p className="text-xs text-center text-gray-500 mb-3">
            Альтернативные способы входа
          </p>
          <button
            type="button"
            onClick={handleYandexLogin}
            className="flex w-full items-center justify-center gap-3 rounded-lg bg-gray-950 px-4 py-3 text-sm font-semibold text-white shadow-sm hover:bg-gray-800 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-gray-950 transition-colors"
          >
            {/* Yandex icon */}
            <svg className="h-5 w-5" viewBox="0 0 24 24" fill="currentColor">
              <path d="M13.32 7.666h-.924c-1.694 0-2.585.858-2.585 2.123 0 1.43.616 2.1 1.881 2.959l1.045.704-3.003 4.487H7.49l2.695-4.014c-1.55-1.111-2.42-2.19-2.42-3.925 0-2.321 1.606-3.891 4.485-3.891h3.24v11.83H13.32V7.666z" />
            </svg>
            Войти через Яндекс
          </button>
        </div>
      </div>
    </div>
  );
}
