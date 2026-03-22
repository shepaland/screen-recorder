import { useState } from 'react';
import { useParams, useNavigate, Link } from 'react-router-dom';
import { resetPassword } from '../api/auth';
import { useAuth } from '../hooks/useAuth';

function getPasswordStrength(password: string): { label: string; color: string; width: string } {
  const hasLetters = /[a-zA-Z]/.test(password);
  const hasDigits = /\d/.test(password);
  const hasSpecial = /[^a-zA-Z0-9]/.test(password);
  const len = password.length;

  if (len < 8 || !hasLetters || !hasDigits) {
    return { label: 'Слабый', color: 'bg-red-500', width: 'w-1/3' };
  }
  if (len >= 12 || hasSpecial) {
    return { label: 'Сильный', color: 'bg-green-500', width: 'w-full' };
  }
  return { label: 'Средний', color: 'bg-yellow-500', width: 'w-2/3' };
}

function generatePassword(): string {
  const chars = 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789!@#$%&*';
  let pwd = '';
  for (let i = 0; i < 14; i++) {
    pwd += chars.charAt(Math.floor(Math.random() * chars.length));
  }
  if (!/[a-zA-Z]/.test(pwd)) pwd = 'A' + pwd.slice(1);
  if (!/\d/.test(pwd)) pwd = pwd.slice(0, -1) + '7';
  return pwd;
}

const ERROR_MESSAGES: Record<string, string> = {
  TOKEN_NOT_FOUND: 'Ссылка для сброса недействительна',
  TOKEN_EXPIRED: 'Ссылка для сброса истекла',
  TOKEN_ALREADY_USED: 'Эта ссылка уже была использована',
  WEAK_PASSWORD: 'Пароль должен содержать минимум 8 символов, буквы и цифры',
};

