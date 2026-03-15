import { useState, useEffect } from 'react';
import { getUserActivity } from '../../api/user-activity';
import type { UserActivityResponse } from '../../types/user-activity';
import LoadingSpinner from '../LoadingSpinner';

interface TimeReportProps {
  username: string;
  from: string;
  to: string;
  deviceId?: string;
}

function formatDuration(ms: number): string {
  if (ms < 60000) return `${Math.round(ms / 1000)}с`;
  const minutes = Math.floor(ms / 60000);
  const hours = Math.floor(minutes / 60);
  const mins = minutes % 60;
  if (hours === 0) return `${mins}м`;
  return `${hours}ч ${mins}м`;
}

function formatDateRu(dateStr: string): string {
  try {
    const [y, m, d] = dateStr.split('-');
    const date = new Date(Number(y), Number(m) - 1, Number(d));
    return date.toLocaleDateString('ru-RU', {
      weekday: 'short',
      day: 'numeric',
      month: 'short',
    });
  } catch {
    return dateStr;
  }
}

export default function TimeReport({ username, from, to, deviceId }: TimeReportProps) {
  const [data, setData] = useState<UserActivityResponse | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    getUserActivity(username, from, to, deviceId)
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

  if (!data) {
    return (
      <div className="text-center py-16 text-gray-500">
        Нет данных за выбранный период
      </div>
    );
  }

  const { summary, top_apps, top_domains, daily_breakdown } = data;
  const maxDailyMs = daily_breakdown.length > 0
    ? Math.max(...daily_breakdown.map((d) => d.total_active_ms))
    : 1;

  return (
    <div className="space-y-6">
      {/* Summary cards */}
      <div className="grid grid-cols-2 sm:grid-cols-4 lg:grid-cols-7 gap-3">
        <div className="bg-white rounded-lg border border-gray-200 p-3">
          <p className="text-xs text-gray-500">Общее время</p>
          <p className="text-lg font-bold text-gray-900">{formatDuration(summary.total_active_ms)}</p>
        </div>
        <div className="bg-white rounded-lg border border-green-200 p-3">
          <p className="text-xs text-green-600">Реальная активность</p>
          <p className="text-lg font-bold text-green-700">{formatDuration(summary.real_active_ms)}</p>
          {summary.total_active_ms > 0 && (
            <p className="text-xs text-green-500">{Math.round(summary.real_active_ms / summary.total_active_ms * 100)}%</p>
          )}
        </div>
        <div className="bg-white rounded-lg border border-gray-200 p-3">
          <p className="text-xs text-gray-500">Дней активности</p>
          <p className="text-lg font-bold text-gray-900">{summary.total_days_active}</p>
        </div>
        <div className="bg-white rounded-lg border border-gray-200 p-3">
          <p className="text-xs text-gray-500">Среднее/день</p>
          <p className="text-lg font-bold text-gray-900">{formatDuration(summary.avg_daily_active_ms)}</p>
        </div>
        <div className="bg-white rounded-lg border border-gray-200 p-3">
          <p className="text-xs text-gray-500">Сессий</p>
          <p className="text-lg font-bold text-gray-900">{summary.total_sessions}</p>
        </div>
        <div className="bg-white rounded-lg border border-gray-200 p-3">
          <p className="text-xs text-gray-500">Интервалов</p>
          <p className="text-lg font-bold text-gray-900">{summary.total_focus_intervals}</p>
        </div>
        <div className="bg-white rounded-lg border border-gray-200 p-3">
          <p className="text-xs text-gray-500">Приложений</p>
          <p className="text-lg font-bold text-gray-900">{summary.unique_apps}</p>
        </div>
        <div className="bg-white rounded-lg border border-gray-200 p-3">
          <p className="text-xs text-gray-500">Доменов</p>
          <p className="text-lg font-bold text-gray-900">{summary.unique_domains}</p>
        </div>
      </div>

      {/* Daily chart */}
      <div className="bg-white rounded-lg border border-gray-200 p-5">
        <h3 className="text-lg font-semibold text-gray-900 mb-4">Активность по дням</h3>
        {daily_breakdown.length > 0 ? (
          <>
          <div className="space-y-2">
            {daily_breakdown.map((day) => {
              const totalPct = (day.total_active_ms / maxDailyMs) * 100;
              const realPct = day.total_active_ms > 0
                ? (day.real_active_ms / day.total_active_ms) * totalPct
                : 0;
              const idlePct = totalPct - realPct;
              return (
                <div key={day.date} className="flex items-center gap-3">
                  <span className="text-xs text-gray-500 w-24 shrink-0">{formatDateRu(day.date)}</span>
                  <div className="flex-1 bg-gray-100 rounded-full h-4 flex overflow-hidden">
                    <div
                      className="bg-green-500 h-4 transition-all"
                      style={{ width: `${realPct}%` }}
                      title={`Активно: ${formatDuration(day.real_active_ms)}`}
                    />
                    {idlePct > 0 && (
                      <div
                        className="bg-red-300 h-4 transition-all"
                        style={{ width: `${idlePct}%` }}
                        title={`Idle: ${formatDuration(day.total_active_ms - day.real_active_ms)}`}
                      />
                    )}
                  </div>
                  <span className="text-xs font-medium text-gray-700 w-16 text-right shrink-0">
                    {formatDuration(day.real_active_ms)}
                  </span>
                </div>
              );
            })}
          </div>
          <div className="flex gap-4 mt-3">
            <div className="flex items-center gap-1.5">
              <div className="w-3 h-3 rounded-full bg-green-500" />
              <span className="text-xs text-gray-500">Активно</span>
            </div>
            <div className="flex items-center gap-1.5">
              <div className="w-3 h-3 rounded-full bg-red-300" />
              <span className="text-xs text-gray-500">Idle</span>
            </div>
          </div>
          </>
        ) : (
          <p className="text-sm text-gray-500">Нет данных</p>
        )}
      </div>

      {/* Top apps & domains */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        <div className="bg-white rounded-lg border border-gray-200 p-5">
          <h3 className="text-base font-semibold text-gray-900 mb-3">Топ приложений</h3>
          {top_apps.length > 0 ? (
            <div className="space-y-2">
              {top_apps.map((app) => (
                <div key={app.process_name} className="flex items-center justify-between">
                  <span className="text-sm text-gray-700 truncate mr-2">{app.process_name}</span>
                  <span className="text-sm font-medium text-gray-900 shrink-0">
                    {formatDuration(app.total_duration_ms)} ({app.percentage.toFixed(1)}%)
                  </span>
                </div>
              ))}
            </div>
          ) : (
            <p className="text-sm text-gray-500">Нет данных</p>
          )}
        </div>

        <div className="bg-white rounded-lg border border-gray-200 p-5">
          <h3 className="text-base font-semibold text-gray-900 mb-3">Топ сайтов</h3>
          {top_domains.length > 0 ? (
            <div className="space-y-2">
              {top_domains.map((domain) => (
                <div key={`${domain.domain}-${domain.browser_name}`} className="flex items-center justify-between">
                  <span className="text-sm text-gray-700 truncate mr-2">{domain.domain}</span>
                  <span className="text-sm font-medium text-gray-900 shrink-0">
                    {formatDuration(domain.total_duration_ms)} ({domain.percentage.toFixed(1)}%)
                  </span>
                </div>
              ))}
            </div>
          ) : (
            <p className="text-sm text-gray-500">Нет данных</p>
          )}
        </div>
      </div>
    </div>
  );
}
