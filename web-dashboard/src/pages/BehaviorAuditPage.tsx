import { useState, useEffect, useCallback, Fragment } from 'react';
import { getInputEvents, type InputEventItem, type InputEventsQueryParams } from '../api/behavior-audit';
import { ChevronDownIcon, ChevronRightIcon, PlayIcon } from '@heroicons/react/24/outline';
import { useNavigate } from 'react-router-dom';

const EVENT_TYPE_LABELS: Record<string, { icon: string; label: string }> = {
  mouse_click: { icon: '\uD83D\uDDB1\uFE0F', label: 'Клик' },
  keyboard_metric: { icon: '\u2328\uFE0F', label: 'Ввод' },
  scroll: { icon: '\u2195\uFE0F', label: 'Скролл' },
  clipboard: { icon: '\uD83D\uDCCB', label: 'Буфер' },
};

const EVENT_TYPE_OPTIONS = [
  { value: '', label: 'Все типы' },
  { value: 'mouse_click', label: 'Клик мыши' },
  { value: 'keyboard_metric', label: 'Ввод текста' },
  { value: 'scroll', label: 'Скролл' },
  { value: 'clipboard', label: 'Буфер обмена' },
];

function formatTime(iso: string): string {
  const d = new Date(iso);
  return d.toLocaleTimeString('ru-RU', { hour: '2-digit', minute: '2-digit', second: '2-digit' });
}

function formatEventDetails(event: InputEventItem): string {
  switch (event.event_type) {
    case 'mouse_click': {
      const elem = event.ui_element_type && event.ui_element_name
        ? `${event.ui_element_type} "${event.ui_element_name}"`
        : event.ui_element_type || '';
      return `${event.process_name || ''}${elem ? ': ' + elem : ''}`;
    }
    case 'keyboard_metric': {
      const burst = event.has_typing_burst ? ', burst' : '';
      return `${event.process_name || ''}: ${event.keystroke_count} keys${burst}`;
    }
    case 'scroll':
      return `${event.process_name || ''}: ${event.scroll_direction}, ${event.scroll_event_count}x`;
    case 'clipboard':
      return `${event.clipboard_action}: ${event.clipboard_content_type || ''}, ${event.clipboard_content_length || 0}b`;
    default:
      return event.process_name || '';
  }
}

function todayISO(): string {
  return new Date().toISOString().split('T')[0];
}