export default function ResetPasswordPage() {
  const { token } = useParams<{ token: string }>();
  const navigate = useNavigate();
  const { refreshToken } = useAuth();
  const [password, setPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [isSubmitting, setIsSubmitting] = useState(false);

  if (!token) {
    return (
      <div className="card text-center">
        <p className="text-red-600">Недействительная ссылка</p>
        <Link to="/forgot-password" className="mt-4 btn-primary inline-block">Запросить новую ссылку</Link>
      </div>
    );
  }

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);

    if (password.length < 8) {
      setError('Пароль должен содержать минимум 8 символов');
      return;
    }
    if (!/[a-zA-Z]/.test(password)) {
      setError('Пароль должен содержать хотя бы одну букву');
      return;
    }
    if (!/\d/.test(password)) {
      setError('Пароль должен содержать хотя бы одну цифру');
      return;
    }
    if (password !== confirmPassword) {
      setError('Пароли не совпадают');
      return;
    }

    setIsSubmitting(true);
    try {
      await resetPassword(token, password);
      // Auto-login successful, refresh session and redirect
      await refreshToken();
      navigate('/', { replace: true });
    } catch (err: unknown) {
      const apiError = err as { response?: { data?: { code?: string; error?: string } } };
      const code = apiError?.response?.data?.code;
      const msg = apiError?.response?.data?.error;
      if (code === 'TOKEN_EXPIRED' || code === 'TOKEN_NOT_FOUND' || code === 'TOKEN_ALREADY_USED') {
        navigate('/reset-password-expired', { replace: true });
        return;
      }
      setError(code && ERROR_MESSAGES[code] ? ERROR_MESSAGES[code] : msg || 'Ошибка сброса пароля');
    } finally {
      setIsSubmitting(false);
    }
  };

  const handleGenerate = () => {
    const pwd = generatePassword();
    setPassword(pwd);
    setConfirmPassword(pwd);
    setShowPassword(true);
  };

  const strength = getPasswordStrength(password);

  return (
    <div className="card">
      <h2 className="text-lg font-semibold text-gray-900 text-center mb-4">Новый пароль</h2>

      {error && (
        <div className="rounded-md bg-red-50 p-3 mb-4">
          <p className="text-sm text-red-700">{error}</p>
        </div>
      )}

      <form onSubmit={handleSubmit} className="space-y-4">
        <div>
          <label htmlFor="password" className="label">Новый пароль</label>
          <div className="relative mt-1">
            <input
              id="password"
              type={showPassword ? 'text' : 'password'}
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              className="input-field pr-10"
              placeholder="Минимум 8 символов"
              required
              autoFocus
            />
            <button
              type="button"
              onClick={() => setShowPassword(!showPassword)}
              className="absolute inset-y-0 right-0 flex items-center pr-3 text-gray-400 hover:text-gray-600"
            >
              {showPassword ? (
                <svg className="h-5 w-5" fill="none" viewBox="0 0 24 24" strokeWidth={1.5} stroke="currentColor"><path strokeLinecap="round" strokeLinejoin="round" d="M3.98 8.223A10.477 10.477 0 001.934 12C3.226 16.338 7.244 19.5 12 19.5c.993 0 1.953-.138 2.863-.395M6.228 6.228A10.45 10.45 0 0112 4.5c4.756 0 8.773 3.162 10.065 7.498a10.523 10.523 0 01-4.293 5.774M6.228 6.228L3 3m3.228 3.228l3.65 3.65m7.894 7.894L21 21m-3.228-3.228l-3.65-3.65m0 0a3 3 0 10-4.243-4.243m4.242 4.242L9.88 9.88" /></svg>
              ) : (
                <svg className="h-5 w-5" fill="none" viewBox="0 0 24 24" strokeWidth={1.5} stroke="currentColor"><path strokeLinecap="round" strokeLinejoin="round" d="M2.036 12.322a1.012 1.012 0 010-.639C3.423 7.51 7.36 4.5 12 4.5c4.638 0 8.573 3.007 9.963 7.178.07.207.07.431 0 .639C20.577 16.49 16.64 19.5 12 19.5c-4.638 0-8.573-3.007-9.963-7.178z" /><path strokeLinecap="round" strokeLinejoin="round" d="M15 12a3 3 0 11-6 0 3 3 0 016 0z" /></svg>
              )}
            </button>
          </div>
          {password.length > 0 && (
            <div className="mt-2">
              <div className="h-1.5 w-full rounded-full bg-gray-200">
                <div className={`h-1.5 rounded-full transition-all ${strength.color} ${strength.width}`} />
              </div>
              <p className="mt-1 text-xs text-gray-500">{strength.label}</p>
            </div>
          )}
          <p className="mt-1 text-xs text-gray-400">Минимум 8 символов, буквы и цифры</p>
        </div>

        <div>
          <label htmlFor="confirmPassword" className="label">Подтверждение пароля</label>
          <input
            id="confirmPassword"
            type={showPassword ? 'text' : 'password'}
            value={confirmPassword}
            onChange={(e) => setConfirmPassword(e.target.value)}
            className="input-field mt-1"
            placeholder="Повторите пароль"
            required
          />
          {confirmPassword && password !== confirmPassword && (
            <p className="mt-1 text-xs text-red-500">Пароли не совпадают</p>
          )}
        </div>

        <button
          type="button"
          onClick={handleGenerate}
          className="flex w-full items-center justify-center gap-2 text-sm text-red-600 hover:text-red-500 font-medium"
        >
          <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" strokeWidth={1.5} stroke="currentColor"><path strokeLinecap="round" strokeLinejoin="round" d="M15.75 5.25a3 3 0 013 3m3 0a6 6 0 01-7.029 5.912c-.563-.097-1.159.026-1.563.43L10.5 17.25H8.25v2.25H6v2.25H2.25v-2.818c0-.597.237-1.17.659-1.591l6.499-6.499c.404-.404.527-1 .43-1.563A6 6 0 1121.75 8.25z" /></svg>
          Сгенерировать пароль
        </button>

        <button type="submit" disabled={isSubmitting} className="btn-primary w-full">
          {isSubmitting ? 'Сохранение...' : 'Сохранить пароль'}
        </button>
      </form>
    </div>
  );
}
