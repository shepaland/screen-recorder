import { useState } from 'react';
import { NavLink, useLocation } from 'react-router-dom';
import {
  HomeIcon,
  UsersIcon,
  ShieldCheckIcon,
  DocumentTextIcon,
  BuildingOfficeIcon,
  ComputerDesktopIcon,
  KeyIcon,
  Cog6ToothIcon,
  ChevronDownIcon,
  ChevronRightIcon,
  ArrowDownTrayIcon,
  AdjustmentsHorizontalIcon,
  ArchiveBoxIcon,
  UserGroupIcon,
} from '@heroicons/react/24/outline';
import PermissionGate from './PermissionGate';
import TenantSwitcher from './TenantSwitcher';
import { useAuth } from '../hooks/useAuth';

interface SidebarProps {
  onClose?: () => void;
}

interface NavItem {
  name: string;
  href: string;
  icon: typeof HomeIcon;
  permission?: string;
}

/** Full navigation for superadmin (password auth, SUPER_ADMIN role). */
const superAdminNavigation: NavItem[] = [
  { name: 'Dashboard', href: '/', icon: HomeIcon, permission: 'DASHBOARD:VIEW' },
  { name: 'Users', href: '/users', icon: UsersIcon, permission: 'USERS:READ' },
  { name: 'Roles', href: '/roles', icon: ShieldCheckIcon, permission: 'ROLES:READ' },
  { name: 'Устройства', href: '/devices', icon: ComputerDesktopIcon, permission: 'DEVICES:READ' },
  { name: 'Настройки записи', href: '/recording-settings', icon: AdjustmentsHorizontalIcon, permission: 'DEVICES:READ' },
  { name: 'Токены регистрации', href: '/device-tokens', icon: KeyIcon, permission: 'DEVICE_TOKENS:READ' },
  { name: 'Audit Log', href: '/audit', icon: DocumentTextIcon, permission: 'AUDIT:READ' },
  { name: 'Скачать клиент', href: '/download', icon: ArrowDownTrayIcon },
  { name: 'Tenants', href: '/tenants', icon: BuildingOfficeIcon, permission: 'TENANTS:READ' },
];

const archiveSubmenu: NavItem[] = [
  { name: 'Устройства', href: '/archive/devices', icon: ComputerDesktopIcon },
  { name: 'Пользователи', href: '/archive/users', icon: UserGroupIcon },
];

/** Tenant-scoped items under the company name. */
const tenantScopedNavigation: NavItem[] = [
  { name: 'Контрольная панель', href: '/', icon: HomeIcon, permission: 'DASHBOARD:VIEW' },
  { name: 'Токены регистрации', href: '/device-tokens', icon: KeyIcon, permission: 'DEVICE_TOKENS:READ' },
  { name: 'Устройства', href: '/devices', icon: ComputerDesktopIcon, permission: 'DEVICES:READ' },
  { name: 'Настройки записи', href: '/recording-settings', icon: AdjustmentsHorizontalIcon, permission: 'DEVICES:READ' },
  { name: 'Пользователи', href: '/users', icon: UsersIcon, permission: 'USERS:READ' },
  { name: 'Скачать клиент', href: '/download', icon: ArrowDownTrayIcon },
];

/** Global items outside the company scope. */
const globalNavigation: NavItem[] = [
  { name: 'Компании', href: '/tenants', icon: BuildingOfficeIcon, permission: 'TENANTS:READ' },
  { name: 'Аудит', href: '/audit', icon: DocumentTextIcon, permission: 'AUDIT:READ' },
];

function NavList({
  items,
  onClick,
  indent = false,
}: {
  items: NavItem[];
  onClick?: () => void;
  indent?: boolean;
}) {
  return (
    <ul role="list" className={`${indent ? 'ml-4' : '-mx-2'} space-y-1`}>
      {items.map((item) => (
        <PermissionGate key={item.name} permission={item.permission}>
          <li>
            <NavLink
              to={item.href}
              end={item.href === '/'}
              onClick={onClick}
              className={({ isActive }) =>
                `group flex gap-x-3 rounded-md p-2 text-sm font-semibold leading-6 ${
                  isActive
                    ? 'bg-red-700 text-white'
                    : 'text-gray-300 hover:bg-red-700 hover:text-white'
                }`
              }
            >
              <item.icon className="h-6 w-6 shrink-0" aria-hidden="true" />
              {item.name}
            </NavLink>
          </li>
        </PermissionGate>
      ))}
    </ul>
  );
}

