import { useNavigate, useSearchParams, Navigate } from 'react-router-dom';
import { getOAuthLoginUrl, getMailruOAuthLoginUrl } from '../api/auth';
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

  const handleMailruLogin = () => {
    window.location.href = getMailruOAuthLoginUrl();
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

        {/* Mail.ru OAuth button */}
        <button
          type="button"
          onClick={handleMailruLogin}
          className="flex w-full items-center justify-center gap-3 rounded-lg bg-[#005FF9] px-4 py-3 text-sm font-semibold text-white shadow-sm hover:bg-[#0052D9] focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[#005FF9] transition-colors"
        >
          <svg className="h-5 w-5" viewBox="0 0 24 24" fill="currentColor">
            <path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm4.65 6.35l-3.8 3.8c-.2.2-.45.3-.71.3s-.51-.1-.71-.3l-1.78-1.78a1.003 1.003 0 011.42-1.42L12 9.88l3.09-3.09c.2-.2.45-.3.71-.3.55 0 1 .45 1 1 0 .26-.1.51-.3.71l.15.15zM12 17.5c-2.33 0-4.31-1.46-5.11-3.5h1.6c.69 1.19 1.97 2 3.51 2s2.82-.81 3.51-2h1.6c-.8 2.04-2.78 3.5-5.11 3.5z"/>
          </svg>
          Войти через Mail.ru
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
