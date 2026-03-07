import { useState, useEffect, useCallback, useRef, useMemo } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import {
  getDeviceRecordingDays,
  getDeviceDayTimeline,
  getDeviceAuditEvents,
  downloadRecording,
} from '../api/ingest';
import { getDevice } from '../api/devices';
import type {
  DeviceDaysResponse,
  DayTimelineResponse,
  TimelineSegment,
  DeviceDetailResponse,
} from '../types/device';
import type { AuditEvent } from '../types/audit-event';
import DayTimeline from '../components/DayTimeline';
import AuditEventTimeline from '../components/AuditEventTimeline';
import LoadingSpinner from '../components/LoadingSpinner';
import { formatBytes, formatDuration } from '../utils/timeAgo';
import {
  ArrowLeftIcon,
  CalendarDaysIcon,
  ChevronLeftIcon,
  ChevronRightIcon,
  ArrowDownTrayIcon,
  PlayIcon,
  SignalIcon,
} from '@heroicons/react/24/outline';

const basePath = import.meta.env.BASE_URL.replace(/\/$/, '');

function buildSegmentUrl(s3Key: string): string {
  return `${basePath}/prg-segments/${s3Key}`;
}

function formatDateRu(dateStr: string): string {
  try {
    const [y, m, d] = dateStr.split('-');
    const date = new Date(Number(y), Number(m) - 1, Number(d));
    return date.toLocaleDateString('ru-RU', {
      weekday: 'short',
      day: 'numeric',
      month: 'long',
    });
  } catch {
    return dateStr;
  }
}

