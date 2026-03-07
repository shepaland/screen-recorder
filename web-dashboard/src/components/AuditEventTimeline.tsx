import { useState, useMemo } from 'react';
import type { AuditEvent, AuditEventType } from '../types/audit-event';
import type { TimelineSession } from '../types/device';
import {
  LockClosedIcon,
  LockOpenIcon,
  ArrowRightStartOnRectangleIcon,
  ArrowLeftStartOnRectangleIcon,
  PlayIcon,
  StopIcon,
} from '@heroicons/react/24/outline';

// Event type config
const EVENT_CONFIG: Record<AuditEventType, { label: string; color: string; bgColor: string; icon: React.ElementType }> = {
  SESSION_LOCK: { label: 'Блокировка', color: 'text-red-400', bgColor: 'bg-red-900/40', icon: LockClosedIcon },
  SESSION_UNLOCK: { label: 'Разблокировка', color: 'text-green-400', bgColor: 'bg-green-900/40', icon: LockOpenIcon },
  SESSION_LOGON: { label: 'Вход', color: 'text-blue-400', bgColor: 'bg-blue-900/40', icon: ArrowRightStartOnRectangleIcon },
  SESSION_LOGOFF: { label: 'Выход', color: 'text-orange-400', bgColor: 'bg-orange-900/40', icon: ArrowLeftStartOnRectangleIcon },
  PROCESS_START: { label: 'Запуск', color: 'text-gray-400', bgColor: 'bg-gray-800', icon: PlayIcon },
  PROCESS_STOP: { label: 'Остановка', color: 'text-gray-500', bgColor: 'bg-gray-800', icon: StopIcon },
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
      <div className="flex items-center justify-between px-4 py-1.5 border-b border-gray-700/50">
        <span className="text-xs font-medium text-gray-400">
          Аудит ({filteredEvents.length})
        </span>
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

      {/* Scrollable event list */}
      <div className="overflow-x-auto overflow-y-hidden">
        <div className="flex gap-1 px-4 py-2 min-w-max">
          {filteredEvents.map((event) => {
            const config = EVENT_CONFIG[event.event_type];
            const Icon = config.icon;
            const isActive = event.id === activeEventId;
            const processName = event.details?.process_name;

            return (
              <button
                key={event.id}
                onClick={() => onEventClick(event)}
                className={`group flex items-center gap-1.5 px-2 py-1 rounded-md transition-all shrink-0 ${
                  isActive
                    ? 'bg-red-600/30 ring-1 ring-red-500'
                    : `${config.bgColor} hover:brightness-125`
                }`}
                title={`${config.label}${processName ? `: ${processName}` : ''}\n${formatTimeInTz(event.event_ts, timezone)}`}
              >
                <Icon className={`h-3.5 w-3.5 ${config.color} shrink-0`} />
                <div className="flex flex-col items-start">
                  <span className={`text-[10px] leading-tight ${config.color}`}>
                    {processName || config.label}
                  </span>
                  <span className="text-[9px] text-gray-500 leading-tight">
                    {formatTimeInTz(event.event_ts, timezone)}
                  </span>
                </div>
              </button>
            );
          })}
        </div>
      </div>
    </div>
  );
}
