import { useState, useEffect } from 'react';
import { getUserWorktime } from '../../api/user-activity';
import type { WorktimeResponse, WorktimeDay } from '../../types/user-activity';
import LoadingSpinner from '../LoadingSpinner';

interface WorktimeReportProps {
  username: string;
  from: string;
  to: string;
  deviceId?: string;
}

const STATUS_STYLES: Record<string, string> = {
  present: 'bg-green-100 text-green-700',
  absent: 'bg-red-100 text-red-700',
  partial: 'bg-yellow-100 text-yellow-700',
  weekend: 'bg-gray-100 text-gray-500',
  holiday: 'bg-blue-100 text-blue-700',
};

const STATUS_LABELS: Record<string, string> = {
  present: 'На месте',
  absent: 'Отсутствие',
  partial: 'Неполный день',
  weekend: 'Выходной',
  holiday: 'Праздник',
};

const WEEKDAY_LABELS: Record<string, string> = {
  mon: 'Пн',
  tue: 'Вт',
  wed: 'Ср',
  thu: 'Чт',
  fri: 'Пт',
  sat: 'Сб',
  sun: 'Вс',
};

export default function WorktimeReport({ username, from, to, deviceId }: WorktimeReportProps) {
  const [data, setData] = useState<WorktimeResponse | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    getUserWorktime(username, from, to, 'Europe/Moscow', deviceId)
      .then((resp) => { if (!cancelled) setData(resp); })
      .catch(console.error)
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [username, from, to, deviceId]);

  if (loading) {
    return (
      <div className="flex items-center justify-center py-16">
        <LoadingSpinner size="lg" />
      </div>
    );
  }

  if (!data || data.days.length === 0) {
    return (
      <div className="text-center py-16 text-gray-500">
        Нет данных за выбранный период
      </div>
    );
  }

  const presentDays = data.days.filter((d) => d.status === 'present').length;
  const absentDays = data.days.filter((d) => d.status === 'absent').length;
  const totalHours = data.days.reduce((sum, d) => sum + d.total_hours, 0);

  return (
    <div className="space-y-6">
      {/* Summary */}
      <div className="grid grid-cols-2 sm:grid-cols-4 gap-4">
        <div className="bg-white rounded-lg border border-gray-200 p-4">
          <p className="text-sm text-gray-500">Дней на работе</p>
          <p className="text-2xl font-bold text-gray-900">{presentDays}</p>
        </div>
        <div className="bg-white rounded-lg border border-gray-200 p-4">
          <p className="text-sm text-gray-500">Пропущено</p>
          <p className="text-2xl font-bold text-red-600">{absentDays}</p>
        </div>
        <div className="bg-white rounded-lg border border-gray-200 p-4">
          <p className="text-sm text-gray-500">Всего часов</p>
          <p className="text-2xl font-bold text-gray-900">{totalHours.toFixed(1)}</p>
        </div>
        <div className="bg-white rounded-lg border border-gray-200 p-4">
          <p className="text-sm text-gray-500">Опоздания</p>
          <p className="text-2xl font-bold text-yellow-600">
            {data.days.filter((d) => d.is_late).length}
          </p>
        </div>
      </div>

      {/* Table */}
      <div className="overflow-hidden rounded-lg border border-gray-200">
        <table className="min-w-full divide-y divide-gray-200">
          <thead className="bg-gray-50">
            <tr>
              <th className="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase">Дата</th>
              <th className="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase">День</th>
              <th className="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase">Статус</th>
              <th className="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase">Приход</th>
              <th className="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase">Уход</th>
              <th className="px-4 py-3 text-right text-xs font-medium text-gray-500 uppercase">Часы</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-200 bg-white">
            {data.days.map((day: WorktimeDay) => (
              <tr key={day.date} className={day.is_workday ? '' : 'bg-gray-50'}>
                <td className="px-4 py-2.5 text-sm text-gray-900">{day.date}</td>
                <td className="px-4 py-2.5 text-sm text-gray-500">
                  {WEEKDAY_LABELS[day.weekday] || day.weekday}
                </td>
                <td className="px-4 py-2.5">
                  <span className={`inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium ${STATUS_STYLES[day.status] || 'bg-gray-100 text-gray-500'}`}>
                    {STATUS_LABELS[day.status] || day.status}
                  </span>
                  {day.is_late && (
                    <span className="ml-1 inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium bg-yellow-100 text-yellow-700">
                      Опоздание
                    </span>
                  )}
                </td>
                <td className="px-4 py-2.5 text-sm text-gray-700">{day.arrival_time || '-'}</td>
                <td className="px-4 py-2.5 text-sm text-gray-700">{day.departure_time || '-'}</td>
                <td className="px-4 py-2.5 text-sm text-right font-medium text-gray-900">
                  {day.total_hours > 0 ? day.total_hours.toFixed(1) : '-'}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
