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
} from '@heroicons/react/24/outline';
import PermissionGate from './PermissionGate';

interface SidebarProps {
  onClose?: () => void;
}

interface NavItem {
  name: string;
  href: string;
  icon: typeof HomeIcon;
  permission?: string;
}

const navigation: NavItem[] = [
  { name: 'Dashboard', href: '/', icon: HomeIcon, permission: 'DASHBOARD:VIEW' },
  { name: 'Users', href: '/users', icon: UsersIcon, permission: 'USERS:READ' },
  { name: 'Roles', href: '/roles', icon: ShieldCheckIcon, permission: 'ROLES:READ' },
  { name: 'Устройства', href: '/devices', icon: ComputerDesktopIcon, permission: 'DEVICES:READ' },
  { name: 'Токены регистрации', href: '/device-tokens', icon: KeyIcon, permission: 'DEVICE_TOKENS:READ' },
  { name: 'Audit Log', href: '/audit', icon: DocumentTextIcon, permission: 'AUDIT:READ' },
  { name: 'Tenants', href: '/tenants', icon: BuildingOfficeIcon, permission: 'TENANTS:READ' },
];

const secondaryNavigation: NavItem[] = [
  { name: 'Profile', href: '/profile', icon: UserCircleIcon },
];

export default function Sidebar({ onClose }: SidebarProps) {
  const handleClick = () => {
    onClose?.();
  };

  return (
    <div className="flex grow flex-col gap-y-5 overflow-y-auto bg-indigo-950 px-6 pb-4">
      {/* Logo */}
      <div className="flex h-16 shrink-0 items-center">
        <span className="text-xl font-bold text-white">PRG Screen Recorder</span>
      </div>

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
              {secondaryNavigation.map((item) => (
                <li key={item.name}>
                  <NavLink
                    to={item.href}
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
              ))}
            </ul>
          </li>
        </ul>
      </nav>
    </div>
  );
}
