import {
  UsersIcon,
  ComputerDesktopIcon,
  VideoCameraIcon,
  CircleStackIcon,
} from '@heroicons/react/24/outline';
import { useAuth } from '../hooks/useAuth';

const stats = [
  { name: 'Active Users', value: '--', icon: UsersIcon, change: '' },
  { name: 'Active Devices', value: '--', icon: ComputerDesktopIcon, change: '' },
  { name: 'Recordings Today', value: '--', icon: VideoCameraIcon, change: '' },
  { name: 'Storage Used', value: '--', icon: CircleStackIcon, change: '' },
];

export default function DashboardPage() {
  const { user } = useAuth();

  return (
    <div>
      {/* Page header */}
      <div className="mb-8">
        <h1 className="text-2xl font-bold tracking-tight text-gray-900">Dashboard</h1>
        <p className="mt-2 text-sm text-gray-600">
          Welcome back, {user?.first_name || user?.username}. Here is an overview of your
          screen recording platform.
        </p>
      </div>

      {/* Stats grid */}
      <div className="grid grid-cols-1 gap-6 sm:grid-cols-2 lg:grid-cols-4">
        {stats.map((stat) => (
          <div
            key={stat.name}
            className="card flex items-center gap-x-4"
          >
            <div className="flex h-12 w-12 items-center justify-center rounded-lg bg-indigo-50">
              <stat.icon className="h-6 w-6 text-indigo-600" aria-hidden="true" />
            </div>
            <div>
              <p className="text-sm font-medium text-gray-500">{stat.name}</p>
              <p className="text-2xl font-semibold text-gray-900">{stat.value}</p>
            </div>
          </div>
        ))}
      </div>

      {/* Placeholder content */}
      <div className="mt-8 card">
        <h2 className="text-lg font-semibold text-gray-900">Getting Started</h2>
        <p className="mt-2 text-sm text-gray-600">
          This dashboard will display real-time metrics once recording agents are connected.
          Use the sidebar navigation to manage users, roles, and view audit logs.
        </p>
        <div className="mt-4 grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
          <div className="rounded-lg border border-gray-200 p-4">
            <h3 className="text-sm font-medium text-gray-900">1. Set up users</h3>
            <p className="mt-1 text-sm text-gray-500">
              Create user accounts and assign roles for your team members.
            </p>
          </div>
          <div className="rounded-lg border border-gray-200 p-4">
            <h3 className="text-sm font-medium text-gray-900">2. Configure roles</h3>
            <p className="mt-1 text-sm text-gray-500">
              Define custom roles with specific permissions for your organization.
            </p>
          </div>
          <div className="rounded-lg border border-gray-200 p-4">
            <h3 className="text-sm font-medium text-gray-900">3. Install agents</h3>
            <p className="mt-1 text-sm text-gray-500">
              Deploy recording agents on operator workstations to start capturing screens.
            </p>
          </div>
        </div>
      </div>
    </div>
  );
}
