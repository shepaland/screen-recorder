import { useMemo } from 'react';
import type { TimelineSession } from '../types/device';

interface DayTimelineProps {
  sessions: TimelineSession[];
  timezone: string;
  date: string;
  onSegmentClick?: (sessionId: string, segmentIndex: number) => void;
  activeSessionId?: string;
  activeSegmentIndex?: number;
}

// Parse timestamp to minutes from midnight in the device timezone
function tsToMinutes(ts: string, tz: string): number {
  try {
    const date = new Date(ts);
    const formatted = date.toLocaleString('en-US', {
      timeZone: tz,
      hour: 'numeric',
      minute: 'numeric',
      second: 'numeric',
      hour12: false,
    });
    const parts = formatted.split(':');
    const h = parseInt(parts[0], 10);
    const m = parseInt(parts[1], 10);
    const s = parseInt(parts[2], 10);
    return h * 60 + m + s / 60;
  } catch {
    return 0;
  }
}

function formatTimeInTz(ts: string, tz: string): string {
  try {
    const date = new Date(ts);
    return date.toLocaleTimeString('ru-RU', {
      timeZone: tz,
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
      hour12: false,
    });
  } catch {
    return '00:00:00';
  }
}

function getShortTzName(tz: string): string {
  try {
    const formatter = new Intl.DateTimeFormat('en-US', {
      timeZone: tz,
      timeZoneName: 'short',
    });
    const parts = formatter.formatToParts(new Date());
    const tzPart = parts.find((p) => p.type === 'timeZoneName');
    return tzPart?.value || tz;
  } catch {
    return tz;
  }
}

interface TimeBlock {
  startMin: number;
  endMin: number;
  session: TimelineSession;
  startTs: string;
  endTs: string;
}

const TOTAL_MINUTES = 24 * 60;

export default function DayTimeline({
  sessions,
  timezone,
  date,
  onSegmentClick,
  activeSessionId,
  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  activeSegmentIndex: _activeSegmentIndex,
}: DayTimelineProps) {
  const tzShort = useMemo(() => getShortTzName(timezone), [timezone]);

  // Build time blocks from sessions
  const blocks: TimeBlock[] = useMemo(() => {
    return sessions
      .map((session) => {
        const startMin = tsToMinutes(session.started_ts, timezone);
        let endMin: number;
        if (session.ended_ts) {
          endMin = tsToMinutes(session.ended_ts, timezone);
        } else {
          // Live session — extend to "now" or end of day
          const now = new Date();
          const nowDate = now.toLocaleDateString('en-CA', { timeZone: timezone });
          if (nowDate === date) {
            endMin = tsToMinutes(now.toISOString(), timezone);
          } else {
            endMin = TOTAL_MINUTES;
          }
        }
        // Handle wrap-around midnight (unlikely with per-day grouping but safe)
        if (endMin < startMin) endMin = TOTAL_MINUTES;
        // Minimum visible width
        if (endMin - startMin < 2) endMin = startMin + 2;

        return {
          startMin,
          endMin,
          session,
          startTs: session.started_ts,
          endTs: session.ended_ts || new Date().toISOString(),
        };
      })
      .sort((a, b) => a.startMin - b.startMin);
  }, [sessions, timezone, date]);

  // Hour markers
  const hours = Array.from({ length: 25 }, (_, i) => i);

  return (
    <div className="relative w-full">
      {/* Hour grid */}
      <div className="relative h-10 bg-gray-200 rounded-lg overflow-hidden">
        {/* Hour markers */}
        {hours.map((h) => (
          <div
            key={h}
            className="absolute top-0 bottom-0 border-l border-gray-300"
            style={{ left: `${(h / 24) * 100}%` }}
          />
        ))}

        {/* Recording blocks (red) */}
        {blocks.map((block, idx) => {
          const left = (block.startMin / TOTAL_MINUTES) * 100;
          const width = ((block.endMin - block.startMin) / TOTAL_MINUTES) * 100;
          const isActive = block.session.session_id === activeSessionId;
          const isLive = block.session.status === 'active';

          return (
            <div
              key={idx}
              className={`absolute top-0 bottom-0 cursor-pointer transition-all ${
                isActive
                  ? 'bg-red-700 ring-2 ring-red-400 z-10'
                  : 'bg-red-600 hover:bg-red-500 z-0'
              } ${isLive ? 'animate-pulse' : ''}`}
              style={{ left: `${left}%`, width: `${Math.max(width, 0.5)}%` }}
              onClick={() => onSegmentClick?.(block.session.session_id, 0)}
              title={`${formatTimeInTz(block.startTs, timezone)} - ${formatTimeInTz(block.endTs, timezone)} ${tzShort}`}
            />
          );
        })}
      </div>

      {/* Hour labels */}
      <div className="relative h-5 mt-0.5">
        {[0, 3, 6, 9, 12, 15, 18, 21, 24].map((h) => (
          <span
            key={h}
            className="absolute text-[10px] text-gray-500 -translate-x-1/2"
            style={{ left: `${(h / 24) * 100}%` }}
          >
            {h.toString().padStart(2, '0')}:00
          </span>
        ))}
      </div>
    </div>
  );
}
