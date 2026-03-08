import { useEffect, useState } from 'react';
import {
  ComputerDesktopIcon,
  SignalIcon,
  KeyIcon,
  CircleStackIcon,
} from '@heroicons/react/24/outline';
import * as catalogsApi from '../../api/catalogs';
import type { DashboardMetrics } from '../../types/catalogs';

function formatBytes(bytes: number): string {
  if (bytes === 0) return '0 Б';
  const units = ['Б', 'КБ', 'МБ', 'ГБ', 'ТБ'];
  const i = Math.floor(Math.log(bytes) / Math.log(1024));
  const val = bytes / Math.pow(1024, i);
  return `${val.toFixed(val >= 100 ? 0 : 1)} ${units[i]}`;
}

export default function MetricsRow() {
  const [metrics, setMetrics] = useState<DashboardMetrics | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = async () => {
    setLoading(true);
    setError(null);
    try {
      const data = await catalogsApi.getDashboardMetrics();
      setMetrics(data);
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : 'Failed to load metrics';
      setError(msg);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    load();
  }, []);

  if (error) {
    return (
      <div className="rounded-lg border border-red-200 bg-red-50 p-4">
        <p className="text-sm text-red-700">Ошибка загрузки метрик: {error}</p>
        <button onClick={load} className="mt-2 text-sm font-medium text-red-700 underline hover:text-red-600">
          Повторить
        </button>
      </div>
    );
  }

  const cards = [
    {
      title: 'Подключенные устройства',
      value: loading ? '...' : `${metrics?.connected_devices ?? 0} / ${metrics?.total_devices ?? 0}`,
      subtitle: 'устройств',
      icon: ComputerDesktopIcon,
      color: 'bg-blue-50 text-blue-600',
    },
    {
      title: 'В работе',
      value: loading ? '...' : `${metrics?.active_devices ?? 0}`,
      subtitle: 'устройств',
      icon: SignalIcon,
      color: 'bg-green-50 text-green-600',
    },
    {
      title: 'Токены',
      value: loading ? '...' : `${metrics?.tokens_used ?? 0} / ${metrics?.tokens_total ?? 0}`,
      subtitle: 'использовано',
      icon: KeyIcon,
      color: 'bg-purple-50 text-purple-600',
      progress: metrics ? (metrics.tokens_total > 0 ? (metrics.tokens_used / metrics.tokens_total) * 100 : 0) : 0,
    },
    {
      title: 'Объём видео',
      value: loading ? '...' : formatBytes(metrics?.video_size_bytes ?? 0),
      subtitle: '',
      icon: CircleStackIcon,
      color: 'bg-amber-50 text-amber-600',
    },
  ];

  return (
    <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
      {cards.map((card) => (
        <div key={card.title} className="card flex items-start gap-x-4">
          <div className={`flex h-12 w-12 shrink-0 items-center justify-center rounded-lg ${card.color}`}>
            <card.icon className="h-6 w-6" aria-hidden="true" />
          </div>
          <div className="min-w-0 flex-1">
            <p className="text-sm font-medium text-gray-500 truncate">{card.title}</p>
            <p className={`text-2xl font-semibold text-gray-900 ${loading ? 'animate-pulse' : ''}`}>
              {card.value}
            </p>
            {card.subtitle && (
              <p className="text-xs text-gray-400">{card.subtitle}</p>
            )}
            {card.progress !== undefined && card.progress > 0 && !loading && (
              <div className="mt-1.5 h-1.5 w-full rounded-full bg-gray-200">
                <div
                  className="h-1.5 rounded-full bg-purple-500 transition-all"
                  style={{ width: `${Math.min(card.progress, 100)}%` }}
                />
              </div>
            )}
          </div>
        </div>
      ))}
    </div>
  );
}
