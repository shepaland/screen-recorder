import { Outlet, Navigate } from 'react-router-dom';
import { useAuth } from '../hooks/useAuth';
import LoadingSpinner from '../components/LoadingSpinner';

export default function AuthLayout() {
  const { isAuthenticated, isLoading } = useAuth();

  if (isLoading) {
    return (
      <div className="flex min-h-screen items-center justify-center bg-gray-50">
        <LoadingSpinner size="lg" />
      </div>
    );
  }

  // If already authenticated, redirect to dashboard
  if (isAuthenticated) {
    return <Navigate to="/" replace />;
  }

  return (
    <div className="flex min-h-screen flex-col items-center justify-center bg-gray-50 px-4 py-12 sm:px-6 lg:px-8">
      <div className="w-full max-w-md">
        <div className="mb-8 text-center">
          <div className="flex flex-col items-center">
            {/* Стилизованная К */}
            <svg width="80" height="80" viewBox="0 0 100 100" className="mb-4">
              <polygon points="20,10 40,10 40,40 70,10 95,10 55,50 95,90 70,90 40,60 40,90 20,90" fill="#dc2626" />
            </svg>
            {/* Текст КАДЕРО */}
            <h1 className="text-3xl font-bold tracking-[0.3em] text-gray-900">КАДЕРО</h1>
            <p className="mt-2 text-sm text-gray-600">Платформа записи экранов</p>
          </div>
        </div>
        <Outlet />
      </div>
    </div>
  );
}