export default function DeviceRecordingsPage() {
  const { deviceId } = useParams<{ deviceId: string }>();
  const navigate = useNavigate();

  // Device info
  const [device, setDevice] = useState<DeviceDetailResponse | null>(null);

  // Days list
  const [daysData, setDaysData] = useState<DeviceDaysResponse | null>(null);
  const [daysLoading, setDaysLoading] = useState(true);
  const [daysPage, setDaysPage] = useState(0);

  // Selected day timeline
  const [selectedDate, setSelectedDate] = useState<string | null>(null);
  const [timeline, setTimeline] = useState<DayTimelineResponse | null>(null);
  const [timelineLoading, setTimelineLoading] = useState(false);

  // Audit events
  const [auditEvents, setAuditEvents] = useState<AuditEvent[]>([]);
  const [auditLoading, setAuditLoading] = useState(false);
  const [activeAuditEventId, setActiveAuditEventId] = useState<string | undefined>();

  // Video player state
  const [activeSessionId, setActiveSessionId] = useState<string | null>(null);
  const [activeSegmentIndex, setActiveSegmentIndex] = useState(0);
  const [videoUrl, setVideoUrl] = useState<string | null>(null);
  const videoRef = useRef<HTMLVideoElement>(null);

  // All segments of active session
  const activeSession = useMemo(
    () => timeline?.sessions.find((s) => s.session_id === activeSessionId) || null,
    [timeline, activeSessionId],
  );

  const activeSegments = useMemo(() => activeSession?.segments || [], [activeSession]);

  const activeSegment: TimelineSegment | null = useMemo(
    () => (activeSegments.length > activeSegmentIndex ? activeSegments[activeSegmentIndex] : null),
    [activeSegments, activeSegmentIndex],
  );

  // Fetch device info
  useEffect(() => {
    if (!deviceId) return;
    getDevice(deviceId)
      .then(setDevice)
      .catch(console.error);
  }, [deviceId]);

  // Fetch recording days
  const fetchDays = useCallback(async () => {
    if (!deviceId) return;
    try {
      setDaysLoading(true);
      const resp = await getDeviceRecordingDays(deviceId, { page: daysPage, size: 30 });
      setDaysData(resp);
      // Auto-select first day if none selected
      if (!selectedDate && resp.days.length > 0) {
        setSelectedDate(resp.days[0].date);
      }
    } catch (err) {
      console.error('Failed to load recording days', err);
    } finally {
      setDaysLoading(false);
    }
  }, [deviceId, daysPage, selectedDate]);

  useEffect(() => {
    fetchDays();
  }, [fetchDays]);

  // Fetch timeline for selected date
  useEffect(() => {
    if (!deviceId || !selectedDate) return;
    let cancelled = false;

    const fetchTimeline = async () => {
      try {
        setTimelineLoading(true);
        const resp = await getDeviceDayTimeline(deviceId, selectedDate);
        if (cancelled) return;
        setTimeline(resp);
        // Auto-select first session
        if (resp.sessions.length > 0) {
          setActiveSessionId(resp.sessions[0].session_id);
          setActiveSegmentIndex(0);
        }
      } catch (err) {
        console.error('Failed to load timeline', err);
        if (!cancelled) setTimeline(null);
      } finally {
        if (!cancelled) setTimelineLoading(false);
      }
    };

    fetchTimeline();
    return () => { cancelled = true; };
  }, [deviceId, selectedDate]);

  // Fetch audit events for selected date
  useEffect(() => {
    if (!deviceId || !selectedDate) return;
    let cancelled = false;

    const fetchAuditEvents = async () => {
      try {
        setAuditLoading(true);
        const resp = await getDeviceAuditEvents(deviceId, selectedDate, { size: 1000 });
        if (!cancelled) setAuditEvents(resp.events);
      } catch (err) {
        console.error('Failed to load audit events', err);
        if (!cancelled) setAuditEvents([]);
      } finally {
        if (!cancelled) setAuditLoading(false);
      }
    };

    fetchAuditEvents();
    return () => { cancelled = true; };
  }, [deviceId, selectedDate]);

  // Update video URL when segment changes
  useEffect(() => {
    if (activeSegment && activeSegment.s3_key) {
      setVideoUrl(buildSegmentUrl(activeSegment.s3_key));
    } else {
      setVideoUrl(null);
    }
  }, [activeSegment]);

  // Autoplay when video src changes
  useEffect(() => {
    if (videoRef.current && videoUrl) {
      videoRef.current.load();
      videoRef.current.play().catch(() => {});
    }
  }, [videoUrl]);

  // Keyboard navigation
  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.target instanceof HTMLInputElement || e.target instanceof HTMLTextAreaElement) return;
      if (e.key === 'ArrowLeft' && activeSegmentIndex > 0) {
        setActiveSegmentIndex((prev) => prev - 1);
      } else if (e.key === 'ArrowRight' && activeSegmentIndex < activeSegments.length - 1) {
        setActiveSegmentIndex((prev) => prev + 1);
      }
    };
    document.addEventListener('keydown', handleKeyDown);
    return () => document.removeEventListener('keydown', handleKeyDown);
  }, [activeSegmentIndex, activeSegments.length]);

  // Auto-advance to next segment
  const handleVideoEnded = () => {
    if (activeSegmentIndex < activeSegments.length - 1) {
      setActiveSegmentIndex((prev) => prev + 1);
    }
  };

  // Handle segment click from timeline
  const handleTimelineSegmentClick = (sessionId: string, segIndex: number) => {
    setActiveSessionId(sessionId);
    setActiveSegmentIndex(segIndex);
  };

  // Handle day select
  const handleDaySelect = (date: string) => {
    setSelectedDate(date);
    setActiveSessionId(null);
    setActiveSegmentIndex(0);
    setTimeline(null);
    setVideoUrl(null);
    setAuditEvents([]);
    setActiveAuditEventId(undefined);
  };

  // Find segment for audit event by timestamp
  const handleAuditEventClick = (event: AuditEvent) => {
    setActiveAuditEventId(event.id);

    if (!timeline) return;

    const eventTime = new Date(event.event_ts).getTime();

    for (const session of timeline.sessions) {
      let segStartMs = new Date(session.started_ts).getTime();

      for (let i = 0; i < session.segments.length; i++) {
        const seg = session.segments[i];
        const segEndMs = segStartMs + seg.duration_ms;

        if (eventTime >= segStartMs && eventTime < segEndMs) {
          setActiveSessionId(session.session_id);
          setActiveSegmentIndex(i);
          return;
        }
        segStartMs = segEndMs;
      }
    }

    // Fallback: find closest session/segment
    let closestSession = timeline.sessions[0];
    let closestSegIdx = 0;
    let minDiff = Infinity;

    for (const session of timeline.sessions) {
      let segStartMs = new Date(session.started_ts).getTime();
      for (let i = 0; i < session.segments.length; i++) {
        const diff = Math.abs(eventTime - segStartMs);
        if (diff < minDiff) {
          minDiff = diff;
          closestSession = session;
          closestSegIdx = i;
        }
        segStartMs += session.segments[i].duration_ms;
      }
    }

    setActiveSessionId(closestSession.session_id);
    setActiveSegmentIndex(closestSegIdx);
  };

  // Download active session
  const handleDownload = async () => {
    if (!activeSessionId) return;
    try {
      const { blob, filename } = await downloadRecording(activeSessionId);
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = filename;
      a.click();
      URL.revokeObjectURL(url);
    } catch (err) {
      console.error('Download failed', err);
    }
  };

  return (
    <div className="flex flex-col h-[calc(100vh-4rem)] overflow-hidden -m-6 sm:-m-6 lg:-m-8 -mb-6">
      {/* Top bar */}
      <div className="shrink-0 flex items-center gap-3 px-4 py-2 border-b border-gray-200 bg-white">
        <button
          onClick={() => navigate('/recordings')}
          className="p-1.5 text-gray-500 hover:text-gray-700 hover:bg-gray-100 rounded-lg"
        >
          <ArrowLeftIcon className="h-5 w-5" />
        </button>
        <div className="flex-1 min-w-0">
          <h1 className="text-lg font-semibold text-gray-900 truncate">
            {device?.hostname || 'Загрузка...'}
          </h1>
          <p className="text-xs text-gray-500">
            {device?.os_version} {device?.ip_address ? `| ${device.ip_address}` : ''}
          </p>
        </div>
        {activeSession && activeSession.status !== 'active' && (
          <button
            onClick={handleDownload}
            className="flex items-center gap-1.5 px-3 py-1.5 text-sm font-medium text-white bg-red-600 hover:bg-red-500 rounded-lg"
          >
            <ArrowDownTrayIcon className="h-4 w-4" />
            Скачать
          </button>
        )}
      </div>

      <div className="flex flex-1 min-h-0">
        {/* Left panel: Day list */}
        <div className="w-48 shrink-0 border-r border-gray-200 bg-gray-50 flex flex-col">
          <div className="shrink-0 px-3 py-2 border-b border-gray-200">
            <div className="flex items-center gap-2 text-sm font-medium text-gray-700">
              <CalendarDaysIcon className="h-4 w-4" />
              Дни записей
            </div>
            {daysData && (
              <p className="text-xs text-gray-500 mt-0.5">
                Часовой пояс: {daysData.timezone}
              </p>
            )}
          </div>

          <div className="flex-1 overflow-y-auto">
            {daysLoading ? (
              <div className="flex items-center justify-center py-8">
                <LoadingSpinner size="sm" />
              </div>
            ) : daysData && daysData.days.length > 0 ? (
              <div className="divide-y divide-gray-200">
                {daysData.days.map((day) => (
                  <button
                    key={day.date}
                    onClick={() => handleDaySelect(day.date)}
                    className={`w-full text-left px-3 py-2.5 transition-colors ${
                      selectedDate === day.date
                        ? 'bg-red-50 border-l-2 border-red-600'
                        : 'hover:bg-gray-100 border-l-2 border-transparent'
                    }`}
                  >
                    <div className="flex items-center justify-between">
                      <span
                        className={`text-sm font-medium ${
                          selectedDate === day.date ? 'text-red-700' : 'text-gray-900'
                        }`}
                      >
                        {formatDateRu(day.date)}
                      </span>
                      {day.live && (
                        <span className="inline-flex items-center gap-0.5 text-[10px] font-bold text-red-600">
                          <SignalIcon className="h-3 w-3" />
                          LIVE
                        </span>
                      )}
                    </div>
                    <div className="flex items-center gap-2 mt-0.5 text-xs text-gray-500">
                      <span>{day.segment_count} сегм.</span>
                      <span>{formatDuration(day.total_duration_ms)}</span>
                      <span>{formatBytes(day.total_bytes)}</span>
                    </div>
                  </button>
                ))}
              </div>
            ) : (
              <div className="text-center py-8 text-sm text-gray-500">
                Нет записей
              </div>
            )}
          </div>

          {/* Day pagination */}
          {daysData && daysData.total_pages > 1 && (
            <div className="shrink-0 flex items-center justify-center gap-2 px-3 py-2 border-t border-gray-200">
              <button
                onClick={() => setDaysPage(Math.max(0, daysPage - 1))}
                disabled={daysPage === 0}
                className="p-1 text-gray-500 hover:text-gray-700 disabled:opacity-30"
              >
                <ChevronLeftIcon className="h-4 w-4" />
              </button>
              <span className="text-xs text-gray-500">
                {daysPage + 1}/{daysData.total_pages}
              </span>
              <button
                onClick={() => setDaysPage(Math.min(daysData.total_pages - 1, daysPage + 1))}
                disabled={daysPage >= daysData.total_pages - 1}
                className="p-1 text-gray-500 hover:text-gray-700 disabled:opacity-30"
              >
                <ChevronRightIcon className="h-4 w-4" />
              </button>
            </div>
          )}
        </div>

        {/* Center panel: Video player + Timeline */}
        <div className="flex-1 flex flex-col min-w-0 bg-gray-900">
          {timelineLoading ? (
            <div className="flex-1 flex items-center justify-center">
              <LoadingSpinner size="lg" />
            </div>
          ) : !timeline || !selectedDate ? (
            <div className="flex-1 flex items-center justify-center text-gray-400">
              <div className="text-center">
                <CalendarDaysIcon className="mx-auto h-12 w-12 mb-2" />
                <p>Выберите день для просмотра</p>
              </div>
            </div>
          ) : (
            <>
              {/* Video player */}
              <div className="flex-1 flex items-center justify-center min-h-0 bg-black">
                {videoUrl ? (
                  <video
                    ref={videoRef}
                    className="max-w-full max-h-full object-contain"
                    controls
                    autoPlay
                    onEnded={handleVideoEnded}
                  >
                    <source src={videoUrl} type="video/mp4" />
                  </video>
                ) : (
                  <div className="text-gray-500 text-center">
                    <PlayIcon className="mx-auto h-12 w-12 mb-2" />
                    <p>Нет доступных сегментов</p>
                  </div>
                )}
              </div>

              {/* Segment navigation bar */}
              {activeSegments.length > 1 && (
                <div className="shrink-0 flex items-center gap-2 px-4 py-1.5 bg-gray-800">
                  <button
                    onClick={() => setActiveSegmentIndex(Math.max(0, activeSegmentIndex - 1))}
                    disabled={activeSegmentIndex === 0}
                    className="p-1 text-gray-400 hover:text-white disabled:opacity-30"
                  >
                    <ChevronLeftIcon className="h-4 w-4" />
                  </button>

                  <div className="flex-1 flex items-center gap-0.5 h-2.5">
                    {activeSegments.map((seg, idx) => (
                      <button
                        key={seg.id}
                        onClick={() => setActiveSegmentIndex(idx)}
                        className={`h-full flex-1 rounded-sm transition-colors ${
                          idx === activeSegmentIndex
                            ? 'bg-red-500'
                            : idx < activeSegmentIndex
                            ? 'bg-gray-500'
                            : 'bg-gray-700'
                        } hover:brightness-125`}
                        title={`Сегмент ${idx + 1}`}
                      />
                    ))}
                  </div>

                  <button
                    onClick={() =>
                      setActiveSegmentIndex(Math.min(activeSegments.length - 1, activeSegmentIndex + 1))
                    }
                    disabled={activeSegmentIndex >= activeSegments.length - 1}
                    className="p-1 text-gray-400 hover:text-white disabled:opacity-30"
                  >
                    <ChevronRightIcon className="h-4 w-4" />
                  </button>

                  <span className="text-xs text-gray-400 ml-2 shrink-0">
                    {activeSegmentIndex + 1}/{activeSegments.length}
                  </span>
                </div>
              )}

              {/* Day timeline */}
              <div className="shrink-0 px-4 py-2 bg-gray-800 border-t border-gray-700">
                <DayTimeline
                  sessions={timeline.sessions}
                  timezone={timeline.timezone}
                  date={timeline.date}
                  onSegmentClick={handleTimelineSegmentClick}
                  activeSessionId={activeSessionId || undefined}
                  activeSegmentIndex={activeSegmentIndex}
                />
              </div>

              {/* Session info bar */}
              {activeSession && (
                <div className="shrink-0 px-4 py-1.5 bg-gray-950 border-t border-gray-800 flex items-center gap-4 text-xs text-gray-400">
                  <span>
                    <span className="text-gray-500">Сессия:</span>{' '}
                    {activeSession.session_id.substring(0, 8)}
                  </span>
                  <span>
                    <span className="text-gray-500">Статус:</span>{' '}
                    <span
                      className={
                        activeSession.status === 'active'
                          ? 'text-green-400'
                          : activeSession.status === 'completed'
                          ? 'text-gray-300'
                          : 'text-yellow-400'
                      }
                    >
                      {activeSession.status}
                    </span>
                  </span>
                  <span>
                    <span className="text-gray-500">Сегменты:</span> {activeSession.segment_count}
                  </span>
                  <span>
                    <span className="text-gray-500">Длительность:</span>{' '}
                    {formatDuration(activeSession.total_duration_ms)}
                  </span>
                  <span>
                    <span className="text-gray-500">Размер:</span>{' '}
                    {formatBytes(activeSession.total_bytes)}
                  </span>
                  {activeSegment && (
                    <span className="ml-auto">
                      <span className="text-gray-500">Сегмент:</span>{' '}
                      #{activeSegment.sequence_num} ({formatBytes(activeSegment.size_bytes)})
                    </span>
                  )}
                </div>
              )}
            </>
          )}
        </div>

        {/* Right panel: Audit events */}
        {timeline && selectedDate && !timelineLoading && (
          <div className="w-80 shrink-0 border-l border-gray-700 bg-gray-800 flex flex-col min-h-0">
            {auditLoading ? (
              <div className="flex-1 flex items-center justify-center">
                <LoadingSpinner size="sm" />
              </div>
            ) : auditEvents.length > 0 ? (
              <AuditEventTimeline
                events={auditEvents}
                timezone={timeline.timezone}
                sessions={timeline.sessions}
                onEventClick={handleAuditEventClick}
                activeEventId={activeAuditEventId}
              />
            ) : (
              <div className="flex-1 flex items-center justify-center text-gray-500 text-sm">
                Нет событий аудита
              </div>
            )}
          </div>
        )}
      </div>
    </div>
  );
}
