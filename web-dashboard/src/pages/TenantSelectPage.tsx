import { useState, useEffect } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { selectTenant } from '../api/auth';
import { useAuth } from '../hooks/useAuth';
import LoadingSpinner from '../components/LoadingSpinner';
import { AxiosError } from 'axios';
import type { TenantPreview, ErrorResponse } from '../types';

const ROLE_LABELS: Record<string, string> = {
  OWNER: 'Владелец',
  MANAGER: 'Менеджер',
  ADMIN: 'Администратор',
  OPERATOR: 'Оператор',
  VIEWER: 'Наблюдатель',
  SUPER_ADMIN: 'Суперадмин',
};

const ROLE_COLORS: Record<string, string> = {
  OWNER: 'bg-purple-100 text-purple-800 ring-purple-600/20',
  MANAGER: 'bg-blue-100 text-blue-800 ring-blue-600/20',
  ADMIN: 'bg-indigo-100 text-indigo-800 ring-indigo-600/20',
  OPERATOR: 'bg-green-100 text-green-800 ring-green-600/20',
  VIEWER: 'bg-gray-100 text-gray-800 ring-gray-600/20',
  SUPER_ADMIN: 'bg-red-100 text-red-800 ring-red-600/20',
};

export default function TenantSelectPage() {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const { setUserFromToken } = useAuth();

  const oauthToken = searchParams.get('oauth_token') || '';
  const tenantsParam = searchParams.get('tenants');

  const [tenants, setTenants] = useState<TenantPreview[]>([]);
  const [loading, setLoading] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!oauthToken) {
      navigate('/login', { replace: true });
      return;
    }

    // Try to parse tenants from URL params (encoded JSON)
    if (tenantsParam) {
      try {
        const parsed = JSON.parse(decodeURIComponent(tenantsParam)) as TenantPreview[];
        setTenants(parsed);
      } catch {
        // If parsing fails, tenants will be empty
      }
    }
  }, [oauthToken, tenantsParam, navigate]);

  const handleSelectTenant = async (tenant: TenantPreview) => {
    setLoading(tenant.id);
    setError(null);

    try {
      const response = await selectTenant({
        tenant_id: tenant.id,
        oauth_token: oauthToken,
      });
      await setUserFromToken(response.access_token);
      navigate('/', { replace: true });
    } catch (err) {
      if (err instanceof AxiosError) {
        const data = err.response?.data as ErrorResponse | undefined;
        setError(data?.error || 'Не удалось войти в компанию. Попробуйте ещё раз.');
      } else {
        setError('Произошла непредвиденная ошибка.');
      }
    } finally {
      setLoading(null);
    }
  };

  return (
    <div className="flex min-h-screen flex-col items-center justify-center bg-gray-50 px-4 py-12 sm:px-6 lg:px-8">
      <div className="w-full max-w-lg">
        {/* Header */}
        <div className="mb-8 text-center">
          <h1 className="text-3xl font-bold tracking-tight text-gray-900">
            PRG Screen Recorder
          </h1>
          <h2 className="mt-3 text-xl font-semibold text-gray-700">
            Выберите компанию
          </h2>
          <p className="mt-2 text-sm text-gray-500">
            Вы состоите в нескольких компаниях. Выберите, в какую войти.
          </p>
        </div>

        {/* Error */}
        {error && (
          <div className="mb-6 rounded-md bg-red-50 p-4">
            <p className="text-sm font-medium text-red-800">{error}</p>
          </div>
        )}

        {/* Tenant cards */}
        {tenants.length > 0 ? (
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            {tenants.map((tenant) => (
              <div
                key={tenant.id}
                className="relative rounded-lg border border-gray-200 bg-white p-6 shadow-sm hover:border-indigo-300 hover:shadow-md transition-all"
              >
                <div className="mb-4">
                  <h3 className="text-lg font-semibold text-gray-900">{tenant.name}</h3>
                  <p className="mt-1 text-sm text-gray-500">{tenant.slug}</p>
                </div>
                <div className="mb-4">
                  <span
                    className={`inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-medium ring-1 ring-inset ${
                      ROLE_COLORS[tenant.role] || ROLE_COLORS.VIEWER
                    }`}
                  >
                    {ROLE_LABELS[tenant.role] || tenant.role}
                  </span>
                </div>
                <button
                  type="button"
                  disabled={loading !== null}
                  onClick={() => handleSelectTenant(tenant)}
                  className="btn-primary w-full"
                >
                  {loading === tenant.id ? (
                    <>
                      <LoadingSpinner size="sm" className="mr-2" />
                      Вход...
                    </>
                  ) : (
                    'Войти'
                  )}
                </button>
              </div>
            ))}
          </div>
        ) : (
          <div className="text-center">
            <LoadingSpinner size="lg" />
            <p className="mt-4 text-sm text-gray-500">Загрузка списка компаний...</p>
          </div>
        )}

        {/* Back */}
        <div className="mt-8 text-center">
          <button
            type="button"
            onClick={() => navigate('/login')}
            className="text-sm text-gray-500 hover:text-indigo-600 transition-colors"
          >
            &larr; Назад к входу
          </button>
        </div>
      </div>
    </div>
  );
}
