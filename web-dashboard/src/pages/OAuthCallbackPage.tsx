import { useEffect } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { useAuth } from '../hooks/useAuth';
import LoadingSpinner from '../components/LoadingSpinner';

export default function OAuthCallbackPage() {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const { setUserFromToken } = useAuth();

  useEffect(() => {
    const accessToken = searchParams.get('access_token');
    const oauthToken = searchParams.get('oauth_token');
    const status = searchParams.get('status');

    async function handleCallback() {
      if (accessToken) {
        // Single tenant -- auto-login
        try {
          await setUserFromToken(accessToken);
          navigate('/', { replace: true });
        } catch {
          navigate('/login?error=' + encodeURIComponent('Failed to authenticate'), { replace: true });
        }
      } else if (oauthToken) {
        if (status === 'needs_onboarding') {
          const name = searchParams.get('name') || '';
          const email = searchParams.get('email') || '';
          navigate(`/onboarding?oauth_token=${encodeURIComponent(oauthToken)}&name=${encodeURIComponent(name)}&email=${encodeURIComponent(email)}`, { replace: true });
        } else if (status === 'tenant_selection') {
          const tenants = searchParams.get('tenants') || '';
          navigate(`/select-tenant?oauth_token=${encodeURIComponent(oauthToken)}&tenants=${encodeURIComponent(tenants)}`, { replace: true });
        } else {
          // Fallback: try tenant selection
          navigate(`/select-tenant?oauth_token=${encodeURIComponent(oauthToken)}`, { replace: true });
        }
      } else {
        const error = searchParams.get('error');
        navigate('/login' + (error ? `?error=${encodeURIComponent(error)}` : ''), { replace: true });
      }
    }

    handleCallback();
  }, [searchParams, navigate, setUserFromToken]);

  return (
    <div className="flex min-h-screen items-center justify-center bg-gray-50">
      <div className="text-center">
        <LoadingSpinner size="lg" />
        <p className="mt-4 text-sm text-gray-600">Авторизация...</p>
      </div>
    </div>
  );
}
