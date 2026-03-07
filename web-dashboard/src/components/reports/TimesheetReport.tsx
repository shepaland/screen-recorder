import { useState, useEffect } from 'react';
import { getUserTimesheet } from '../../api/user-activity';
import type { TimesheetResponse } from '../../types/user-activity';
import LoadingSpinner from '../LoadingSpinner';

interface TimesheetReportProps {
  username: string;
  month: string;
  deviceId?: string;
}

const STATUS_COLORS: Record<string, string> = {
  present: 'bg-green-50',
  absent: 'bg-red-50',
  partial: 'bg-yellow-50',
  weekend: 'bg-gray-50',
  holiday: 'bg-blue-50',
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

export default function TimesheetReport({ username, month, deviceId }: TimesheetReportProps) {
  const [data, setData] = useState<TimesheetResponse | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    getUserTimesheet(username, month, { deviceId })
      .then((resp) => { if (!cancelled) setData(resp); })
      .catch(console.error)
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [username, month, deviceId]);

  if (loading) {
    return (
      <div className="flex items-center justify-center py-16">
        <LoadingSpinner size="lg" />
      </div>
    );
  }

  if (!data) {
    return (
      <div className="text-center py-16 text-gray-500">
        Нет данных за выбранный месяц
      </div>
    );
  }

  const { summary, days } = data;

  return (
    <div className="space-y-6">
      {/* Summary */}
      <div className="grid grid-cols-2 sm:grid-cols-4 lg:grid-cols-5 gap-4">
        <div className="bg-white rounded-lg border border-gray-200 p-4">
          <p className="text-sm text-gray-500">Рабочих дней</p>
          <p className="text-2xl font-bold text-gray-900">{summary.work_days_in_month}</p>
        </div>
        <div className="bg-white rounded-lg border border-gray-200 p-4">
          <p className="text-sm text-gray-500">Присутствие</p>
          <p className="text-2xl font-bold text-green-600">{summary.days_present}</p>
        </div>
        <div className="bg-white rounded-lg border border-gray-200 p-4">
          <p className="text-sm text-gray-500">Отсутствие</p>
          <p className="text-2xl font-bold text-red-600">{summary.days_absent}</p>
        </div>
        <div className="bg-white rounded-lg border border-gray-200 p-4">
          <p className="text-sm text-gray-500">Плановые часы</p>
          <p className="text-2xl font-bold text-gray-900">{summary.total_expected_hours.toFixed(0)}</p>
        </div>
        <div className="bg-white rounded-lg border border-gray-200 p-4">
          <p className="text-sm text-gray-500">Фактические часы</p>
          <p className="text-2xl font-bold text-gray-900">{summary.total_actual_hours.toFixed(1)}</p>
        </div>
      </div>

      {summary.avg_arrival_time && (
        <div className="flex gap-6 text-sm text-gray-500">
          <span>Средний приход: <strong className="text-gray-900">{summary.avg_arrival_time}</strong></span>
          <span>Средний уход: <strong className="text-gray-900">{summary.avg_departure_time}</strong></span>
          {summary.total_overtime_hours > 0 && (
            <span>Переработка: <strong className="text-orange-600">{summary.total_overtime_hours.toFixed(1)} ч.</strong></span>
          )}
        </div>
      )}

      {/* Days table */}
      <div className="overflow-hidden rounded-lg border border-gray-200">
        <table className="min-w-full divide-y divide-gray-200">
          <thead className="bg-gray-50">
            <tr>
              <th className="px-3 py-2.5 text-left text-xs font-medium text-gray-500 uppercase">Дата</th>
              <th className="px-3 py-2.5 text-left text-xs font-medium text-gray-500 uppercase">День</th>
              <th className="px-3 py-2.5 text-left text-xs font-medium text-gray-500 uppercase">Статус</th>
              <th className="px-3 py-2.5 text-left text-xs font-medium text-gray-500 uppercase">Приход</th>
              <th className="px-3 py-2.5 text-left text-xs font-medium text-gray-500 uppercase">Уход</th>
              <th className="px-3 py-2.5 text-right text-xs font-medium text-gray-500 uppercase">Всего</th>
              <th className="px-3 py-2.5 text-right text-xs font-medium text-gray-500 uppercase">Актив.</th>
              <th className="px-3 py-2.5 text-right text-xs font-medium text-gray-500 uppercase">Простой</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-200">
            {days.map((day) => (
              <tr key={day.date} className={STATUS_COLORS[day.status] || ''}>
                <td className="px-3 py-2 text-sm text-gray-900">{day.date}</td>
                <td className="px-3 py-2 text-sm text-gray-500">
                  {WEEKDAY_LABELS[day.weekday] || day.weekday}
                </td>
                <td className="px-3 py-2 text-sm">
                  <span className="text-gray-700">{STATUS_LABELS[day.status] || day.status}</span>
                  {day.is_late && <span className="ml-1 text-xs text-yellow-600">[опоздание]</span>}
                  {day.is_early_leave && <span className="ml-1 text-xs text-orange-600">[ранний уход]</span>}
                </td>
                <td className="px-3 py-2 text-sm text-gray-700">{day.arrival_time || '-'}</td>
                <td className="px-3 py-2 text-sm text-gray-700">{day.departure_time || '-'}</td>
                <td className="px-3 py-2 text-sm text-right font-medium text-gray-900">
                  {day.total_hours > 0 ? day.total_hours.toFixed(1) : '-'}
                </td>
                <td className="px-3 py-2 text-sm text-right text-gray-700">
                  {day.active_hours > 0 ? day.active_hours.toFixed(1) : '-'}
                </td>
                <td className="px-3 py-2 text-sm text-right text-gray-500">
                  {day.idle_hours > 0 ? day.idle_hours.toFixed(1) : '-'}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
