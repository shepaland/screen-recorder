import type { DeviceStatus } from '../types/device';

interface DeviceStatusBadgeProps {
  status: DeviceStatus;
  size?: 'sm' | 'md';
}

const statusConfig: Record<string, { label: string; color: string }> = {
  recording: { label: 'Запись', color: 'bg-green-100 text-green-800 ring-green-600/20' },
  online: { label: 'Онлайн', color: 'bg-yellow-100 text-yellow-800 ring-yellow-600/20' },
  offline: { label: 'Офлайн', color: 'bg-gray-100 text-gray-600 ring-gray-500/20' },
  error: { label: 'Ошибка', color: 'bg-red-100 text-red-800 ring-red-600/20' },
  starting: { label: 'Запуск', color: 'bg-blue-100 text-blue-800 ring-blue-600/20' },
  configuring: { label: 'Настройка', color: 'bg-indigo-100 text-indigo-800 ring-indigo-600/20' },
  awaiting_user: { label: 'Ожидание входа', color: 'bg-purple-100 text-purple-800 ring-purple-600/20' },
  idle: { label: 'Неактивен', color: 'bg-orange-100 text-orange-800 ring-orange-600/20' },
  stopped: { label: 'Остановлен', color: 'bg-gray-200 text-gray-700 ring-gray-600/20' },
};

export default function DeviceStatusBadge({ status, size = 'md' }: DeviceStatusBadgeProps) {
  const config = statusConfig[status] || statusConfig.offline;
  const sizeClasses = size === 'sm'
    ? 'px-1.5 py-0.5 text-[10px]'
    : 'px-2.5 py-0.5 text-xs';

  return (
    <span
      className={`inline-flex items-center rounded-full font-medium ring-1 ring-inset ${sizeClasses} ${config.color}`}
    >
      {config.label}
    </span>
  );
}
