import { useState, useEffect, useCallback } from 'react';
import {
  MagnifyingGlassIcon,
  PlayIcon,
  ArrowDownTrayIcon,
  ChevronRightIcon,
  ChevronDownIcon,
  FunnelIcon,
} from '@heroicons/react/24/outline';
import {
  getRecordings,
  getRecordingSegments,
  downloadRecording,
  downloadSegment,
} from '../api/ingest';
import type { Recording, Segment } from '../api/ingest';
import PlayerModal from '../components/player/PlayerModal';

interface Filters {
  search: string;
  device: string;
  status: string;
  from: string;
  to: string;
  minSegments: string;
  maxSegments: string;
  minMb: string;
  maxMb: string;
}

const defaultFilters: Filters = {
  search: '',
  device: '',
  status: '',
  from: '',
  to: '',
  minSegments: '',
  maxSegments: '',
  minMb: '',
  maxMb: '',
};

interface PlayerState {
  open: boolean;
  sessionId: string;
  segments: Segment[];
  initialSegmentIndex: number;
}

export default function SearchPage() {
  const [sessions, setSessions] = useState<Recording[]>([]);
  const [totalElements, setTotalElements] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [page, setPage] = useState(0);
  const [loading, setLoading] = useState(false);

  const [filters, setFilters] = useState<Filters>(defaultFilters);
  const [showFilters, setShowFilters] = useState(false);

  // Expanded sessions (accordion)
  const [expandedIds, setExpandedIds] = useState<Set<string>>(new Set());
  const [segmentsCache, setSegmentsCache] = useState<Map<string, Segment[]>>(new Map());
  const [loadingSegments, setLoadingSegments] = useState<Set<string>>(new Set());

  // Player modal
  const [player, setPlayer] = useState<PlayerState>({
    open: false,
    sessionId: '',
    segments: [],
    initialSegmentIndex: 0,
  });

  // Downloading state
  const [downloading, setDownloading] = useState<string | null>(null);

  const PAGE_SIZE = 20;

  const fetchSessions = useCallback(
    async (p = 0) => {
      setLoading(true);
      try {
        const params: Record<string, unknown> = { page: p, size: PAGE_SIZE };
        if (filters.search) params.search = filters.search;
        if (filters.device) params.device_id = filters.device;
        if (filters.status) params.status = filters.status;
        if (filters.from) params.from = filters.from;
        if (filters.to) params.to = filters.to;
        if (filters.minSegments) params.min_segments = Number(filters.minSegments);
        if (filters.maxSegments) params.max_segments = Number(filters.maxSegments);
        if (filters.minMb) params.min_bytes = Math.round(Number(filters.minMb) * 1024 * 1024);
        if (filters.maxMb) params.max_bytes = Math.round(Number(filters.maxMb) * 1024 * 1024);

        const data = await getRecordings(params as Parameters<typeof getRecordings>[0]);
        setSessions(data.content);
        setTotalElements(data.total_elements);
        setTotalPages(data.total_pages);
        setPage(p);
      } catch (err) {
        console.error('Failed to fetch sessions:', err);
      } finally {
        setLoading(false);
      }
    },
    [filters],
  );

  useEffect(() => {
    fetchSessions(0);
  }, []);

  const handleSearch = () => fetchSessions(0);

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter') handleSearch();
  };

  const handleResetFilters = () => {
    setFilters(defaultFilters);
  };

  // Toggle expand row
  const toggleExpand = async (sessionId: string) => {
    const next = new Set(expandedIds);
    if (next.has(sessionId)) {
      next.delete(sessionId);
    } else {
      next.add(sessionId);
      // Fetch segments if not cached
      if (!segmentsCache.has(sessionId)) {
        setLoadingSegments((prev) => new Set(prev).add(sessionId));
        try {
          const data = await getRecordingSegments(sessionId);
          setSegmentsCache((prev) => new Map(prev).set(sessionId, data.segments || []));
        } catch {
          setSegmentsCache((prev) => new Map(prev).set(sessionId, []));
        } finally {
          setLoadingSegments((prev) => {
            const s = new Set(prev);
            s.delete(sessionId);
            return s;
          });
        }
      }
    }
    setExpandedIds(next);
  };

  // Open player modal
  const openPlayer = (sessionId: string, segments: Segment[], segmentIndex = 0) => {
    setPlayer({ open: true, sessionId, segments, initialSegmentIndex: segmentIndex });
  };

  const closePlayer = () => {
    setPlayer((prev) => ({ ...prev, open: false }));
  };

  // Play session from row action
  const handlePlaySession = async (e: React.MouseEvent, session: Recording) => {
    e.stopPropagation();
    if (session.segment_count === 0) return;
    let segs = segmentsCache.get(session.id);
    if (!segs) {
      try {
        const data = await getRecordingSegments(session.id);
        segs = data.segments || [];
        setSegmentsCache((prev) => new Map(prev).set(session.id, segs!));
      } catch {
        segs = [];
      }
    }
    if (segs.length === 0) return;
    openPlayer(session.id, segs);
  };

  // Download session ZIP
  const handleDownloadSession = async (e: React.MouseEvent, sessionId: string) => {
    e.stopPropagation();
    setDownloading(sessionId);
    try {
      const { blob, filename } = await downloadRecording(sessionId);
      triggerDownload(blob, filename);
    } catch (err) {
      console.error('Download failed:', err);
    } finally {
      setDownloading(null);
    }
  };

  // Download single segment
  const handleDownloadSegment = async (e: React.MouseEvent, sessionId: string, seg: Segment) => {
    e.stopPropagation();
    setDownloading(seg.id);
    try {
      const { blob, filename } = await downloadSegment(sessionId, seg.id);
      triggerDownload(blob, filename);
    } catch (err) {
      console.error('Download segment failed:', err);
    } finally {
      setDownloading(null);
    }
  };

  const triggerDownload = (blob: Blob, filename: string) => {
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = filename;
    a.click();
    URL.revokeObjectURL(url);
  };

  // Formatters
  const formatBytes = (bytes: number) => {
    if (bytes < 1024) return `${bytes} B`;
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
    return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
  };

  const formatDuration = (ms: number) => {
    const totalSec = Math.round(ms / 1000);
    const h = Math.floor(totalSec / 3600);
    const m = Math.floor((totalSec % 3600) / 60);
    const s = totalSec % 60;
    if (h > 0) return `${h}:${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`;
    return `${m}:${String(s).padStart(2, '0')}`;
  };

  const formatDate = (iso: string | null) => {
    if (!iso) return '-';
    return new Date(iso).toLocaleString('ru-RU', {
      day: '2-digit',
      month: '2-digit',
      year: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
    });
  };

  const statusLabel = (status: string) => {
    switch (status) {
      case 'active':
        return <span className="text-green-600 font-medium">Запись</span>;
      case 'completed':
        return <span className="text-gray-500">Завершена</span>;
      case 'interrupted':
        return <span className="text-yellow-600">Прервана</span>;
      case 'failed':
        return <span className="text-red-600">Ошибка</span>;
      default:
        return <span className="text-gray-500">{status}</span>;
    }
  };

  const updateFilter = (key: keyof Filters, value: string) => {
    setFilters((prev) => ({ ...prev, [key]: value }));
  };

  return (
    <div className="p-4 sm:p-6">
      <h1 className="text-2xl font-bold text-gray-900 mb-6">Поиск записей</h1>

      {/* Search bar */}
      <div className="flex flex-col sm:flex-row gap-3 mb-4">
        <div className="relative flex-1">
          <MagnifyingGlassIcon className="absolute left-3 top-1/2 -translate-y-1/2 w-5 h-5 text-gray-400" />
          <input
            type="text"
            value={filters.search}
            onChange={(e) => updateFilter('search', e.target.value)}
            onKeyDown={handleKeyDown}
            placeholder="Поиск по устройству, сотруднику..."
            className="w-full pl-10 pr-4 py-2 bg-white border border-gray-300 rounded-lg text-gray-900 placeholder-gray-400 focus:outline-none focus:ring-2 focus:ring-red-500"
          />
        </div>
        <button
          onClick={() => setShowFilters(!showFilters)}
          className={`flex items-center gap-2 px-4 py-2 rounded-lg border transition-colors ${
            showFilters
              ? 'bg-red-50 border-red-500 text-red-600'
              : 'bg-white border-gray-300 text-gray-600 hover:bg-gray-50'
          }`}
        >
          <FunnelIcon className="w-4 h-4" />
          Фильтры
        </button>
        <button
          onClick={handleSearch}
          disabled={loading}
          className="px-6 py-2 bg-red-600 hover:bg-red-700 text-white rounded-lg disabled:opacity-50 transition-colors"
        >
          {loading ? 'Поиск...' : 'Найти'}
        </button>
      </div>

      {/* Filter bar */}
      {showFilters && (
        <div className="bg-white rounded-lg border border-gray-200 p-4 mb-4 grid grid-cols-2 md:grid-cols-4 gap-3">
          <div>
            <label className="block text-xs text-gray-500 mb-1">Устройство</label>
            <input
              type="text"
              value={filters.device}
              onChange={(e) => updateFilter('device', e.target.value)}
              onKeyDown={handleKeyDown}
              placeholder="ID устройства"
              className="w-full px-3 py-1.5 bg-gray-50 border border-gray-300 rounded text-sm text-gray-900 placeholder-gray-400 focus:outline-none focus:ring-1 focus:ring-red-500"
            />
          </div>
          <div>
            <label className="block text-xs text-gray-500 mb-1">Статус</label>
            <select
              value={filters.status}
              onChange={(e) => updateFilter('status', e.target.value)}
              className="w-full px-3 py-1.5 bg-gray-50 border border-gray-300 rounded text-sm text-gray-900 focus:outline-none focus:ring-1 focus:ring-red-500"
            >
              <option value="">Все</option>
              <option value="active">Запись</option>
              <option value="completed">Завершена</option>
              <option value="interrupted">Прервана</option>
              <option value="failed">Ошибка</option>
            </select>
          </div>
          <div>
            <label className="block text-xs text-gray-500 mb-1">Дата от</label>
            <input
              type="date"
              value={filters.from}
              onChange={(e) => updateFilter('from', e.target.value)}
              className="w-full px-3 py-1.5 bg-gray-50 border border-gray-300 rounded text-sm text-gray-900 focus:outline-none focus:ring-1 focus:ring-red-500"
            />
          </div>
          <div>
            <label className="block text-xs text-gray-500 mb-1">Дата до</label>
            <input
              type="date"
              value={filters.to}
              onChange={(e) => updateFilter('to', e.target.value)}
              className="w-full px-3 py-1.5 bg-gray-50 border border-gray-300 rounded text-sm text-gray-900 focus:outline-none focus:ring-1 focus:ring-red-500"
            />
          </div>
          <div>
            <label className="block text-xs text-gray-500 mb-1">Сегментов от</label>
            <input
              type="number"
              min="0"
              value={filters.minSegments}
              onChange={(e) => updateFilter('minSegments', e.target.value)}
              onKeyDown={handleKeyDown}
              placeholder="мин"
              className="w-full px-3 py-1.5 bg-gray-50 border border-gray-300 rounded text-sm text-gray-900 placeholder-gray-400 focus:outline-none focus:ring-1 focus:ring-red-500"
            />
          </div>
          <div>
            <label className="block text-xs text-gray-500 mb-1">Сегментов до</label>
            <input
              type="number"
              min="0"
              value={filters.maxSegments}
              onChange={(e) => updateFilter('maxSegments', e.target.value)}
              onKeyDown={handleKeyDown}
              placeholder="макс"
              className="w-full px-3 py-1.5 bg-gray-50 border border-gray-300 rounded text-sm text-gray-900 placeholder-gray-400 focus:outline-none focus:ring-1 focus:ring-red-500"
            />
          </div>
          <div>
            <label className="block text-xs text-gray-500 mb-1">Размер от (МБ)</label>
            <input
              type="number"
              min="0"
              step="0.1"
              value={filters.minMb}
              onChange={(e) => updateFilter('minMb', e.target.value)}
              onKeyDown={handleKeyDown}
              placeholder="мин"
              className="w-full px-3 py-1.5 bg-gray-50 border border-gray-300 rounded text-sm text-gray-900 placeholder-gray-400 focus:outline-none focus:ring-1 focus:ring-red-500"
            />
          </div>
          <div>
            <label className="block text-xs text-gray-500 mb-1">Размер до (МБ)</label>
            <input
              type="number"
              min="0"
              step="0.1"
              value={filters.maxMb}
              onChange={(e) => updateFilter('maxMb', e.target.value)}
              onKeyDown={handleKeyDown}
              placeholder="макс"
              className="w-full px-3 py-1.5 bg-gray-50 border border-gray-300 rounded text-sm text-gray-900 placeholder-gray-400 focus:outline-none focus:ring-1 focus:ring-red-500"
            />
          </div>
          <div className="col-span-2 md:col-span-4 flex justify-end">
            <button
              onClick={handleResetFilters}
              className="text-sm text-gray-500 hover:text-gray-800 transition-colors"
            >
              Сбросить фильтры
            </button>
          </div>
        </div>
      )}

      {/* Results count */}
      <div className="text-sm text-gray-500 mb-4">
        Найдено: {totalElements} сессий
      </div>

      {/* Sessions table */}
      <div className="bg-white rounded-lg border border-gray-200 overflow-hidden">
        <table className="w-full text-sm text-gray-900">
          <thead className="bg-gray-50 text-gray-500 uppercase text-xs border-b border-gray-200">
            <tr>
              <th className="w-8 px-2 py-3" />
              <th className="px-4 py-3 text-left">Сотрудник</th>
              <th className="px-4 py-3 text-left">Устройство</th>
              <th className="hidden lg:table-cell px-4 py-3 text-left">Сессия</th>
              <th className="px-4 py-3 text-left">Начало</th>
              <th className="hidden md:table-cell px-4 py-3 text-left">Конец</th>
              <th className="px-4 py-3 text-right">Сегменты</th>
              <th className="hidden sm:table-cell px-4 py-3 text-right">Размер</th>
              <th className="px-4 py-3 text-center">Действия</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-100">
            {sessions.map((s) => {
              const isExpanded = expandedIds.has(s.id);
              const segs = segmentsCache.get(s.id) || [];
              const isLoadingSegs = loadingSegments.has(s.id);
              return (
                <SessionRow
                  key={s.id}
                  session={s}
                  isExpanded={isExpanded}
                  segments={segs}
                  isLoadingSegments={isLoadingSegs}
                  downloading={downloading}
                  onToggle={() => toggleExpand(s.id)}
                  onPlay={(e) => handlePlaySession(e, s)}
                  onDownload={(e) => handleDownloadSession(e, s.id)}
                  onPlaySegment={(_seg, idx) => openPlayer(s.id, segs, idx)}
                  onDownloadSegment={(e, seg) => handleDownloadSegment(e, s.id, seg)}
                  formatBytes={formatBytes}
                  formatDuration={formatDuration}
                  formatDate={formatDate}
                  statusLabel={statusLabel}
                />
              );
            })}
            {sessions.length === 0 && !loading && (
              <tr>
                <td colSpan={9} className="px-4 py-8 text-center text-gray-500">
                  Нет результатов
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>

      {/* Pagination */}
      {totalPages > 1 && (
        <div className="flex justify-center items-center gap-2 mt-4">
          <button
            onClick={() => fetchSessions(page - 1)}
            disabled={page === 0}
            className="px-3 py-1 bg-white border border-gray-300 rounded text-gray-700 disabled:opacity-30 hover:bg-gray-50 transition-colors"
          >
            Назад
          </button>
          <span className="px-3 py-1 text-gray-500 text-sm">
            {page + 1} / {totalPages}
          </span>
          <button
            onClick={() => fetchSessions(page + 1)}
            disabled={page + 1 >= totalPages}
            className="px-3 py-1 bg-white border border-gray-300 rounded text-gray-700 disabled:opacity-30 hover:bg-gray-50 transition-colors"
          >
            Вперёд
          </button>
        </div>
      )}

      {/* Player Modal */}
      {player.open && (
        <PlayerModal
          sessionId={player.sessionId}
          segments={player.segments}
          initialSegmentIndex={player.initialSegmentIndex}
          onClose={closePlayer}
        />
      )}
    </div>
  );
}

/* ---- Session Row (with accordion segments) ---- */

interface SessionRowProps {
  session: Recording;
  isExpanded: boolean;
  segments: Segment[];
  isLoadingSegments: boolean;
  downloading: string | null;
  onToggle: () => void;
  onPlay: (e: React.MouseEvent) => void;
  onDownload: (e: React.MouseEvent) => void;
  onPlaySegment: (seg: Segment, index: number) => void;
  onDownloadSegment: (e: React.MouseEvent, seg: Segment) => void;
  formatBytes: (n: number) => string;
  formatDuration: (ms: number) => string;
  formatDate: (iso: string | null) => string;
  statusLabel: (status: string) => JSX.Element;
}

function SessionRow({
  session,
  isExpanded,
  segments,
  isLoadingSegments,
  downloading,
  onToggle,
  onPlay,
  onDownload,
  onPlaySegment,
  onDownloadSegment,
  formatBytes,
  formatDuration,
  formatDate,
  statusLabel,
}: SessionRowProps) {
  return (
    <>
      {/* Main row */}
      <tr
        onClick={onToggle}
        className="hover:bg-gray-50 cursor-pointer"
      >
        <td className="px-2 py-3 text-center">
          {isExpanded ? (
            <ChevronDownIcon className="w-4 h-4 text-gray-400 inline" />
          ) : (
            <ChevronRightIcon className="w-4 h-4 text-gray-400 inline" />
          )}
        </td>
        <td className="px-4 py-3 text-gray-900">
          {session.employee_name || <span className="text-gray-400">-</span>}
        </td>
        <td className="px-4 py-3">
          <div className="font-mono text-xs text-gray-700">{session.device_hostname || session.device_id.slice(0, 12)}</div>
          {session.device_deleted && (
            <span className="text-[10px] text-red-500">удалено</span>
          )}
        </td>
        <td className="hidden lg:table-cell px-4 py-3">
          <div className="font-mono text-xs text-gray-500 break-all">{session.id}</div>
          <div className="text-[10px] mt-0.5">{statusLabel(session.status)}</div>
        </td>
        <td className="px-4 py-3 text-xs text-gray-600">{formatDate(session.started_ts)}</td>
        <td className="hidden md:table-cell px-4 py-3 text-xs text-gray-600">
          {session.ended_ts ? formatDate(session.ended_ts) : <span className="text-green-600 font-medium">В процессе</span>}
        </td>
        <td className="px-4 py-3 text-right text-gray-700">{session.segment_count}</td>
        <td className="hidden sm:table-cell px-4 py-3 text-right text-gray-700">{formatBytes(session.total_bytes)}</td>
        <td className="px-4 py-3">
          <div className="flex items-center justify-center gap-1">
            <button
              onClick={onPlay}
              disabled={session.segment_count === 0}
              className="p-1.5 rounded hover:bg-gray-200 text-gray-500 hover:text-red-600 transition-colors disabled:opacity-30"
              title={session.segment_count === 0 ? 'Нет сегментов' : 'Воспроизвести'}
            >
              <PlayIcon className="w-4 h-4" />
            </button>
            <button
              onClick={onDownload}
              disabled={downloading === session.id || session.status === 'active'}
              className="p-1.5 rounded hover:bg-gray-200 text-gray-500 hover:text-gray-800 transition-colors disabled:opacity-30"
              title={session.status === 'active' ? 'Запись в процессе' : 'Скачать (ZIP)'}
            >
              <ArrowDownTrayIcon className="w-4 h-4" />
            </button>
          </div>
        </td>
      </tr>

      {/* Expanded: segments table */}
      {isExpanded && (
        <tr>
          <td colSpan={9} className="bg-gray-50 px-6 py-3">
            {isLoadingSegments ? (
              <div className="text-gray-500 text-sm py-4 text-center">Загрузка сегментов...</div>
            ) : segments.length === 0 ? (
              <div className="text-gray-400 text-sm py-4 text-center">Нет сегментов</div>
            ) : (
              <table className="w-full text-xs text-gray-700">
                <thead className="text-gray-500 uppercase">
                  <tr>
                    <th className="px-3 py-2 text-left">#</th>
                    <th className="px-3 py-2 text-left">Статус</th>
                    <th className="px-3 py-2 text-right">Длительность</th>
                    <th className="px-3 py-2 text-right">Размер</th>
                    <th className="px-3 py-2 text-center">Действия</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-gray-200">
                  {segments.map((seg, idx) => (
                    <tr key={seg.id} className="hover:bg-gray-100">
                      <td className="px-3 py-2">{seg.sequence_num}</td>
                      <td className="px-3 py-2">
                        <span
                          className={
                            seg.status === 'confirmed'
                              ? 'text-green-600'
                              : seg.status === 'failed'
                                ? 'text-red-600'
                                : 'text-gray-500'
                          }
                        >
                          {seg.status === 'confirmed' ? 'Готов' : seg.status === 'uploaded' ? 'Загружен' : seg.status === 'failed' ? 'Ошибка' : seg.status}
                        </span>
                      </td>
                      <td className="px-3 py-2 text-right">{formatDuration(seg.duration_ms)}</td>
                      <td className="px-3 py-2 text-right">{formatBytes(seg.size_bytes)}</td>
                      <td className="px-3 py-2">
                        <div className="flex items-center justify-center gap-1">
                          <button
                            onClick={() => onPlaySegment(seg, idx)}
                            className="p-1 rounded hover:bg-gray-200 text-gray-500 hover:text-red-600 transition-colors"
                            title="Воспроизвести с этого сегмента"
                          >
                            <PlayIcon className="w-3.5 h-3.5" />
                          </button>
                          <button
                            onClick={(e) => onDownloadSegment(e, seg)}
                            disabled={downloading === seg.id || seg.status !== 'confirmed'}
                            className="p-1 rounded hover:bg-gray-200 text-gray-500 hover:text-gray-800 transition-colors disabled:opacity-30"
                            title="Скачать сегмент"
                          >
                            <ArrowDownTrayIcon className="w-3.5 h-3.5" />
                          </button>
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </td>
        </tr>
      )}
    </>
  );
}
