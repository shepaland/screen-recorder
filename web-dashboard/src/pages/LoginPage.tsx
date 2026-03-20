import { useState, useRef, useEffect, type FormEvent, type KeyboardEvent, type ClipboardEvent } from 'react';
import { useNavigate, useSearchParams, Navigate } from 'react-router-dom';
import { useAuth } from '../hooks/useAuth';
import { getOAuthLoginUrl, initiateEmailOtp, verifyEmailOtp, resendEmailOtp } from '../api/auth';
import LoadingSpinner from '../components/LoadingSpinner';
import { AxiosError } from 'axios';
import type { ErrorResponse } from '../types';

type LoginStep = 'credentials' | 'email-input' | 'email-code';

const ERROR_MESSAGES: Record<string, string> = {
  DISPOSABLE_EMAIL: 'Регистрация с временных email-адресов запрещена',
  OTP_SEND_RATE_LIMITED: 'Подождите перед повторной отправкой кода',
  OTP_SEND_LIMIT: 'Слишком много запросов кода. Попробуйте позже',
  OTP_INVALID: 'Неверный код подтверждения',
  OTP_EXPIRED: 'Код подтверждения истёк. Запросите новый',
  OTP_ALREADY_USED: 'Этот код уже был использован',
  OTP_VERIFY_BLOCKED: 'Слишком много попыток. Попробуйте через 30 минут',
  OTP_FINGERPRINT_MISMATCH: 'Код нужно ввести в том же браузере, в котором был запрошен',
  OTP_NOT_FOUND: 'Код не найден. Запросите новый',
  OTP_LOGIN_NOT_ALLOWED: 'Для этого аккаунта используйте вход по паролю',
  TENANT_LIMIT_REACHED: 'Достигнут лимит аккаунтов для этого email',
  EMAIL_DELIVERY_FAILED: 'Не удалось отправить email. Попробуйте позже',
  VALIDATION_ERROR: 'Проверьте введённые данные',
  TOO_MANY_REQUESTS: 'Слишком много запросов. Попробуйте позже',
};

function getErrorMessage(err: unknown): string {
  if (err instanceof AxiosError) {
    const data = err.response?.data as ErrorResponse | undefined;
    if (data?.code && ERROR_MESSAGES[data.code]) {
      return ERROR_MESSAGES[data.code];
    }
    if (data?.error) {
      return data.error;
    }
    if (err.response?.status === 429) {
      return 'Слишком много попыток. Попробуйте позже.';
    }
    if (err.response?.status === 401) {
      return 'Неверный email или пароль';
    }
    if (err.response?.status === 503) {
      return 'Сервис email временно недоступен. Попробуйте позже';
    }
  }
  return 'Произошла ошибка. Попробуйте ещё раз.';
}

