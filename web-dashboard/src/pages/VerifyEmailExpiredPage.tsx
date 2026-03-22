import { Link } from 'react-router-dom';

export default function VerifyEmailExpiredPage() {
  return (
    <div className="card text-center">
      <div className="mx-auto flex h-12 w-12 items-center justify-center rounded-full bg-red-100 mb-4">
        <svg className="h-6 w-6 text-red-600" fill="none" viewBox="0 0 24 24" strokeWidth={1.5} stroke="currentColor">
          <path strokeLinecap="round" strokeLinejoin="round" d="M12 9v3.75m9-.75a9 9 0 11-18 0 9 9 0 0118 0zm-9 3.75h.008v.008H12v-.008z" />
        </svg>
      </div>

      <h2 className="text-lg font-semibold text-gray-900 mb-2">Ссылка недействительна</h2>

      <p className="text-sm text-gray-600 mb-6">
        Ссылка для подтверждения email истекла или уже была использована. Зарегистрируйтесь заново.
      </p>

      <Link to="/register" className="btn-primary inline-block">
        Зарегистрироваться
      </Link>

      <p className="mt-4 text-sm text-gray-500">
        Уже есть аккаунт?{' '}
        <Link to="/login" className="font-semibold text-red-600 hover:text-red-500">Войти</Link>
      </p>
    </div>
  );
}
