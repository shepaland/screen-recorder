import { useState, useRef, useEffect, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import { ChevronUpDownIcon, CheckIcon, PlusIcon } from '@heroicons/react/20/solid';
import { BuildingOffice2Icon } from '@heroicons/react/24/outline';
import { useAuth } from '../hooks/useAuth';
import { useToast } from '../contexts/ToastContext';
import type { TenantPreview } from '../types';

const ROLE_LABELS: Record<string, string> = {
  OWNER: 'Владелец',
  TENANT_ADMIN: 'Администратор',
  MANAGER: 'Менеджер',
  ADMIN: 'Администратор',
  OPERATOR: 'Оператор',
  VIEWER: 'Наблюдатель',
};

export default function TenantSwitcher() {
  const { user, tenants, currentTenantId, switchTenant } = useAuth();
  const { addToast } = useToast();
  const navigate = useNavigate();
  const [isOpen, setIsOpen] = useState(false);
  const [isSwitching, setIsSwitching] = useState(false);
  const dropdownRef = useRef<HTMLDivElement>(null);

  // Close dropdown on click outside
  useEffect(() => {
    function handleClickOutside(event: MouseEvent) {
      if (dropdownRef.current && !dropdownRef.current.contains(event.target as Node)) {
        setIsOpen(false);
      }
    }
    document.addEventListener('mousedown', handleClickOutside);
    return () => document.removeEventListener('mousedown', handleClickOutside);
  }, []);

  const handleSwitch = useCallback(
    async (tenant: TenantPreview) => {
      if (tenant.id === currentTenantId) {
        setIsOpen(false);
        return;
      }

      setIsSwitching(true);
      try {
        await switchTenant(tenant.id);
        setIsOpen(false);
        addToast('success', `Переключено на "${tenant.name}"`);
        window.location.href = import.meta.env.BASE_URL;
      } catch {
        addToast('error', 'Не удалось переключить компанию');
      } finally {
        setIsSwitching(false);
      }
    },
    [currentTenantId, switchTenant, addToast],
  );

  // Don't render if user is not loaded
  if (!user) {
    return null;
  }

  const currentTenant = tenants.find((t) => t.id === currentTenantId);

  return (
    <div className="relative" ref={dropdownRef}>
      {/* Trigger button -- always visible, shows current company name */}
      <button
        type="button"
        onClick={() => setIsOpen(!isOpen)}
        className="flex w-full items-center gap-3 rounded-lg bg-gray-800/50 px-3 py-2.5 text-left text-sm transition-colors hover:bg-gray-800/70 cursor-pointer"
      >
        <BuildingOffice2Icon className="h-5 w-5 flex-shrink-0 text-gray-400" />
        <div className="min-w-0 flex-1">
          <p className="truncate font-medium text-white">
            {currentTenant?.name || 'Компания'}
          </p>
          {currentTenant?.role && (
            <p className="truncate text-xs text-gray-400">
              {ROLE_LABELS[currentTenant.role] || currentTenant.role}
            </p>
          )}
        </div>
        <ChevronUpDownIcon className="h-5 w-5 flex-shrink-0 text-gray-400" />
      </button>

      {/* Dropdown */}
      {isOpen && (
        <div className="absolute left-0 right-0 z-50 mt-1 rounded-lg border border-gray-200 bg-white py-1 shadow-lg">
          <div className="px-3 py-2 text-xs font-semibold uppercase tracking-wider text-gray-500">
            Компании
          </div>
          {tenants.map((tenant) => {
            const isActive = tenant.id === currentTenantId;
            return (
              <button
                key={tenant.id}
                type="button"
                onClick={() => handleSwitch(tenant)}
                disabled={isSwitching}
                className={`flex w-full items-center gap-3 px-3 py-2.5 text-left text-sm transition-colors ${
                  isActive
                    ? 'bg-red-50 text-red-700'
                    : 'text-gray-700 hover:bg-gray-50'
                }`}
              >
                <div className="min-w-0 flex-1">
                  <p className="truncate font-medium">{tenant.name}</p>
                  <p className="truncate text-xs text-gray-500">
                    {ROLE_LABELS[tenant.role] || tenant.role}
                  </p>
                </div>
                {isActive && (
                  <CheckIcon className="h-4 w-4 flex-shrink-0 text-red-600" />
                )}
              </button>
            );
          })}

          {/* Create new company */}
          <div className="border-t border-gray-100 mt-1 pt-1">
            <button
              type="button"
              onClick={() => {
                setIsOpen(false);
                navigate('/tenants/new');
              }}
              className="flex w-full items-center gap-3 px-3 py-2.5 text-left text-sm text-gray-700 hover:bg-gray-50 transition-colors"
            >
              <PlusIcon className="h-4 w-4 flex-shrink-0 text-gray-400" />
              <span className="font-medium">Создать компанию</span>
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
