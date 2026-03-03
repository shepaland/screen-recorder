import { NavLink } from 'react-router-dom';
import {
  HomeIcon,
  UsersIcon,
  ShieldCheckIcon,
  DocumentTextIcon,
  BuildingOfficeIcon,
  ComputerDesktopIcon,
  KeyIcon,
  Cog6ToothIcon,
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
  { name: 'Токены регистрации', href: '/device-tokens', icon: KeyIcon, permission: 'DEVICE_TOKENS:READ' },
  { name: 'Audit Log', href: '/audit', icon: DocumentTextIcon, permission: 'AUDIT:READ' },
  { name: 'Tenants', href: '/tenants', icon: BuildingOfficeIcon, permission: 'TENANTS:READ' },
];

/** Tenant-scoped items under the company name. */
const tenantScopedNavigation: NavItem[] = [
  { name: 'Контрольная панель', href: '/', icon: HomeIcon, permission: 'DASHBOARD:VIEW' },
  { name: 'Токены регистрации', href: '/device-tokens', icon: KeyIcon, permission: 'DEVICE_TOKENS:READ' },
  { name: 'Устройства', href: '/devices', icon: ComputerDesktopIcon, permission: 'DEVICES:READ' },
  { name: 'Пользователи', href: '/users', icon: UsersIcon, permission: 'USERS:READ' },
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
                    ? 'bg-indigo-700 text-white'
                    : 'text-indigo-200 hover:bg-indigo-700 hover:text-white'
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

  const handleClick = () => {
    onClose?.();
  };

  const isSuperAdmin = user?.roles?.includes('SUPER_ADMIN');

  return (
    <div className="flex grow flex-col gap-y-5 overflow-y-auto bg-indigo-950 px-6 pb-4">
      {/* Logo */}
      <div className="flex h-16 shrink-0 items-center">
        <span className="text-xl font-bold text-white">PRG Screen Recorder</span>
      </div>

      <nav className="flex flex-1 flex-col">
        <ul role="list" className="flex flex-1 flex-col gap-y-7">
          {isSuperAdmin ? (
            /* ---- SuperAdmin: flat list (unchanged) ---- */
            <li>
              <NavList items={superAdminNavigation} onClick={handleClick} />
            </li>
          ) : (
            /* ---- OAuth user: structured menu ---- */
            <>
              {/* Company name (tenant switcher) + tenant-scoped items */}
              <li>
                <div className="-mx-2 mb-2">
                  <TenantSwitcher />
                </div>
                <NavList items={tenantScopedNavigation} onClick={handleClick} indent />
              </li>

              {/* Global items: Компании, Аудит */}
              <li>
                <div className="-mx-2 mb-2">
                  <p className="px-3 text-xs font-semibold uppercase tracking-wider text-indigo-400">
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
                        ? 'bg-indigo-700 text-white'
                        : 'text-indigo-200 hover:bg-indigo-700 hover:text-white'
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
