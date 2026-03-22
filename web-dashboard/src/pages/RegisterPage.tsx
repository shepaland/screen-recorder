import { useState, useEffect, useCallback } from 'react';
import { Link } from 'react-router-dom';
import { registerByEmail, resendVerification, getOAuthLoginUrl } from '../api/auth';

type Step = 'profile' | 'password' | 'confirmation';

const ERROR_MESSAGES: Record<string, string> = {
  DISPOSABLE_EMAIL: 'Регистрация с одноразовыми email-адресами запрещена',
  EMAIL_ALREADY_EXISTS: 'Этот email уже зарегистрирован',
  WEAK_PASSWORD: 'Пароль должен содержать минимум 8 символов, буквы и цифры',
  INVALID_COMPANY_NAME: 'Название компании должно быть от 2 до 200 символов',
  TOO_MANY_REQUESTS: 'Слишком много запросов. Попробуйте позже',
  EMAIL_DELIVERY_FAILED: 'Не удалось отправить письмо. Попробуйте позже',
  RESEND_LIMIT: 'Слишком много запросов. Попробуйте позже',
  CONFLICT: 'Этот email уже зарегистрирован',
};

function getPasswordStrength(password: string): { level: 'weak' | 'medium' | 'strong'; label: string; color: string; width: string } {
  const hasLetters = /[a-zA-Z]/.test(password);
  const hasDigits = /\d/.test(password);
  const hasSpecial = /[^a-zA-Z0-9]/.test(password);
  const len = password.length;

  if (len < 8 || !hasLetters || !hasDigits) {
    return { level: 'weak', label: 'Слабый', color: 'bg-red-500', width: 'w-1/3' };
  }
  if (len >= 12 || hasSpecial) {
    return { level: 'strong', label: 'Сильный', color: 'bg-green-500', width: 'w-full' };
  }
  return { level: 'medium', label: 'Средний', color: 'bg-yellow-500', width: 'w-2/3' };
}

function generatePassword(): string {
  const chars = 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789!@#$%&*';
  let password = '';
  for (let i = 0; i < 14; i++) {
    password += chars.charAt(Math.floor(Math.random() * chars.length));
  }
  // Ensure at least one letter, one digit, one special
  if (!/[a-zA-Z]/.test(password)) password = 'A' + password.slice(1);
  if (!/\d/.test(password)) password = password.slice(0, -1) + '7';
  return password;
}

