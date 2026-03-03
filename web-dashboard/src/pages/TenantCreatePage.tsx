import { useState, useEffect, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { ArrowLeftIcon } from '@heroicons/react/20/solid';
import { useToast } from '../contexts/ToastContext';
import LoadingSpinner from '../components/LoadingSpinner';
import { AxiosError } from 'axios';
import type { ErrorResponse } from '../types';
import apiClient from '../api/client';

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

export default function TenantCreatePage() {
  const navigate = useNavigate();
  const { addToast } = useToast();

  const [name, setName] = useState('');
  const [slug, setSlug] = useState('');
  const [slugEdited, setSlugEdited] = useState(false);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Auto-generate slug from company name
  useEffect(() => {
    if (!slugEdited) {
      setSlug(generateSlug(name));
    }
  }, [name, slugEdited]);

  const handleSlugChange = (value: string) => {
    setSlugEdited(true);
    setSlug(value.toLowerCase().replace(/[^a-z0-9-]/g, ''));
  };

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);

    if (!name.trim()) {
      setError('Название компании обязательно');
      return;
    }
    if (!slug.trim() || slug.length < 3) {
      setError('Slug должен быть не менее 3 символов');
      return;
    }

    setIsSubmitting(true);
    try {
      await apiClient.post('/tenants/create-own', {
        name: name.trim(),
        slug: slug.trim(),
      });

      addToast('success', 'Компания успешно создана');
      navigate('/tenants');
    } catch (err) {
      if (err instanceof AxiosError) {
        const data = err.response?.data as ErrorResponse | undefined;
        if (err.response?.status === 409) {
          setError('Компания с таким slug уже существует. Выберите другой.');
        } else {
          setError(data?.error || 'Не удалось создать компанию');
        }
      } else {
        setError('Произошла непредвиденная ошибка');
      }
    } finally {
      setIsSubmitting(false);
    }
  };

  return (
    <div>
      {/* Header */}
      <div className="mb-6">
        <button
          type="button"
          onClick={() => navigate('/tenants')}
          className="flex items-center text-sm text-gray-500 hover:text-gray-700 mb-4"
        >
          <ArrowLeftIcon className="h-4 w-4 mr-1" />
          Назад к компаниям
        </button>
        <h1 className="text-2xl font-bold tracking-tight text-gray-900">Создать компанию</h1>
        <p className="mt-2 text-sm text-gray-600">
          Новая компания будет привязана к вашей учётной записи. Вы станете владельцем.
        </p>
      </div>

      <form onSubmit={handleSubmit}>
        <div className="card max-w-2xl">
          <h2 className="text-lg font-semibold text-gray-900 mb-4">Организация</h2>

          {/* Error */}
          {error && (
            <div className="rounded-md bg-red-50 p-4 mb-6">
              <p className="text-sm font-medium text-red-800">{error}</p>
            </div>
          )}

          <div className="space-y-6">
            {/* Company name */}
            <div>
              <label htmlFor="name" className="label">
                Название компании <span className="text-red-500">*</span>
              </label>
              <input
                id="name"
                type="text"
                value={name}
                onChange={(e) => setName(e.target.value)}
                className="input-field mt-1"
                placeholder="ООО Ромашка"
              />
            </div>

            {/* Slug */}
            <div>
              <label htmlFor="slug" className="label">
                Slug (URL-идентификатор) <span className="text-red-500">*</span>
              </label>
              <input
                id="slug"
                type="text"
                value={slug}
                onChange={(e) => handleSlugChange(e.target.value)}
                className="input-field mt-1 font-mono"
                placeholder="romashka"
              />
              <p className="mt-1 text-xs text-gray-500">
                Заполняется автоматически. Только латинские буквы, цифры и дефисы.
              </p>
            </div>
          </div>
        </div>

        {/* Actions */}
        <div className="mt-6 flex justify-end gap-3 max-w-2xl">
          <button
            type="button"
            onClick={() => navigate('/tenants')}
            className="btn-secondary"
          >
            Отмена
          </button>
          <button type="submit" disabled={isSubmitting} className="btn-primary">
            {isSubmitting && <LoadingSpinner size="sm" className="mr-2" />}
            {isSubmitting ? 'Создание...' : 'Создать компанию'}
          </button>
        </div>
      </form>
    </div>
  );
}