export default function Sidebar({ onClose }: SidebarProps) {
  const { user } = useAuth();
  const location = useLocation();
  const [isTenantMenuExpanded, setIsTenantMenuExpanded] = useState(true);
  const [isArchiveExpanded, setIsArchiveExpanded] = useState(
    location.pathname.startsWith('/archive') || location.pathname.startsWith('/recordings'),
  );

  const handleClick = () => {
    onClose?.();
  };

  const isSuperAdmin = user?.roles?.includes('SUPER_ADMIN');
  const isArchiveActive = location.pathname.startsWith('/archive') || location.pathname.startsWith('/recordings');

  return (
    <div className="flex grow flex-col gap-y-5 overflow-y-auto bg-gray-950 px-6 pb-4">
      {/* Logo */}
      <div className="flex h-16 shrink-0 items-center">
        <span className="text-xl font-bold tracking-wider text-white">КАДЕРО</span>
      </div>

      <nav className="flex flex-1 flex-col">
        <ul role="list" className="flex flex-1 flex-col gap-y-7">
          {isSuperAdmin ? (
            /* ---- SuperAdmin: flat list + archive submenu ---- */
            <li>
              <NavList items={superAdminNavigation} onClick={handleClick} />
              {/* Archive submenu */}
              <ul role="list" className="-mx-2 mt-1 space-y-1">
                <li>
                  <button
                    type="button"
                    onClick={() => setIsArchiveExpanded(!isArchiveExpanded)}
                    className={`group flex w-full items-center gap-x-3 rounded-md p-2 text-sm font-semibold leading-6 ${
                      isArchiveActive
                        ? 'bg-red-700 text-white'
                        : 'text-gray-300 hover:bg-red-700 hover:text-white'
                    }`}
                  >
                    <ArchiveBoxIcon className="h-6 w-6 shrink-0" aria-hidden="true" />
                    Архив
                    {isArchiveExpanded ? (
                      <ChevronDownIcon className="ml-auto h-4 w-4" />
                    ) : (
                      <ChevronRightIcon className="ml-auto h-4 w-4" />
                    )}
                  </button>
                  {isArchiveExpanded && (
                    <NavList items={archiveSubmenu} onClick={handleClick} indent />
                  )}
                </li>
              </ul>
            </li>
          ) : (
            /* ---- OAuth user: structured menu ---- */
            <>
              {/* Company name (tenant switcher) + collapsible tenant-scoped items */}
              <li>
                <div className="-mx-2 mb-2 flex items-center gap-1">
                  <div className="flex-1">
                    <TenantSwitcher />
                  </div>
                  <button
                    type="button"
                    onClick={() => setIsTenantMenuExpanded(!isTenantMenuExpanded)}
                    className="p-1 rounded-md text-gray-400 hover:text-white hover:bg-gray-800 transition-colors"
                    title={isTenantMenuExpanded ? 'Свернуть меню' : 'Развернуть меню'}
                  >
                    {isTenantMenuExpanded ? (
                      <ChevronDownIcon className="h-4 w-4" />
                    ) : (
                      <ChevronRightIcon className="h-4 w-4" />
                    )}
                  </button>
                </div>
                {isTenantMenuExpanded && (
                  <>
                    <NavList items={tenantScopedNavigation} onClick={handleClick} indent />
                    {/* Archive submenu */}
                    <ul role="list" className="ml-4 mt-1 space-y-1">
                      <li>
                        <button
                          type="button"
                          onClick={() => setIsArchiveExpanded(!isArchiveExpanded)}
                          className={`group flex w-full items-center gap-x-3 rounded-md p-2 text-sm font-semibold leading-6 ${
                            isArchiveActive
                              ? 'bg-red-700 text-white'
                              : 'text-gray-300 hover:bg-red-700 hover:text-white'
                          }`}
                        >
                          <ArchiveBoxIcon className="h-6 w-6 shrink-0" aria-hidden="true" />
                          Архив
                          {isArchiveExpanded ? (
                            <ChevronDownIcon className="ml-auto h-4 w-4" />
                          ) : (
                            <ChevronRightIcon className="ml-auto h-4 w-4" />
                          )}
                        </button>
                        {isArchiveExpanded && (
                          <NavList items={archiveSubmenu} onClick={handleClick} indent />
                        )}
                      </li>
                    </ul>
                  </>
                )}
              </li>

              {/* Global items: Компании, Аудит */}
              <li>
                <div className="-mx-2 mb-2">
                  <p className="px-3 text-xs font-semibold uppercase tracking-wider text-gray-400">
                    Управление
                  </p>
                </div>
                <NavList items={globalNavigation} onClick={handleClick} />
              </li>
            </>
          )}

          {/* Bottom: Settings */}
          <li className="mt-auto">
            <ul role="list" className="-mx-2 space-y-1">
              <li>
                <NavLink
                  to="/settings"
                  onClick={handleClick}
                  className={({ isActive }) =>
                    `group flex gap-x-3 rounded-md p-2 text-sm font-semibold leading-6 ${
                      isActive
                        ? 'bg-red-700 text-white'
                        : 'text-gray-300 hover:bg-red-700 hover:text-white'
                    }`
                  }
                >
                  <Cog6ToothIcon className="h-6 w-6 shrink-0" aria-hidden="true" />
                  Настройки
                </NavLink>
              </li>
            </ul>
          </li>
        </ul>
      </nav>
    </div>
  );
}
