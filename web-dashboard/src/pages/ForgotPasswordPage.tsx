import { useState, useEffect, useCallback } from 'react';
import { Link } from 'react-router-dom';
import { forgotPassword, resendResetLink } from '../api/auth';

type Step = 'email' | 'sent';

export default function ForgotPasswordPage() {
  const [step, setStep] = useState<Step>('email');
  const [email, setEmail] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [resendCountdown, setResendCountdown] = useState(0);

  useEffect(() => {
    if (resendCountdown <= 0) return;
    const timer = setTimeout(() => setResendCountdown(resendCountdown - 1), 1000);
    return () => clearTimeout(timer);
  }, [resendCountdown]);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);

    if (!email.trim()) {
      setError('Введите email');
      return;
    }

    setIsSubmitting(true);
    try {
      await forgotPassword(email.trim());
      setResendCountdown(60);
      setStep('sent');
    } catch {
      setError('Не удалось отправить запрос. Попробуйте позже.');
    } finally {
      setIsSubmitting(false);
    }
  };

  const handleResend = useCallback(async () => {
    if (resendCountdown > 0) return;
    setError(null);
    try {
      await resendResetLink(email);
      setResendCountdown(60);
    } catch {
      setError('Не удалось отправить письмо. Попробуйте позже.');
    }
  }, [email, resendCountdown]);

  const maskedEmail = email.includes('@')
    ? email.slice(0, 2) + '***' + email.slice(email.indexOf('@'))
    : email;

  if (step === 'sent') {
    return (
      <div className="card text-center">
        <div className="mx-auto flex h-12 w-12 items-center justify-center rounded-full bg-red-100 mb-4">
          <svg className="h-6 w-6 text-red-600" fill="none" viewBox="0 0 24 24" strokeWidth={1.5} stroke="currentColor">
            <path strokeLinecap="round" strokeLinejoin="round" d="M21.75 6.75v10.5a2.25 2.25 0 01-2.25 2.25h-15a2.25 2.25 0 01-2.25-2.25V6.75m19.5 0A2.25 2.25 0 0019.5 4.5h-15a2.25 2.25 0 00-2.25 2.25m19.5 0v.243a2.25 2.25 0 01-1.07 1.916l-7.5 4.615a2.25 2.25 0 01-2.36 0L3.32 8.91a2.25 2.25 0 01-1.07-1.916V6.75" />
          </svg>
        </div>

        <h2 className="text-lg font-semibold text-gray-900 mb-2">Проверьте почту</h2>

        <p className="text-sm text-gray-600 mb-1">
          Если аккаунт с этим email существует, мы отправили ссылку для сброса пароля на
        </p>
        <p className="text-sm font-semibold text-gray-900 mb-4">{maskedEmail}</p>

        <p className="text-xs text-gray-400 mb-6">Ссылка действительна 1 час</p>

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

        <p className="mt-4 text-sm text-gray-500">
          <Link to="/login" className="font-semibold text-red-600 hover:text-red-500">
            &larr; Вернуться ко входу
          </Link>
        </p>
      </div>
    );
  }

  return (
    <div className="card">
      <h2 className="text-lg font-semibold text-gray-900 text-center mb-4">Восстановление пароля</h2>

      {error && (
        <div className="rounded-md bg-red-50 p-3 mb-4">
          <p className="text-sm text-red-700">{error}</p>
        </div>
      )}

      <form onSubmit={handleSubmit} className="space-y-4">
        <div>
          <label htmlFor="email" className="label">Email</label>
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

        <button type="submit" disabled={isSubmitting} className="btn-primary w-full">
          {isSubmitting ? 'Отправка...' : 'Отправить ссылку'}
        </button>
      </form>

      <p className="mt-6 text-center text-sm text-gray-500">
        <Link to="/login" className="font-semibold text-red-600 hover:text-red-500">
          &larr; Вернуться ко входу
        </Link>
      </p>
    </div>
  );
}
