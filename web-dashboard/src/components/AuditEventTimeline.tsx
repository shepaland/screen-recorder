import { useState, useMemo } from 'react';
import type { AuditEvent, AuditEventType } from '../types/audit-event';
import type { TimelineSession } from '../types/device';

// Event type config
const EVENT_TYPE_LABELS: Record<AuditEventType, { label: string; color: string; bgColor: string }> = {
  SESSION_LOCK: { label: 'Блокировка', color: 'text-red-400', bgColor: 'bg-red-900/30 text-red-300 ring-red-500/30' },
  SESSION_UNLOCK: { label: 'Разблокировка', color: 'text-green-400', bgColor: 'bg-green-900/30 text-green-300 ring-green-500/30' },
  SESSION_LOGON: { label: 'Вход в систему', color: 'text-blue-400', bgColor: 'bg-blue-900/30 text-blue-300 ring-blue-500/30' },
  SESSION_LOGOFF: { label: 'Выход из системы', color: 'text-orange-400', bgColor: 'bg-orange-900/30 text-orange-300 ring-orange-500/30' },
  PROCESS_START: { label: 'Запуск процесса', color: 'text-gray-400', bgColor: 'bg-gray-700/50 text-gray-300 ring-gray-500/30' },
  PROCESS_STOP: { label: 'Остановка процесса', color: 'text-gray-500', bgColor: 'bg-gray-700/50 text-gray-400 ring-gray-500/30' },
};

// Filter options
const FILTER_OPTIONS: { value: string; label: string }[] = [
  { value: 'all', label: 'Все' },
  { value: 'session', label: 'Сессия' },
  { value: 'process', label: 'Процессы' },
];

interface AuditEventTimelineProps {
  events: AuditEvent[];
  timezone: string;
  sessions: TimelineSession[];
  onEventClick: (event: AuditEvent) => void;
  activeEventId?: string;
}

function formatTimeInTz(isoTs: string, tz: string): string {
  try {
    return new Date(isoTs).toLocaleTimeString('ru-RU', {
      timeZone: tz,
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
    });
  } catch {
    return isoTs.substring(11, 19);
  }
}

function getEventDetails(event: AuditEvent): string {
  if (!event.details) return '\u2014';
  const processName = event.details.process_name as string | undefined;
  const reason = event.details.reason as string | undefined;
  if (processName) return processName;
  if (reason) return reason;
  return '\u2014';
}

export default function AuditEventTimeline({
  events,
  timezone,
  onEventClick,
  activeEventId,
}: AuditEventTimelineProps) {
  const [filter, setFilter] = useState('all');

  const filteredEvents = useMemo(() => {
    if (filter === 'all') return events;
    if (filter === 'session') return events.filter((e) => e.event_type.startsWith('SESSION_'));
    if (filter === 'process') return events.filter((e) => e.event_type.startsWith('PROCESS_'));
    return events;
  }, [events, filter]);

  if (events.length === 0) {
    return (
      <div className="px-4 py-2 bg-gray-800 border-t border-gray-700">
        <p className="text-xs text-gray-500 text-center">Нет событий аудита</p>
      </div>
    );
  }

  return (
    <div className="bg-gray-800 border-t border-gray-700">
      {/* Header with filter */}
      <div className="flex items-center justify-between px-4 py-2 border-b border-gray-700/50">
        <h3 className="text-sm font-semibold text-gray-300">
          Аудит событий ({filteredEvents.length})
        </h3>
        <div className="flex gap-1">
          {FILTER_OPTIONS.map((opt) => (
            <button
              key={opt.value}
              onClick={() => setFilter(opt.value)}
              className={`px-2 py-0.5 text-[10px] rounded transition-colors ${
                filter === opt.value
                  ? 'bg-red-600 text-white'
                  : 'text-gray-500 hover:text-gray-300 hover:bg-gray-700'
              }`}
            >
              {opt.label}
            </button>
          ))}
        </div>
      </div>

      {/* Events table */}
      <div className="overflow-y-auto max-h-48">
        <table className="min-w-full divide-y divide-gray-700">
          <thead className="bg-gray-800/80 sticky top-0">
            <tr>
              <th className="px-3 py-2 text-left text-xs font-semibold text-gray-400">Тип</th>
              <th className="px-3 py-2 text-left text-xs font-semibold text-gray-400">Время</th>
              <th className="px-3 py-2 text-left text-xs font-semibold text-gray-400">Сессия</th>
              <th className="px-3 py-2 text-left text-xs font-semibold text-gray-400">Детали</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-700/50">
            {filteredEvents.map((event) => {
              const config = EVENT_TYPE_LABELS[event.event_type];
              const isActive = event.id === activeEventId;

              return (
                <tr
                  key={event.id}
                  onClick={() => onEventClick(event)}
                  className={`cursor-pointer transition-colors ${
                    isActive
                      ? 'bg-red-900/20'
                      : 'hover:bg-gray-700/50'
                  }`}
                >
                  <td className="whitespace-nowrap px-3 py-1.5 text-xs">
                    <span
                      className={`inline-flex items-center rounded-full px-2 py-0.5 text-[10px] font-medium ring-1 ring-inset ${config.bgColor}`}
                    >
                      {config.label}
                    </span>
                  </td>
                  <td className="whitespace-nowrap px-3 py-1.5 text-xs text-gray-300">
                    {formatTimeInTz(event.event_ts, timezone)}
                  </td>
                  <td className="whitespace-nowrap px-3 py-1.5 text-xs text-gray-500">
                    {event.session_id ? event.session_id.substring(0, 8) : '\u2014'}
                  </td>
                  <td className="whitespace-nowrap px-3 py-1.5 text-xs text-gray-400">
                    {getEventDetails(event)}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    </div>
  );
}
