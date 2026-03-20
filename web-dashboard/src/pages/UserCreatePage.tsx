import { useState, useEffect, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { ArrowLeftIcon } from '@heroicons/react/20/solid';
import { getRoles } from '../api/roles';
import { createUser } from '../api/users';
import type { RoleResponse } from '../types';
import { useToast } from '../contexts/ToastContext';
import LoadingSpinner from '../components/LoadingSpinner';
import { AxiosError } from 'axios';
import type { ErrorResponse } from '../types';

export default function UserCreatePage() {
  const navigate = useNavigate();
  const { addToast } = useToast();

  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [firstName, setFirstName] = useState('');
  const [lastName, setLastName] = useState('');
  const [selectedRoleIds, setSelectedRoleIds] = useState<string[]>([]);

  const [roles, setRoles] = useState<RoleResponse[]>([]);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [errors, setErrors] = useState<Record<string, string>>({});

  useEffect(() => {
    getRoles({ size: 100 })
      .then((data) => setRoles(data.content))
      .catch(() => addToast('error', 'Не удалось загрузить роли'));
  }, [addToast]);

  const validate = (): boolean => {
    const newErrors: Record<string, string> = {};

    if (!email.trim() || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) {
      newErrors.email = 'Введите корректный email';
    }
    if (!password || password.length < 8) {
      newErrors.password = 'Пароль должен быть не менее 8 символов';
    } else if (!/(?=.*[a-z])(?=.*[A-Z])(?=.*\d)/.test(password)) {
      newErrors.password = 'Пароль должен содержать заглавную, строчную букву и цифру';
    }

    setErrors(newErrors);
    return Object.keys(newErrors).length === 0;
  };

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault();
    if (!validate()) return;

    setIsSubmitting(true);

    try {
      await createUser({
        email: email.trim(),
        password,
        first_name: firstName.trim() || undefined,
        last_name: lastName.trim() || undefined,
        role_ids: selectedRoleIds.length > 0 ? selectedRoleIds : undefined,
      });

      addToast('success', 'Пользователь создан');
      navigate('/users');
    } catch (err) {
      if (err instanceof AxiosError) {
        const data = err.response?.data as ErrorResponse | undefined;
        if (err.response?.status === 409) {
          if (data?.code === 'EMAIL_ALREADY_EXISTS') {
            setErrors({ email: 'Этот email уже зарегистрирован в системе' });
          }
        } else {
          addToast('error', data?.error || 'Не удалось создать пользователя');
        }
      } else {
        addToast('error', 'Произошла ошибка');
      }
    } finally {
      setIsSubmitting(false);
    }
  };

  const toggleRole = (roleId: string) => {
    setSelectedRoleIds((prev) =>
      prev.includes(roleId) ? prev.filter((id) => id !== roleId) : [...prev, roleId],
    );
  };

  return (
    <div>
      {/* Header */}
      <div className="mb-6">
        <button
          type="button"
          onClick={() => navigate('/users')}
          className="flex items-center text-sm text-gray-500 hover:text-gray-700 mb-4"
        >
          <ArrowLeftIcon className="h-4 w-4 mr-1" />
          Назад к пользователям
        </button>
        <h1 className="text-2xl font-bold tracking-tight text-gray-900">Создать пользователя</h1>
        <p className="mt-2 text-sm text-gray-600">
          Добавьте нового пользователя в вашу организацию.
        </p>
      </div>

      {/* Form */}
      <div className="card max-w-2xl">
        <form onSubmit={handleSubmit} className="space-y-6">
          {/* Email */}
          <div>
            <label htmlFor="email" className="label">
              Email *
            </label>
            <input
              id="email"
              type="email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              className={`input-field mt-1 ${errors.email ? 'ring-red-300 focus:ring-red-500' : ''}`}
              placeholder="user@company.ru"
            />
            {errors.email && (
              <p className="mt-1 text-sm text-red-600">{errors.email}</p>
            )}
          </div>

          {/* Password */}
          <div>
            <label htmlFor="password" className="label">
              Пароль *
            </label>
            <input
              id="password"
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              className={`input-field mt-1 ${errors.password ? 'ring-red-300 focus:ring-red-500' : ''}`}
              placeholder="Минимум 8 символов, заглавная, строчная, цифра"
            />
            {errors.password && (
              <p className="mt-1 text-sm text-red-600">{errors.password}</p>
            )}
          </div>

          {/* Name fields */}
          <div className="grid grid-cols-1 gap-6 sm:grid-cols-2">
            <div>
              <label htmlFor="firstName" className="label">
                Имя
              </label>
              <input
                id="firstName"
                type="text"
                value={firstName}
                onChange={(e) => setFirstName(e.target.value)}
                className="input-field mt-1"
                placeholder="Иван"
              />
            </div>
            <div>
              <label htmlFor="lastName" className="label">
                Фамилия
              </label>
              <input
                id="lastName"
                type="text"
                value={lastName}
                onChange={(e) => setLastName(e.target.value)}
                className="input-field mt-1"
                placeholder="Иванов"
              />
            </div>
          </div>

          {/* Roles */}
          <div>
            <label className="label">Роли</label>
            <p className="text-sm text-gray-500 mb-3">
              Выберите одну или несколько ролей для пользователя.
            </p>
            <div className="space-y-2">
              {roles.map((role) => (
                <label
                  key={role.id}
                  className="flex items-center gap-3 rounded-md border border-gray-200 p-3 hover:bg-gray-50 cursor-pointer"
                >
                  <input
                    type="checkbox"
                    checked={selectedRoleIds.includes(role.id)}
                    onChange={() => toggleRole(role.id)}
                    className="h-4 w-4 rounded border-gray-300 text-indigo-600 focus:ring-indigo-600"
                  />
                  <div>
                    <p className="text-sm font-medium text-gray-900">{role.name}</p>
                    {role.description && (
                      <p className="text-xs text-gray-500">{role.description}</p>
                    )}
                  </div>
                  {role.is_system && (
                    <span className="ml-auto inline-flex items-center rounded-full bg-gray-100 px-2 py-0.5 text-xs font-medium text-gray-600">
                      Системная
                    </span>
                  )}
                </label>
              ))}
            </div>
          </div>

          {/* Actions */}
          <div className="flex justify-end gap-3 pt-4 border-t border-gray-200">
            <button
              type="button"
              onClick={() => navigate('/users')}
              className="btn-secondary"
            >
              Отмена
            </button>
            <button type="submit" disabled={isSubmitting} className="btn-primary">
              {isSubmitting && <LoadingSpinner size="sm" className="mr-2" />}
              {isSubmitting ? 'Создание...' : 'Создать'}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
