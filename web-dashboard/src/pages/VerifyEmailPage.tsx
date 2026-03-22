import { useEffect, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { useAuth } from '../hooks/useAuth';

export default function VerifyEmailPage() {
  const { token } = useParams<{ token: string }>();
  const navigate = useNavigate();
  const { refreshToken } = useAuth();
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!token) {
      setError('Недействительная ссылка');
      return;
    }

    // The backend handles verification via redirect (GET /verify-email/{token}).
    // If the user lands here, it means the backend redirected them with a token.
    // Check URL params for the access token from the redirect.
    const params = new URLSearchParams(window.location.search);
    const accessToken = params.get('token');
    const verified = params.get('verified');

    if (verified === 'true' && accessToken) {
      // Token received from backend redirect — session established via cookie
      // Refresh to pick up the session
      refreshToken()
        .then(() => navigate('/', { replace: true }))
        .catch(() => navigate('/login', { replace: true }));
    } else {
      // User navigated directly to /verify-email/:token
      // Redirect to backend endpoint which will handle verification and redirect back
      const basePath = import.meta.env.BASE_URL.replace(/\/$/, '');
      window.location.href = `${basePath}/api/v1/auth/verify-email/${token}`;
    }
  }, [token, navigate, refreshToken]);

  if (error) {
    return (
      <div className="card text-center">
        <p className="text-red-600">{error}</p>
      </div>
    );
  }

  return (
    <div className="card text-center">
      <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-red-600 mx-auto mb-4" />
      <p className="text-sm text-gray-600">Подтверждаем email...</p>
    </div>
  );
}
