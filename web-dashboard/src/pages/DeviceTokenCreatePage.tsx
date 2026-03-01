import { useState, type FormEvent, Fragment } from 'react';
import { useNavigate } from 'react-router-dom';
import { ArrowLeftIcon, ClipboardDocumentIcon, CheckIcon } from '@heroicons/react/20/solid';
import { Dialog, Transition } from '@headlessui/react';
import { ShieldCheckIcon } from '@heroicons/react/24/outline';
import { createDeviceToken } from '../api/deviceTokens';
import { useToast } from '../contexts/ToastContext';
import LoadingSpinner from '../components/LoadingSpinner';
import { AxiosError } from 'axios';
import type { ErrorResponse } from '../types';

export default function DeviceTokenCreatePage() {
  const navigate = useNavigate();
  const { addToast } = useToast();

  const [name, setName] = useState('');
  const [maxUses, setMaxUses] = useState('');
  const [expiresAt, setExpiresAt] = useState('');

  const [isSubmitting, setIsSubmitting] = useState(false);
  const [errors, setErrors] = useState<Record<string, string>>({});

  // Token display modal
  const [createdToken, setCreatedToken] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);

  const validate = (): boolean => {
    const newErrors: Record<string, string> = {};
    if (!name.trim()) {
      newErrors.name = 'Имя токена обязательно';
    }
    if (maxUses && (isNaN(Number(maxUses)) || Number(maxUses) < 1)) {
      newErrors.maxUses = 'Максимум использований должен быть положительным числом';
    }
    if (expiresAt) {
      const expiryDate = new Date(expiresAt);
      if (isNaN(expiryDate.getTime())) {
        newErrors.expiresAt = 'Некорректная дата';
      } else if (expiryDate <= new Date()) {
        newErrors.expiresAt = 'Срок действия должен быть в будущем';
      }
    }
    setErrors(newErrors);
    return Object.keys(newErrors).length === 0;
  };

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault();
    if (!validate()) return;

    setIsSubmitting(true);
    try {
      const result = await createDeviceToken({
        name: name.trim(),
        max_uses: maxUses ? Number(maxUses) : null,
        expires_at: expiresAt ? new Date(expiresAt).toISOString() : null,
      });

      if (result.token) {
        setCreatedToken(result.token);
      } else {
        addToast('success', 'Токен создан');
        navigate('/device-tokens');
      }
    } catch (err) {
      if (err instanceof AxiosError) {
        const data = err.response?.data as ErrorResponse | undefined;
        addToast('error', data?.error || 'Не удалось создать токен');
      } else {
        addToast('error', 'Произошла неожиданная ошибка');
      }
    } finally {
      setIsSubmitting(false);
    }
  };

  const handleCopy = async () => {
    if (!createdToken) return;
    try {
      await navigator.clipboard.writeText(createdToken);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch {
      addToast('error', 'Не удалось скопировать токен');
    }
  };

  const handleDone = () => {
    setCreatedToken(null);
    navigate('/device-tokens');
  };

  return (
    <div>
      {/* Header */}
      <div className="mb-6">
        <button
          type="button"
          onClick={() => navigate('/device-tokens')}
          className="flex items-center text-sm text-gray-500 hover:text-gray-700 mb-4"
        >
          <ArrowLeftIcon className="h-4 w-4 mr-1" />
          Назад к токенам
        </button>
        <h1 className="text-2xl font-bold tracking-tight text-gray-900">
          Создать токен регистрации
        </h1>
        <p className="mt-2 text-sm text-gray-600">
          Создайте токен для регистрации агентов записи на устройствах.
        </p>
      </div>

      {/* Form */}
      <div className="card max-w-2xl">
        <form onSubmit={handleSubmit} className="space-y-6">
          {/* Name */}
          <div>
            <label htmlFor="tokenName" className="label">
              Имя токена *
            </label>
            <input
              id="tokenName"
              type="text"
              value={name}
              onChange={(e) => setName(e.target.value)}
              className={`input-field mt-1 ${errors.name ? 'ring-red-300 focus:ring-red-500' : ''}`}
              placeholder="Например: Офис Москва"
            />
            {errors.name && (
              <p className="mt-1 text-sm text-red-600">{errors.name}</p>
            )}
          </div>

          {/* Max uses */}
          <div>
            <label htmlFor="maxUses" className="label">
              Максимум использований
            </label>
            <input
              id="maxUses"
              type="number"
              min="1"
              value={maxUses}
              onChange={(e) => setMaxUses(e.target.value)}
              className={`input-field mt-1 ${errors.maxUses ? 'ring-red-300 focus:ring-red-500' : ''}`}
              placeholder="Без ограничений"
            />
            {errors.maxUses && (
              <p className="mt-1 text-sm text-red-600">{errors.maxUses}</p>
            )}
            <p className="mt-1 text-xs text-gray-500">
              Оставьте пустым для неограниченного количества регистраций.
            </p>
          </div>

          {/* Expires at */}
          <div>
            <label htmlFor="expiresAt" className="label">
              Срок действия
            </label>
            <input
              id="expiresAt"
              type="datetime-local"
              value={expiresAt}
              onChange={(e) => setExpiresAt(e.target.value)}
              className={`input-field mt-1 ${errors.expiresAt ? 'ring-red-300 focus:ring-red-500' : ''}`}
            />
            {errors.expiresAt && (
              <p className="mt-1 text-sm text-red-600">{errors.expiresAt}</p>
            )}
            <p className="mt-1 text-xs text-gray-500">
              Оставьте пустым для бессрочного токена.
            </p>
          </div>

          {/* Actions */}
          <div className="flex justify-end gap-3 pt-4 border-t border-gray-200">
            <button
              type="button"
              onClick={() => navigate('/device-tokens')}
              className="btn-secondary"
            >
              Отмена
            </button>
            <button type="submit" disabled={isSubmitting} className="btn-primary">
              {isSubmitting && <LoadingSpinner size="sm" className="mr-2" />}
              {isSubmitting ? 'Создание...' : 'Создать токен'}
            </button>
          </div>
        </form>
      </div>

      {/* Token created modal */}
      <Transition.Root show={!!createdToken} as={Fragment}>
        <Dialog as="div" className="relative z-50" onClose={() => {}}>
          <Transition.Child
            as={Fragment}
            enter="ease-out duration-300"
            enterFrom="opacity-0"
            enterTo="opacity-100"
            leave="ease-in duration-200"
            leaveFrom="opacity-100"
            leaveTo="opacity-0"
          >
            <div className="fixed inset-0 bg-gray-500 bg-opacity-75 transition-opacity" />
          </Transition.Child>

          <div className="fixed inset-0 z-10 w-screen overflow-y-auto">
            <div className="flex min-h-full items-end justify-center p-4 text-center sm:items-center sm:p-0">
              <Transition.Child
                as={Fragment}
                enter="ease-out duration-300"
                enterFrom="opacity-0 translate-y-4 sm:translate-y-0 sm:scale-95"
                enterTo="opacity-100 translate-y-0 sm:scale-100"
                leave="ease-in duration-200"
                leaveFrom="opacity-100 translate-y-0 sm:scale-100"
                leaveTo="opacity-0 translate-y-4 sm:translate-y-0 sm:scale-95"
              >
                <Dialog.Panel className="relative transform overflow-hidden rounded-lg bg-white px-4 pb-4 pt-5 text-left shadow-xl transition-all sm:my-8 sm:w-full sm:max-w-lg sm:p-6">
                  <div>
                    <div className="mx-auto flex h-12 w-12 items-center justify-center rounded-full bg-green-100">
                      <ShieldCheckIcon className="h-6 w-6 text-green-600" aria-hidden="true" />
                    </div>
                    <div className="mt-3 text-center sm:mt-5">
                      <Dialog.Title
                        as="h3"
                        className="text-base font-semibold leading-6 text-gray-900"
                      >
                        Токен создан
                      </Dialog.Title>
                      <div className="mt-4">
                        <div className="rounded-md bg-yellow-50 border border-yellow-200 p-4 mb-4">
                          <p className="text-sm font-medium text-yellow-800">
                            Сохраните токен! Он больше не будет показан.
                          </p>
                        </div>
                        <div className="flex items-center gap-2 rounded-md bg-gray-50 border border-gray-200 p-3">
                          <code className="flex-1 break-all text-sm font-mono text-gray-900">
                            {createdToken}
                          </code>
                          <button
                            type="button"
                            onClick={handleCopy}
                            className="flex-shrink-0 rounded-md p-2 text-gray-400 hover:text-gray-600 hover:bg-gray-100"
                            title="Копировать"
                          >
                            {copied ? (
                              <CheckIcon className="h-5 w-5 text-green-600" />
                            ) : (
                              <ClipboardDocumentIcon className="h-5 w-5" />
                            )}
                          </button>
                        </div>
                      </div>
                    </div>
                  </div>
                  <div className="mt-5 sm:mt-6">
                    <button
                      type="button"
                      className="inline-flex w-full justify-center rounded-md bg-indigo-600 px-3 py-2 text-sm font-semibold text-white shadow-sm hover:bg-indigo-500 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-indigo-600"
                      onClick={handleDone}
                    >
                      Готово
                    </button>
                  </div>
                </Dialog.Panel>
              </Transition.Child>
            </div>
          </div>
        </Dialog>
      </Transition.Root>
    </div>
  );
}
