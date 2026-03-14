import {
  ComputerDesktopIcon,
  SignalIcon,
  VideoCameraIcon,
} from '@heroicons/react/24/outline';

interface Props {
  totalDevices: number;
  onlineDevices: number;
  recordingDevices: number;
}

export default function DeviceGroupStats({ totalDevices, onlineDevices, recordingDevices }: Props) {
  const stats = [
    {
      label: 'Всего устройств',
      value: totalDevices,
      icon: ComputerDesktopIcon,
      color: 'text-blue-600 bg-blue-50',
    },
    {
      label: 'Онлайн',
      value: onlineDevices,
      icon: SignalIcon,
      color: 'text-green-600 bg-green-50',
    },
    {
      label: 'Ведётся запись',
      value: recordingDevices,
      icon: VideoCameraIcon,
      color: 'text-red-600 bg-red-50',
    },
  ];

  return (
    <div className="grid grid-cols-3 gap-4 mb-6">
      {stats.map((stat) => (
        <div
          key={stat.label}
          className="flex items-center gap-3 rounded-lg border border-gray-200 bg-white px-4 py-3"
        >
          <div className={`rounded-lg p-2 ${stat.color}`}>
            <stat.icon className="h-5 w-5" />
          </div>
          <div>
            <p className="text-2xl font-semibold text-gray-900">{stat.value}</p>
            <p className="text-xs text-gray-500">{stat.label}</p>
          </div>
        </div>
      ))}
    </div>
  );
}
