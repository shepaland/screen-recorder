import { useNavigate, useSearchParams, Navigate } from 'react-router-dom';
import { getOAuthLoginUrl } from '../api/auth';
import { useAuth } from '../hooks/useAuth';

export default function OAuthLoginPage() {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const { isAuthenticated, isLoading } = useAuth();

  // If already authenticated, redirect to home (fixes Back button issue)
  if (!isLoading && isAuthenticated) {
    return <Navigate to="/" replace />;
  }

  const errorMessage = searchParams.get('error');

  const handleYandexLogin = () => {
    window.location.href = getOAuthLoginUrl();
  };

  return (
    <div className="card">
      <div className="space-y-6">
        {/* Branding */}
        <div className="text-center">
          <p className="text-sm text-gray-500">
            Платформа записи экранов
          </p>
        </div>

        {/* Error message from OAuth callback */}
        {errorMessage && (
          <div className="rounded-md bg-red-50 p-3">
            <p className="text-sm text-red-700">{errorMessage}</p>
          </div>
        )}

        {/* Yandex OAuth button */}
        <button
          type="button"
          onClick={handleYandexLogin}
          className="flex w-full items-center justify-center gap-3 rounded-lg bg-[#FC3F1D] px-4 py-3 text-sm font-semibold text-white shadow-sm hover:bg-[#E5391A] focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[#FC3F1D] transition-colors"
        >
          {/* Yandex icon */}
          <svg className="h-5 w-5" viewBox="0 0 24 24" fill="currentColor">
            <path d="M13.32 7.666h-.924c-1.694 0-2.585.858-2.585 2.123 0 1.43.616 2.1 1.881 2.959l1.045.704-3.003 4.487H7.49l2.695-4.014c-1.55-1.111-2.42-2.19-2.42-3.925 0-2.321 1.606-3.891 4.485-3.891h3.24v11.83H13.32V7.666z" />
          </svg>
          Войти через Яндекс
        </button>

        {/* Divider */}
        <div className="relative">
          <div className="absolute inset-0 flex items-center">
            <div className="w-full border-t border-gray-200" />
          </div>
          <div className="relative flex justify-center text-sm">
            <span className="bg-white px-4 text-gray-500">или</span>
          </div>
        </div>

        {/* Admin login link */}
        <div className="text-center">
          <button
            type="button"
            onClick={() => navigate('/login/admin')}
            className="text-sm text-gray-500 hover:text-indigo-600 transition-colors"
          >
            Вход для администратора
          </button>
        </div>
      </div>
    </div>
  );
}
