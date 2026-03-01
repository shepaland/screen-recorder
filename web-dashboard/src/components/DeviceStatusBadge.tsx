interface DeviceStatusBadgeProps {
  status: 'recording' | 'online' | 'offline' | 'error';
}

const statusConfig: Record<string, { label: string; color: string }> = {
  recording: { label: 'Запись', color: 'bg-green-100 text-green-800 ring-green-600/20' },
  online: { label: 'Онлайн', color: 'bg-yellow-100 text-yellow-800 ring-yellow-600/20' },
  offline: { label: 'Офлайн', color: 'bg-gray-100 text-gray-600 ring-gray-500/20' },
  error: { label: 'Ошибка', color: 'bg-red-100 text-red-800 ring-red-600/20' },
};

export default function DeviceStatusBadge({ status }: DeviceStatusBadgeProps) {
  const config = statusConfig[status] || statusConfig.offline;

  return (
    <span
      className={`inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-medium ring-1 ring-inset ${config.color}`}
    >
      {config.label}
    </span>
  );
}