export default function RegisterPage() {
  const [step, setStep] = useState<Step>('profile');
  const [email, setEmail] = useState('');
  const [firstName, setFirstName] = useState('');
  const [lastName, setLastName] = useState('');
  const [companyName, setCompanyName] = useState('');
  const [password, setPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [resendCountdown, setResendCountdown] = useState(0);

  // Countdown timer for resend
  useEffect(() => {
    if (resendCountdown <= 0) return;
    const timer = setTimeout(() => setResendCountdown(resendCountdown - 1), 1000);
    return () => clearTimeout(timer);
  }, [resendCountdown]);

  const handleProfileSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);

    if (!email.trim()) {
      setError('Введите email');
      return;
    }
    if (!companyName.trim() || companyName.trim().length < 2) {
      setError('Введите название компании (минимум 2 символа)');
      return;
    }
    setStep('password');
  };

  const handlePasswordSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);

    // Validate password
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
      await registerByEmail({
        email: email.trim(),
        password,
        first_name: firstName.trim() || undefined,
        last_name: lastName.trim() || undefined,
        company_name: companyName.trim(),
      });
      setResendCountdown(60);
      setStep('confirmation');
    } catch (err: unknown) {
      const apiError = err as { response?: { data?: { code?: string; error?: string } } };
      const code = apiError?.response?.data?.code;
      const msg = apiError?.response?.data?.error;
      setError(code && ERROR_MESSAGES[code] ? ERROR_MESSAGES[code] : msg || 'Ошибка регистрации');
    } finally {
      setIsSubmitting(false);
    }
  };

  const handleGeneratePassword = () => {
    const pwd = generatePassword();
    setPassword(pwd);
    setConfirmPassword(pwd);
    setShowPassword(true);
  };

  const handleResend = useCallback(async () => {
    if (resendCountdown > 0) return;
    setError(null);
    try {
      const result = await resendVerification(email);
      setResendCountdown(result.resend_available_in || 60);
    } catch (err: unknown) {
      const apiError = err as { response?: { data?: { code?: string; error?: string } } };
      const code = apiError?.response?.data?.code;
      const msg = apiError?.response?.data?.error;
      setError(code && ERROR_MESSAGES[code] ? ERROR_MESSAGES[code] : msg || 'Не удалось отправить письмо');
    }
  }, [email, resendCountdown]);

  const strength = getPasswordStrength(password);

  return (
    <div className="space-y-6">
      {/* Step 1: Profile */}
      {step === 'profile' && (
        <div className="card">
          <h2 className="text-lg font-semibold text-gray-900 text-center mb-4">Регистрация</h2>

          {error && (
            <div className="rounded-md bg-red-50 p-3 mb-4">
              <p className="text-sm text-red-700">{error}</p>
            </div>
          )}

          <form onSubmit={handleProfileSubmit} className="space-y-4">
            <div>
              <label htmlFor="email" className="label">Email <span className="text-red-500">*</span></label>
              <input
                id="email"
                type="email"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                className="input-field mt-1"
                placeholder="you@company.com"
                required
                autoFocus
              />
            </div>
            <div>
              <label htmlFor="firstName" className="label">Имя</label>
              <input
                id="firstName"
                type="text"
                value={firstName}
                onChange={(e) => setFirstName(e.target.value)}
                className="input-field mt-1"
                placeholder="Иван"
                maxLength={100}
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
                maxLength={100}
              />
            </div>
            <div>
              <label htmlFor="companyName" className="label">Название компании <span className="text-red-500">*</span></label>
              <input
                id="companyName"
                type="text"
                value={companyName}
                onChange={(e) => setCompanyName(e.target.value)}
                className="input-field mt-1"
                placeholder="Моя компания"
                required
                minLength={2}
                maxLength={200}
              />
            </div>

            <button type="submit" className="btn-primary w-full">
              Далее
            </button>
          </form>

          <div className="relative mt-6">
            <div className="absolute inset-0 flex items-center"><div className="w-full border-t border-gray-300" /></div>
            <div className="relative flex justify-center text-sm"><span className="bg-white px-2 text-gray-500">или</span></div>
          </div>

          <div className="mt-6">
            <a href={getOAuthLoginUrl()} className="flex w-full items-center justify-center gap-2 rounded-md bg-gray-900 px-4 py-2 text-sm font-semibold text-white shadow-sm hover:bg-gray-800 transition-colors">
              <svg className="h-5 w-5" viewBox="0 0 24 24" fill="currentColor"><path d="M12.76 13.83L7.64 24h4.58l1.1-2.31L12 18.06l3.31-6.54-2.55 2.31zm.91-1.82L24 0h-5.26l-5.07 9.56L12 12.01z" /></svg>
              Войти через Яндекс
            </a>
          </div>

          <p className="mt-6 text-center text-sm text-gray-500">
            Уже есть аккаунт?{' '}
            <Link to="/login" className="font-semibold text-red-600 hover:text-red-500">Войти</Link>
          </p>
        </div>
      )}

      {/* Step 2: Password */}
      {step === 'password' && (
        <div className="card">
          <h2 className="text-lg font-semibold text-gray-900 text-center mb-4">Придумайте пароль</h2>

          {error && (
            <div className="rounded-md bg-red-50 p-3 mb-4">
              <p className="text-sm text-red-700">{error}</p>
            </div>
          )}

          <form onSubmit={handlePasswordSubmit} className="space-y-4">
            <div>
              <label htmlFor="password" className="label">Пароль</label>
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
              {/* Strength indicator */}
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
              onClick={handleGeneratePassword}
              className="flex w-full items-center justify-center gap-2 text-sm text-red-600 hover:text-red-500 font-medium"
            >
              <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" strokeWidth={1.5} stroke="currentColor"><path strokeLinecap="round" strokeLinejoin="round" d="M15.75 5.25a3 3 0 013 3m3 0a6 6 0 01-7.029 5.912c-.563-.097-1.159.026-1.563.43L10.5 17.25H8.25v2.25H6v2.25H2.25v-2.818c0-.597.237-1.17.659-1.591l6.499-6.499c.404-.404.527-1 .43-1.563A6 6 0 1121.75 8.25z" /></svg>
              Сгенерировать пароль
            </button>

            <div className="flex gap-3">
              <button
                type="button"
                onClick={() => { setStep('profile'); setError(null); }}
                className="flex-1 rounded-md bg-white px-4 py-2 text-sm font-semibold text-gray-900 shadow-sm ring-1 ring-inset ring-gray-300 hover:bg-gray-50"
              >
                Назад
              </button>
              <button
                type="submit"
                disabled={isSubmitting}
                className="btn-primary flex-1"
              >
                {isSubmitting ? 'Регистрация...' : 'Зарегистрироваться'}
              </button>
            </div>
          </form>
        </div>
      )}

      {/* Step 3: Confirmation */}
      {step === 'confirmation' && (
        <div className="card text-center">
          <div className="mx-auto flex h-12 w-12 items-center justify-center rounded-full bg-red-100 mb-4">
            <svg className="h-6 w-6 text-red-600" fill="none" viewBox="0 0 24 24" strokeWidth={1.5} stroke="currentColor"><path strokeLinecap="round" strokeLinejoin="round" d="M21.75 6.75v10.5a2.25 2.25 0 01-2.25 2.25h-15a2.25 2.25 0 01-2.25-2.25V6.75m19.5 0A2.25 2.25 0 0019.5 4.5h-15a2.25 2.25 0 00-2.25 2.25m19.5 0v.243a2.25 2.25 0 01-1.07 1.916l-7.5 4.615a2.25 2.25 0 01-2.36 0L3.32 8.91a2.25 2.25 0 01-1.07-1.916V6.75" /></svg>
          </div>

          <h2 className="text-lg font-semibold text-gray-900 mb-2">Проверьте почту</h2>

          <p className="text-sm text-gray-600 mb-1">
            Мы отправили ссылку для подтверждения на
          </p>
          <p className="text-sm font-semibold text-gray-900 mb-4">{email}</p>

          <p className="text-xs text-gray-400 mb-6">Ссылка действительна 24 часа</p>

          {error && (
            <div className="rounded-md bg-red-50 p-3 mb-4">
              <p className="text-sm text-red-700">{error}</p>
            </div>
          )}

          <button
            onClick={handleResend}
            disabled={resendCountdown > 0}
            className="btn-primary w-full"
          >
            {resendCountdown > 0
              ? `Отправить повторно (${resendCountdown})`
              : 'Отправить повторно'}
          </button>

          <button
            onClick={() => { setStep('profile'); setError(null); }}
            className="mt-3 text-sm text-red-600 hover:text-red-500 font-medium"
          >
            Изменить email
          </button>
        </div>
      )}
    </div>
  );
}