export default function BehaviorAuditPage() {
  const navigate = useNavigate();

  // Filters
  const [dateFrom, setDateFrom] = useState(todayISO());
  const [dateTo, setDateTo] = useState(todayISO());
  const [eventType, setEventType] = useState('');
  const [username, setUsername] = useState('');
  const [deviceFilter, setDeviceFilter] = useState('');
  const [search, setSearch] = useState('');

  // Data
  const [events, setEvents] = useState<InputEventItem[]>([]);
  const [totalElements, setTotalElements] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [page, setPage] = useState(0);
  const [size] = useState(50);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Expanded row
  const [expandedId, setExpandedId] = useState<string | null>(null);

  const fetchEvents = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const params: InputEventsQueryParams = {
        from: new Date(dateFrom + 'T00:00:00').toISOString(),
        to: new Date(dateTo + 'T23:59:59').toISOString(),
        page,
        size,
        sort_by: 'event_ts',
        sort_dir: 'desc',
      };
      if (eventType) params.event_type = eventType;
      if (username.trim()) params.username = username.trim();
      if (deviceFilter.trim()) params.device_id = deviceFilter.trim();
      if (search.trim()) params.search = search.trim();

      const data = await getInputEvents(params);
      setEvents(data.content || []);
      setTotalElements(data.total_elements);
      setTotalPages(data.total_pages);
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : 'Failed to load events';
      setError(msg);
    } finally {
      setLoading(false);
    }
  }, [dateFrom, dateTo, eventType, username, deviceFilter, search, page, size]);

  useEffect(() => {
    fetchEvents();
  }, [fetchEvents]);

  const handleReset = () => {
    setDateFrom(todayISO());
    setDateTo(todayISO());
    setEventType('');
    setUsername('');
    setDeviceFilter('');
    setSearch('');
    setPage(0);
  };

  const handleGoToRecording = (event: InputEventItem) => {
    if (event.device_id) {
      const params = new URLSearchParams();
      if (event.segment_id) params.set('segment', event.segment_id);
      if (event.segment_offset_ms != null) params.set('offset', String(event.segment_offset_ms));
      navigate(`/archive/devices/${event.device_id}?${params.toString()}`);
    }
  };

  return (
    <div className="px-4 sm:px-6 lg:px-8 py-6">
      <h1 className="text-2xl font-bold text-gray-900 mb-6">Аудит поведения</h1>

      {/* Filters */}
      <div className="bg-white rounded-lg shadow p-4 mb-6">
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">Дата с</label>
            <input
              type="date"
              value={dateFrom}
              onChange={(e) => { setDateFrom(e.target.value); setPage(0); }}
              className="block w-full rounded-md border-gray-300 shadow-sm focus:border-red-500 focus:ring-red-500 sm:text-sm"
            />
          </div>
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">Дата по</label>
            <input
              type="date"
              value={dateTo}
              onChange={(e) => { setDateTo(e.target.value); setPage(0); }}
              className="block w-full rounded-md border-gray-300 shadow-sm focus:border-red-500 focus:ring-red-500 sm:text-sm"
            />
          </div>
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">Тип действия</label>
            <select
              value={eventType}
              onChange={(e) => { setEventType(e.target.value); setPage(0); }}
              className="block w-full rounded-md border-gray-300 shadow-sm focus:border-red-500 focus:ring-red-500 sm:text-sm"
            >
              {EVENT_TYPE_OPTIONS.map((opt) => (
                <option key={opt.value} value={opt.value}>{opt.label}</option>
              ))}
            </select>
          </div>
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">Сотрудник</label>
            <input
              type="text"
              placeholder="username"
              value={username}
              onChange={(e) => { setUsername(e.target.value); setPage(0); }}
              className="block w-full rounded-md border-gray-300 shadow-sm focus:border-red-500 focus:ring-red-500 sm:text-sm"
            />
          </div>
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">Устройство (ID)</label>
            <input
              type="text"
              placeholder="device UUID"
              value={deviceFilter}
              onChange={(e) => { setDeviceFilter(e.target.value); setPage(0); }}
              className="block w-full rounded-md border-gray-300 shadow-sm focus:border-red-500 focus:ring-red-500 sm:text-sm"
            />
          </div>
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">Поиск</label>
            <input
              type="text"
              placeholder="процесс, окно, элемент..."
              value={search}
              onChange={(e) => { setSearch(e.target.value); setPage(0); }}
              className="block w-full rounded-md border-gray-300 shadow-sm focus:border-red-500 focus:ring-red-500 sm:text-sm"
            />
          </div>
          <div className="flex items-end">
            <button
              onClick={handleReset}
              className="rounded-md bg-gray-100 px-4 py-2 text-sm font-medium text-gray-700 hover:bg-gray-200"
            >
              Сбросить
            </button>
          </div>
        </div>
      </div>

      {/* Summary */}
      <div className="flex items-center justify-between mb-4">
        <p className="text-sm text-gray-600">
          {loading ? 'Загрузка...' : `Найдено: ${totalElements} событий`}
        </p>
        <p className="text-sm text-gray-500">
          Стр. {page + 1} / {Math.max(totalPages, 1)}
        </p>
      </div>

      {error && (
        <div className="mb-4 rounded-md bg-red-50 p-3 text-sm text-red-700">{error}</div>
      )}

      {/* Table */}
      <div className="bg-white shadow rounded-lg overflow-hidden">
        <table className="min-w-full divide-y divide-gray-200">
          <thead className="bg-gray-50">
            <tr>
              <th className="w-8 px-3 py-3"></th>
              <th className="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase">Время</th>
              <th className="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase">Сотрудник</th>
              <th className="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase">Устройство</th>
              <th className="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase">Тип</th>
              <th className="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase">Детали</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-200">
            {events.length === 0 && !loading && (
              <tr>
                <td colSpan={6} className="px-4 py-8 text-center text-sm text-gray-500">
                  Нет событий за выбранный период
                </td>
              </tr>
            )}
            {events.map((event) => {
              const isExpanded = expandedId === event.id;
              const typeInfo = EVENT_TYPE_LABELS[event.event_type] || { icon: '?', label: event.event_type };
              return (
                <Fragment key={event.id}>
                  <tr
                    className="hover:bg-gray-50 cursor-pointer"
                    onClick={() => setExpandedId(isExpanded ? null : event.id)}
                  >
                    <td className="px-3 py-3 text-gray-400">
                      {isExpanded ? (
                        <ChevronDownIcon className="h-4 w-4" />
                      ) : (
                        <ChevronRightIcon className="h-4 w-4" />
                      )}
                    </td>
                    <td className="px-4 py-3 text-sm text-gray-900 whitespace-nowrap" title={event.event_ts}>
                      {formatTime(event.event_ts)}
                    </td>
                    <td className="px-4 py-3 text-sm text-gray-900 max-w-[160px] truncate">
                      {event.username}
                    </td>
                    <td className="px-4 py-3 text-sm text-gray-600 max-w-[120px] truncate">
                      {event.device_hostname}
                    </td>
                    <td className="px-4 py-3 text-sm whitespace-nowrap">
                      <span className="mr-1">{typeInfo.icon}</span>
                      {typeInfo.label}
                    </td>
                    <td className="px-4 py-3 text-sm text-gray-600 max-w-[300px] truncate">
                      {formatEventDetails(event)}
                    </td>
                  </tr>
                  {isExpanded && (
                    <tr className="bg-gray-50">
                      <td colSpan={6} className="px-6 py-4">
                        <div className="grid grid-cols-2 gap-4 text-sm">
                          <div>
                            <span className="font-medium text-gray-700">Окно:</span>{' '}
                            <span className="text-gray-600">{event.window_title || '-'}</span>
                          </div>
                          <div>
                            <span className="font-medium text-gray-700">Процесс:</span>{' '}
                            <span className="text-gray-600">{event.process_name || '-'}</span>
                          </div>
                          {event.segment_id && (
                            <div>
                              <span className="font-medium text-gray-700">Segment:</span>{' '}
                              <span className="text-gray-600 font-mono text-xs">{event.segment_id}</span>
                              {event.segment_offset_ms != null && (
                                <span className="ml-2 text-gray-500">
                                  offset: {event.segment_offset_ms}ms
                                </span>
                              )}
                            </div>
                          )}
                          {event.click_x != null && (
                            <div>
                              <span className="font-medium text-gray-700">Координаты:</span>{' '}
                              <span className="text-gray-600">({event.click_x}, {event.click_y})</span>
                              <span className="ml-2 text-gray-500">{event.click_type} {event.click_button}</span>
                            </div>
                          )}
                          {event.ui_element_type && (
                            <div>
                              <span className="font-medium text-gray-700">UI элемент:</span>{' '}
                              <span className="text-gray-600">
                                {event.ui_element_type}
                                {event.ui_element_name ? ` "${event.ui_element_name}"` : ''}
                              </span>
                            </div>
                          )}
                        </div>
                        {event.device_id && (
                          <button
                            onClick={(e) => { e.stopPropagation(); handleGoToRecording(event); }}
                            className="mt-3 inline-flex items-center gap-1.5 rounded-md bg-red-600 px-3 py-1.5 text-sm font-medium text-white hover:bg-red-700"
                          >
                            <PlayIcon className="h-4 w-4" />
                            Перейти к записи
                          </button>
                        )}
                      </td>
                    </tr>
                  )}
                </Fragment>
              );
            })}
          </tbody>
        </table>
      </div>

      {/* Pagination */}
      {totalPages > 1 && (
        <div className="mt-4 flex items-center justify-between">
          <button
            disabled={page === 0}
            onClick={() => setPage(page - 1)}
            className="rounded-md bg-white px-3 py-2 text-sm font-medium text-gray-700 shadow-sm ring-1 ring-gray-300 hover:bg-gray-50 disabled:opacity-50"
          >
            Назад
          </button>
          <span className="text-sm text-gray-600">
            {page + 1} / {totalPages}
          </span>
          <button
            disabled={page >= totalPages - 1}
            onClick={() => setPage(page + 1)}
            className="rounded-md bg-white px-3 py-2 text-sm font-medium text-gray-700 shadow-sm ring-1 ring-gray-300 hover:bg-gray-50 disabled:opacity-50"
          >
            Вперёд
          </button>
        </div>
      )}
    </div>
  );
}

