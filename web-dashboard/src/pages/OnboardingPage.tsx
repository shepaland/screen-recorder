import { useState, useEffect, type FormEvent } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { onboarding } from '../api/auth';
import { useAuth } from '../hooks/useAuth';
import LoadingSpinner from '../components/LoadingSpinner';
import { AxiosError } from 'axios';
import type { ErrorResponse } from '../types';

/**
 * Generate a slug from a company name.
 * Transliterates Cyrillic to Latin, lowercases, replaces non-alphanumeric with hyphens.
 */
function generateSlug(name: string): string {
  const translitMap: Record<string, string> = {
    'а': 'a', 'б': 'b', 'в': 'v', 'г': 'g', 'д': 'd', 'е': 'e', 'ё': 'yo',
    'ж': 'zh', 'з': 'z', 'и': 'i', 'й': 'y', 'к': 'k', 'л': 'l', 'м': 'm',
    'н': 'n', 'о': 'o', 'п': 'p', 'р': 'r', 'с': 's', 'т': 't', 'у': 'u',
    'ф': 'f', 'х': 'kh', 'ц': 'ts', 'ч': 'ch', 'ш': 'sh', 'щ': 'shch',
    'ъ': '', 'ы': 'y', 'ь': '', 'э': 'e', 'ю': 'yu', 'я': 'ya',
  };

  return name
    .toLowerCase()
    .split('')
    .map((char) => translitMap[char] ?? char)
    .join('')
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-|-$/g, '')
    .slice(0, 63);
}

export default function OnboardingPage() {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const { setUserFromToken } = useAuth();

  const oauthToken = searchParams.get('oauth_token') || '';
  const prefillName = searchParams.get('name') || '';

  const [companyName, setCompanyName] = useState('');
  const [slug, setSlug] = useState('');
  const [slugEdited, setSlugEdited] = useState(false);
  const [firstName, setFirstName] = useState('');
  const [lastName, setLastName] = useState('');
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Prefill name from URL params
  useEffect(() => {
    if (prefillName) {
      const parts = prefillName.trim().split(/\s+/);
      if (parts.length >= 2) {
        setFirstName(parts[0]);
        setLastName(parts.slice(1).join(' '));
      } else if (parts.length === 1) {
        setFirstName(parts[0]);
      }
    }
  }, [prefillName]);

  // Auto-generate slug from company name
  useEffect(() => {
    if (!slugEdited) {
      setSlug(generateSlug(companyName));
    }
  }, [companyName, slugEdited]);

  useEffect(() => {
    if (!oauthToken) {
      navigate('/login', { replace: true });
    }
  }, [oauthToken, navigate]);

  const handleSlugChange = (value: string) => {
    setSlugEdited(true);
    // Only allow valid slug characters
    setSlug(value.toLowerCase().replace(/[^a-z0-9-]/g, ''));
  };

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);

    if (!companyName.trim()) {
      setError('Название компании обязательно');
      return;
    }
    if (!slug.trim() || slug.length < 3) {
      setError('Slug должен быть не менее 3 символов');
      return;
    }

    setIsSubmitting(true);

    try {
      const response = await onboarding(
        {
          company_name: companyName.trim(),
          slug: slug.trim(),
          first_name: firstName.trim() || undefined,
          last_name: lastName.trim() || undefined,
        },
        oauthToken,
      );
      await setUserFromToken(response.access_token);
      navigate('/', { replace: true });
    } catch (err) {
      if (err instanceof AxiosError) {
        const data = err.response?.data as ErrorResponse | undefined;
        if (err.response?.status === 409) {
          setError('Компания с таким slug уже существует. Выберите другой.');
        } else {
          setError(data?.error || 'Не удалось создать компанию. Попробуйте ещё раз.');
        }
      } else {
        setError('Произошла непредвиденная ошибка.');
      }
    } finally {
      setIsSubmitting(false);
    }
  };

  return (
    <div className="flex min-h-screen flex-col items-center justify-center bg-gray-50 px-4 py-12 sm:px-6 lg:px-8">
      <div className="w-full max-w-md">
        {/* Header */}
        <div className="mb-8 text-center">
          <h1 className="text-3xl font-bold tracking-tight text-gray-900">
            PRG Screen Recorder
          </h1>
          <h2 className="mt-3 text-xl font-semibold text-gray-700">
            Создайте компанию
          </h2>
          <p className="mt-2 text-sm text-gray-500">
            Добро пожаловать! Создайте свою первую компанию для начала работы.
          </p>
        </div>

        <div className="card">
          <form className="space-y-6" onSubmit={handleSubmit}>
            {/* Error */}
            {error && (
              <div className="rounded-md bg-red-50 p-4">
                <div className="flex">
                  <div className="flex-shrink-0">
                    <svg
                      className="h-5 w-5 text-red-400"
                      viewBox="0 0 20 20"
                      fill="currentColor"
                    >
                      <path
                        fillRule="evenodd"
                        d="M10 18a8 8 0 100-16 8 8 0 000 16zM8.28 7.22a.75.75 0 00-1.06 1.06L8.94 10l-1.72 1.72a.75.75 0 101.06 1.06L10 11.06l1.72 1.72a.75.75 0 101.06-1.06L11.06 10l1.72-1.72a.75.75 0 00-1.06-1.06L10 8.94 8.28 7.22z"
                        clipRule="evenodd"
                      />
                    </svg>
                  </div>
                  <div className="ml-3">
                    <p className="text-sm font-medium text-red-800">{error}</p>
                  </div>
                </div>
              </div>
            )}

            {/* Company name */}
            <div>
              <label htmlFor="companyName" className="label">
                Название компании <span className="text-red-500">*</span>
              </label>
              <div className="mt-2">
                <input
                  id="companyName"
                  type="text"
                  required
                  placeholder="ООО Ромашка"
                  value={companyName}
                  onChange={(e) => setCompanyName(e.target.value)}
                  className="input-field"
                />
              </div>
            </div>

            {/* Slug */}
            <div>
              <label htmlFor="slug" className="label">
                Slug (URL-идентификатор) <span className="text-red-500">*</span>
              </label>
              <div className="mt-2">
                <input
                  id="slug"
                  type="text"
                  required
                  placeholder="romashka"
                  value={slug}
                  onChange={(e) => handleSlugChange(e.target.value)}
                  className="input-field"
                />
              </div>
              <p className="mt-1 text-xs text-gray-500">
                Только латинские буквы, цифры и дефис. Минимум 3 символа.
              </p>
            </div>

            {/* First name */}
            <div>
              <label htmlFor="firstName" className="label">
                Имя
              </label>
              <div className="mt-2">
                <input
                  id="firstName"
                  type="text"
                  placeholder="Иван"
                  value={firstName}
                  onChange={(e) => setFirstName(e.target.value)}
                  className="input-field"
                />
              </div>
            </div>

            {/* Last name */}
            <div>
              <label htmlFor="lastName" className="label">
                Фамилия
              </label>
              <div className="mt-2">
                <input
                  id="lastName"
                  type="text"
                  placeholder="Иванов"
                  value={lastName}
                  onChange={(e) => setLastName(e.target.value)}
                  className="input-field"
                />
              </div>
            </div>

            {/* Submit */}
            <div>
              <button
                type="submit"
                disabled={isSubmitting}
                className="btn-primary w-full"
              >
                {isSubmitting ? (
                  <LoadingSpinner size="sm" className="mr-2" />
                ) : null}
                {isSubmitting ? 'Создание...' : 'Создать компанию'}
              </button>
            </div>
          </form>
        </div>

        {/* Back */}
        <div className="mt-6 text-center">
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