export default function LoginPage() {
  const [step, setStep] = useState<LoginStep>('credentials');
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [email, setEmail] = useState('');
  const [codeId, setCodeId] = useState<string | null>(null);
  const [codeDigits, setCodeDigits] = useState<string[]>(['', '', '', '', '', '']);
  const [error, setError] = useState<string | null>(null);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [resendCountdown, setResendCountdown] = useState(0);

  const { login, loginWithEmail, isAuthenticated, isLoading } = useAuth();
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const codeInputRefs = useRef<(HTMLInputElement | null)[]>([]);

  // If already authenticated, redirect to home
  if (!isLoading && isAuthenticated) {
    return <Navigate to="/" replace />;
  }

  const errorMessage = searchParams.get('error');

  // Countdown timer for resend
  useEffect(() => {
    if (resendCountdown <= 0) return;
    const timer = setInterval(() => {
      setResendCountdown((prev) => {
        if (prev <= 1) {
          clearInterval(timer);
          return 0;
        }
        return prev - 1;
      });
    }, 1000);
    return () => clearInterval(timer);
  }, [resendCountdown]);

  // --- Password login ---
  const handlePasswordLogin = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    if (!username.trim()) { setError('Введите email'); return; }
    if (!password.trim()) { setError('Введите пароль'); return; }
    setIsSubmitting(true);
    try {
      await login({ username: username.trim(), password });
      const rawRedirect = searchParams.get('redirect') || '/';
      const redirectTo = rawRedirect.startsWith('/') && !rawRedirect.startsWith('//') ? rawRedirect : '/';
      navigate(redirectTo, { replace: true });
    } catch (err) {
      setError(getErrorMessage(err));
    } finally {
      setIsSubmitting(false);
    }
  };

  // --- Email OTP: initiate ---
  const handleEmailInitiate = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    const trimmedEmail = email.trim();
    if (!trimmedEmail) { setError('Введите email'); return; }
    setIsSubmitting(true);
    try {
      const response = await initiateEmailOtp({ email: trimmedEmail });
      setCodeId(response.code_id);
      setResendCountdown(response.resend_available_in);
      setCodeDigits(['', '', '', '', '', '']);
      setStep('email-code');
      // Focus first input after transition
      setTimeout(() => codeInputRefs.current[0]?.focus(), 100);
    } catch (err) {
      setError(getErrorMessage(err));
    } finally {
      setIsSubmitting(false);
    }
  };

  // --- Email OTP: verify ---
  const handleVerifyCode = async (codeString?: string) => {
    const code = codeString || codeDigits.join('');
    if (code.length !== 6 || !/^\d{6}$/.test(code)) {
      setError('Введите 6-значный код');
      return;
    }
    if (!codeId) return;
    setError(null);
    setIsSubmitting(true);
    try {
      const response = await verifyEmailOtp({ code_id: codeId, code });
      await loginWithEmail(response);
      const rawRedirect = searchParams.get('redirect') || '/';
      const redirectTo = rawRedirect.startsWith('/') && !rawRedirect.startsWith('//') ? rawRedirect : '/';
      navigate(redirectTo, { replace: true });
    } catch (err) {
      setError(getErrorMessage(err));
      // Clear code on error
      setCodeDigits(['', '', '', '', '', '']);
      setTimeout(() => codeInputRefs.current[0]?.focus(), 100);
    } finally {
      setIsSubmitting(false);
    }
  };

  // --- Email OTP: resend ---
  const handleResend = async () => {
    if (resendCountdown > 0 || !codeId) return;
    setError(null);
    setIsSubmitting(true);
    try {
      const response = await resendEmailOtp({ code_id: codeId });
      setCodeId(response.code_id);
      setResendCountdown(response.resend_available_in);
      setCodeDigits(['', '', '', '', '', '']);
      setTimeout(() => codeInputRefs.current[0]?.focus(), 100);
    } catch (err) {
      setError(getErrorMessage(err));
    } finally {
      setIsSubmitting(false);
    }
  };

  // --- Code input handlers ---
  const handleCodeDigitChange = (index: number, value: string) => {
    if (!/^\d?$/.test(value)) return;
    const newDigits = [...codeDigits];
    newDigits[index] = value;
    setCodeDigits(newDigits);

    if (value && index < 5) {
      codeInputRefs.current[index + 1]?.focus();
    }

    // Auto-submit when all 6 digits are filled
    if (value && index === 5) {
      const code = newDigits.join('');
      if (code.length === 6) {
        handleVerifyCode(code);
      }
    }
  };

  const handleCodeKeyDown = (index: number, e: KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'Backspace' && !codeDigits[index] && index > 0) {
      codeInputRefs.current[index - 1]?.focus();
    }
  };

  const handleCodePaste = (e: ClipboardEvent<HTMLInputElement>) => {
    e.preventDefault();
    const pasted = e.clipboardData.getData('text').replace(/\D/g, '').slice(0, 6);
    if (!pasted) return;
    const newDigits = [...codeDigits];
    for (let i = 0; i < pasted.length && i < 6; i++) {
      newDigits[i] = pasted[i];
    }
    setCodeDigits(newDigits);
    // Focus last filled or next empty
    const focusIdx = Math.min(pasted.length, 5);
    codeInputRefs.current[focusIdx]?.focus();
    // Auto-submit if 6 digits pasted
    if (pasted.length === 6) {
      handleVerifyCode(pasted);
    }
  };

  const handleYandexLogin = () => {
    window.location.href = getOAuthLoginUrl();
  };

  const formatCountdown = (seconds: number) => {
    const m = Math.floor(seconds / 60);
    const s = seconds % 60;
    return `${m}:${s.toString().padStart(2, '0')}`;
  };

  // --- Error display component ---
  const ErrorAlert = () => {
    const displayError = error || errorMessage;
    if (!displayError) return null;
    return (
      <div className="rounded-md bg-red-50 p-4">
        <div className="flex">
          <div className="flex-shrink-0">
            <svg className="h-5 w-5 text-red-400" viewBox="0 0 20 20" fill="currentColor">
              <path fillRule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zM8.28 7.22a.75.75 0 00-1.06 1.06L8.94 10l-1.72 1.72a.75.75 0 101.06 1.06L10 11.06l1.72 1.72a.75.75 0 101.06-1.06L11.06 10l1.72-1.72a.75.75 0 00-1.06-1.06L10 8.94 8.28 7.22z" clipRule="evenodd" />
            </svg>
          </div>
          <div className="ml-3">
            <p className="text-sm font-medium text-red-800">{displayError}</p>
          </div>
        </div>
      </div>
    );
  };

  // --- STEP: Email code input ---
  if (step === 'email-code') {
    return (
      <div className="card">
        <div className="space-y-6">
          <div>
            <h2 className="text-lg font-semibold text-gray-900">Проверка email</h2>
            <p className="mt-1 text-sm text-gray-600">
              Код отправлен на <span className="font-medium text-gray-900">{email}</span>
            </p>
          </div>

          <ErrorAlert />

          <div className="flex justify-center gap-2">
            {codeDigits.map((digit, index) => (
              <input
                key={index}
                ref={(el) => { codeInputRefs.current[index] = el; }}
                type="text"
                inputMode="numeric"
                maxLength={1}
                value={digit}
                onChange={(e) => handleCodeDigitChange(index, e.target.value)}
                onKeyDown={(e) => handleCodeKeyDown(index, e)}
                onPaste={index === 0 ? handleCodePaste : undefined}
                className="h-12 w-12 rounded-lg border border-gray-300 text-center text-xl font-semibold text-gray-900 focus:border-red-500 focus:ring-1 focus:ring-red-500 outline-none transition-colors"
                disabled={isSubmitting}
                autoComplete="one-time-code"
              />
            ))}
          </div>

          <button
            type="button"
            onClick={() => handleVerifyCode()}
            disabled={isSubmitting || codeDigits.join('').length !== 6}
            className="btn-primary w-full"
          >
            {isSubmitting ? (
              <LoadingSpinner size="sm" className="mr-2" />
            ) : null}
            {isSubmitting ? 'Проверка...' : 'Подтвердить'}
          </button>

          <div className="flex items-center justify-between text-sm">
            <button
              type="button"
              onClick={handleResend}
              disabled={resendCountdown > 0 || isSubmitting}
              className={`font-medium ${resendCountdown > 0 ? 'text-gray-400 cursor-not-allowed' : 'text-red-600 hover:text-red-500'}`}
            >
              {resendCountdown > 0
                ? `Отправить повторно (${formatCountdown(resendCountdown)})`
                : 'Отправить повторно'}
            </button>
            <button
              type="button"
              onClick={() => {
                setStep('email-input');
                setError(null);
                setCodeDigits(['', '', '', '', '', '']);
              }}
              className="font-medium text-gray-600 hover:text-gray-500"
            >
              Изменить email
            </button>
          </div>
        </div>
      </div>
    );
  }

  // --- STEP: Email input ---
  if (step === 'email-input') {
    return (
      <div className="card">
        <div className="space-y-6">
          <div>
            <h2 className="text-lg font-semibold text-gray-900">Вход по email</h2>
            <button
              type="button"
              onClick={() => { setStep('credentials'); setError(null); }}
              className="mt-1 text-sm text-gray-500 hover:text-gray-700 flex items-center gap-1"
            >
              <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" strokeWidth={1.5} stroke="currentColor">
                <path strokeLinecap="round" strokeLinejoin="round" d="M10.5 19.5L3 12m0 0l7.5-7.5M3 12h18" />
              </svg>
              Назад к выбору способа входа
            </button>
          </div>

          <ErrorAlert />

          <form onSubmit={handleEmailInitiate} className="space-y-4">
            <div>
              <label htmlFor="email" className="label">Email</label>
              <div className="mt-1">
                <input
                  id="email"
                  name="email"
                  type="email"
                  autoComplete="email"
                  required
                  value={email}
                  onChange={(e) => setEmail(e.target.value)}
                  className="input-field"
                  placeholder="ivan@company.ru"
                  autoFocus
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
              {isSubmitting ? 'Отправка...' : 'Получить код'}
            </button>
          </form>
        </div>
      </div>
    );
  }

  // --- STEP: Credentials (default) ---
  return (
    <div className="card">
      <div className="space-y-6">
        <ErrorAlert />

        {/* Password login form */}
        <form onSubmit={handlePasswordLogin} className="space-y-4">
          <div>
            <label htmlFor="username" className="label">Email</label>
            <div className="mt-1">
              <input
                id="username"
                name="username"
                type="email"
                autoComplete="email"
                required
                value={username}
                onChange={(e) => setUsername(e.target.value)}
                className="input-field"
                placeholder="user@company.ru"
              />
            </div>
          </div>

          <div>
            <label htmlFor="password" className="label">Пароль</label>
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

          <button type="submit" disabled={isSubmitting} className="btn-primary w-full">
            {isSubmitting ? <LoadingSpinner size="sm" className="mr-2" /> : null}
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
        <div className="space-y-3">
          <button
            type="button"
            onClick={handleYandexLogin}
            className="flex w-full items-center justify-center gap-3 rounded-lg bg-gray-950 px-4 py-3 text-sm font-semibold text-white shadow-sm hover:bg-gray-800 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-gray-950 transition-colors"
          >
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
