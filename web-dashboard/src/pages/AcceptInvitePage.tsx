import { useState, useEffect } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import axios from 'axios';

const basePath = import.meta.env.BASE_URL.replace(/\/$/, '');
const API_BASE = `${basePath}/api/v1`;

export default function AcceptInvitePage() {
  const { token } = useParams<{ token: string }>();
  const navigate = useNavigate();

  const [invitation, setInvitation] = useState<{ email: string; tenant_id: string; expires_at: string } | null>(null);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);

  const [password, setPassword] = useState('');
  const [firstName, setFirstName] = useState('');
  const [lastName, setLastName] = useState('');
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    if (!token) return;
    axios.get(`${API_BASE}/auth/invite/${token}`)
      .then((res) => setInvitation(res.data))
      .catch((err) => {
        const msg = err?.response?.data?.error || 'Приглашение не найдено';
        setError(msg);
      })
      .finally(() => setLoading(false));
  }, [token]);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!token || !password) return;
    setSubmitting(true);
    try {
      const res = await axios.post(`${API_BASE}/auth/invite/${token}/accept`, {
        password,
        first_name: firstName,
        last_name: lastName,
      });
      if (res.data?.access_token) {
        localStorage.setItem('access_token', res.data.access_token);
      }
      navigate('/');
    } catch (err: any) {
      const msg = err?.response?.data?.error || 'Ошибка при принятии приглашения';
      setError(msg);
    } finally {
      setSubmitting(false);
    }
  };

  if (loading) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-gray-50">
        <p className="text-gray-500">Загрузка...</p>
      </div>
    );
  }

  if (error) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-gray-50">
        <div className="bg-white rounded-lg shadow-md p-8 max-w-md w-full text-center">
          <h1 className="text-xl font-semibold text-gray-900 mb-2">Приглашение</h1>
          <p className="text-red-600">{error}</p>
        </div>
      </div>
    );
  }

  return (
    <div className="min-h-screen flex items-center justify-center bg-gray-50">
      <div className="bg-white rounded-lg shadow-md p-8 max-w-md w-full">
        <div className="text-center mb-6">
          <h1 className="text-2xl font-bold text-gray-900">Кадеро</h1>
          <p className="text-gray-600 mt-2">Вас пригласили в организацию</p>
        </div>

        {invitation && (
          <p className="text-sm text-gray-500 mb-4 text-center">
            Email: <strong>{invitation.email}</strong>
          </p>
        )}

        <form onSubmit={handleSubmit} className="space-y-4">
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">Имя</label>
            <input
              type="text"
              value={firstName}
              onChange={(e) => setFirstName(e.target.value)}
              className="w-full border border-gray-300 rounded-md px-3 py-2 text-sm"
              placeholder="Иван"
            />
          </div>
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">Фамилия</label>
            <input
              type="text"
              value={lastName}
              onChange={(e) => setLastName(e.target.value)}
              className="w-full border border-gray-300 rounded-md px-3 py-2 text-sm"
              placeholder="Иванов"
            />
          </div>
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">Пароль</label>
            <input
              type="password"
              required
              minLength={6}
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              className="w-full border border-gray-300 rounded-md px-3 py-2 text-sm"
              placeholder="Минимум 6 символов"
            />
          </div>
          <button
            type="submit"
            disabled={submitting}
            className="w-full py-2 px-4 bg-red-600 text-white rounded-md font-medium hover:bg-red-700 disabled:opacity-50"
          >
            {submitting ? 'Регистрация...' : 'Зарегистрироваться и войти'}
          </button>
        </form>
      </div>
    </div>
  );
}
