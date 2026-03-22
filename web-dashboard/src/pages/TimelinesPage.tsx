import { useState, useEffect, useCallback } from 'react';
import { ChevronLeftIcon, ChevronRightIcon, CalendarIcon } from '@heroicons/react/24/outline';
import { getTimeline } from '../api/user-activity';
import type { TimelineResponse } from '../types/timeline';
import TimelineTable from '../components/timelines/TimelineTable';
import LoadingSpinner from '../components/LoadingSpinner';

function formatDateISO(d: Date): string {
  const year = d.getFullYear();
  const month = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return `${year}-${month}-${day}`;
}

function formatDateDisplay(dateStr: string): string {
  const d = new Date(dateStr + 'T00:00:00');
  const weekdays = ['Вс', 'Пн', 'Вт', 'Ср', 'Чт', 'Пт', 'Сб'];
  const months = [
    'января', 'февраля', 'марта', 'апреля', 'мая', 'июня',
    'июля', 'августа', 'сентября', 'октября', 'ноября', 'декабря',
  ];
  return `${weekdays[d.getDay()]}, ${d.getDate()} ${months[d.getMonth()]} ${d.getFullYear()}`;
}

function isToday(dateStr: string): boolean {
  return dateStr === formatDateISO(new Date());
}

export default function TimelinesPage() {
  const [date, setDate] = useState(() => formatDateISO(new Date()));
  const [data, setData] = useState<TimelineResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const fetchData = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const result = await getTimeline(date);
      setData(result);
    } catch (err) {
      console.error('Failed to load timeline', err);
      setError('Не удалось загрузить таймлайн');
    } finally {
      setLoading(false);
    }
  }, [date]);

  useEffect(() => {
    fetchData();
  }, [fetchData]);

  const goToPrevDay = () => {
    const d = new Date(date + 'T00:00:00');
    d.setDate(d.getDate() - 1);
    setDate(formatDateISO(d));
  };

  const goToNextDay = () => {
    const d = new Date(date + 'T00:00:00');
    d.setDate(d.getDate() + 1);
    const tomorrow = new Date();
    tomorrow.setDate(tomorrow.getDate() + 1);
    if (d <= tomorrow) {
      setDate(formatDateISO(d));
    }
  };

  const goToToday = () => {
    setDate(formatDateISO(new Date()));
  };

  return (
    <div className="space-y-4">
      {/* Header */}
      <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-3">
        <div>
          <h1 className="text-2xl font-bold text-gray-900">Таймлайны</h1>
          <p className="mt-1 text-sm text-gray-500">
            Активность пользователей по часам
          </p>
        </div>

        {/* Date navigation */}
        <div className="flex items-center gap-2">
          <button
            onClick={goToPrevDay}
            className="p-2 rounded-md border border-gray-300 bg-white hover:bg-gray-50 text-gray-700 transition-colors"
            title="Предыдущий день"
          >
            <ChevronLeftIcon className="h-4 w-4" />
          </button>

          <div className="relative">
            <div className="flex items-center gap-2 px-3 py-2 rounded-md border border-gray-300 bg-white text-sm font-medium text-gray-700 sm:min-w-[220px] justify-center">
              <CalendarIcon className="h-4 w-4 text-gray-400" />
              <span>{formatDateDisplay(date)}</span>
            </div>
            <input
              type="date"
              value={date}
              max={formatDateISO(new Date())}
              onChange={(e) => {
                if (e.target.value) setDate(e.target.value);
              }}
              className="absolute inset-0 opacity-0 cursor-pointer w-full"
              aria-label="Выбрать дату"
            />
          </div>

          <button
            onClick={goToNextDay}
            disabled={isToday(date)}
            className="p-2 rounded-md border border-gray-300 bg-white hover:bg-gray-50 text-gray-700 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
            title="Следующий день"
          >
            <ChevronRightIcon className="h-4 w-4" />
          </button>

          {!isToday(date) && (
            <button
              onClick={goToToday}
              className="px-3 py-2 rounded-md border border-gray-300 bg-white hover:bg-gray-50 text-sm text-gray-700 transition-colors"
            >
              Сегодня
            </button>
          )}
        </div>
      </div>

      {/* Content */}
      {loading ? (
        <div className="flex items-center justify-center h-64">
          <LoadingSpinner size="lg" />
        </div>
      ) : error ? (
        <div className="rounded-md bg-red-50 p-4">
          <p className="text-sm text-red-700">{error}</p>
          <button
            onClick={fetchData}
            className="mt-2 text-sm font-medium text-red-600 hover:text-red-500"
          >
            Попробовать снова
          </button>
        </div>
      ) : data && data.users.length === 0 ? (
        <div className="text-center py-12">
          <CalendarIcon className="mx-auto h-12 w-12 text-gray-400" />
          <h3 className="mt-2 text-sm font-semibold text-gray-900">Нет данных</h3>
          <p className="mt-1 text-sm text-gray-500">
            За {formatDateDisplay(date)} активность не зафиксирована
          </p>
        </div>
      ) : data ? (
        <TimelineTable data={data} />
      ) : null}
    </div>
  );
}
