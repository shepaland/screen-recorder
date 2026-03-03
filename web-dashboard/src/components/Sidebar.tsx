import { NavLink } from 'react-router-dom';
import {
  HomeIcon,
  UsersIcon,
  ShieldCheckIcon,
  DocumentTextIcon,
  BuildingOfficeIcon,
  UserCircleIcon,
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

/** Tenant-scoped navigation for regular OAuth users. */
const tenantNavigation: NavItem[] = [
  { name: 'Контрольная панель', href: '/', icon: HomeIcon, permission: 'DASHBOARD:VIEW' },
  { name: 'Токены регистрации', href: '/device-tokens', icon: KeyIcon, permission: 'DEVICE_TOKENS:READ' },
  { name: 'Устройства', href: '/devices', icon: ComputerDesktopIcon, permission: 'DEVICES:READ' },
  { name: 'Пользователи', href: '/users', icon: UsersIcon, permission: 'USERS:READ' },
  { name: 'Аудит', href: '/audit', icon: DocumentTextIcon, permission: 'AUDIT:READ' },
];

export default function Sidebar({ onClose }: SidebarProps) {
  const { user } = useAuth();

  const handleClick = () => {
    onClose?.();
  };

  const isSuperAdmin = user?.roles?.includes('SUPER_ADMIN');
  const navigation = isSuperAdmin ? superAdminNavigation : tenantNavigation;

  return (
    <div className="flex grow flex-col gap-y-5 overflow-y-auto bg-indigo-950 px-6 pb-4">
      {/* Logo */}
      <div className="flex h-16 shrink-0 items-center">
        <span className="text-xl font-bold text-white">PRG Screen Recorder</span>
      </div>

      {/* Tenant switcher (only for OAuth users with multiple tenants) */}
      {!isSuperAdmin && (
        <div className="-mx-2">
          <TenantSwitcher />
        </div>
      )}

      <nav className="flex flex-1 flex-col">
        <ul role="list" className="flex flex-1 flex-col gap-y-7">
          {/* Main navigation */}
          <li>
            <ul role="list" className="-mx-2 space-y-1">
              {navigation.map((item) => (
                <PermissionGate key={item.name} permission={item.permission}>
                  <li>
                    <NavLink
                      to={item.href}
                      end={item.href === '/'}
                      onClick={handleClick}
                      className={({ isActive }) =>
                        `group flex gap-x-3 rounded-md p-2 text-sm font-semibold leading-6 ${
                          isActive
                            ? 'bg-indigo-700 text-white'
                            : 'text-indigo-200 hover:bg-indigo-700 hover:text-white'
                        }`
                      }
                    >
                      <item.icon
                        className="h-6 w-6 shrink-0"
                        aria-hidden="true"
                      />
                      {item.name}
                    </NavLink>
                  </li>
                </PermissionGate>
              ))}
            </ul>
          </li>

          {/* Secondary navigation (bottom) */}
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
              <li>
                <NavLink
                  to="/profile"
                  onClick={handleClick}
                  className={({ isActive }) =>
                    `group flex gap-x-3 rounded-md p-2 text-sm font-semibold leading-6 ${
                      isActive
                        ? 'bg-indigo-700 text-white'
                        : 'text-indigo-200 hover:bg-indigo-700 hover:text-white'
                    }`
                  }
                >
                  <UserCircleIcon className="h-6 w-6 shrink-0" aria-hidden="true" />
                  Профиль
                </NavLink>
              </li>
            </ul>
          </li>
        </ul>
      </nav>
    </div>
  );
}
