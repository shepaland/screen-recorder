import { useState, useEffect, useCallback } from 'react';
import { FilmIcon, XMarkIcon, ArrowPathIcon, ArrowDownTrayIcon, TrashIcon } from '@heroicons/react/24/outline';
import { getRecordings, getRecordingSegments, deleteRecording, downloadRecording, type Recording, type Segment } from '../api/ingest';
import ConfirmDialog from '../components/ConfirmDialog';
import PermissionGate from '../components/PermissionGate';
import { useToast } from '../contexts/ToastContext';

function formatDuration(ms: number): string {
  if (!ms) return '--';
  const totalSeconds = Math.floor(ms / 1000);
  const h = Math.floor(totalSeconds / 3600);
  const m = Math.floor((totalSeconds % 3600) / 60);
  const s = totalSeconds % 60;
  if (h > 0) return `${h}ч ${m}м ${s}с`;
  if (m > 0) return `${m}м ${s}с`;
  return `${s}с`;
}

function formatBytes(bytes: number): string {
  if (!bytes) return '--';
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(0)} КБ`;
  if (bytes < 1024 * 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1)} МБ`;
  return `${(bytes / 1024 / 1024 / 1024).toFixed(2)} ГБ`;
}

function formatTime(iso: string | null): string {
  if (!iso) return '--';
  return new Date(iso).toLocaleString('ru-RU', {
    day: '2-digit', month: '2-digit', year: 'numeric',
    hour: '2-digit', minute: '2-digit',
  });
}

function StatusBadge({ status }: { status: string }) {
  const styles: Record<string, string> = {
    completed: 'bg-green-50 text-green-700 ring-green-600/20',
    active: 'bg-blue-50 text-blue-700 ring-blue-600/20',
    failed: 'bg-red-50 text-red-700 ring-red-600/20',
    interrupted: 'bg-yellow-50 text-yellow-700 ring-yellow-600/20',
  };
  const labels: Record<string, string> = {
    completed: 'Завершена',
    active: 'Идёт запись',
    failed: 'Ошибка',
    interrupted: 'Прервана',
  };
  return (
    <span className={`inline-flex items-center rounded-md px-2 py-1 text-xs font-medium ring-1 ring-inset ${styles[status] || 'bg-gray-50 text-gray-700 ring-gray-600/20'}`}>
      {labels[status] || status}
    </span>
  );
}

export default function RecordingsPage() {
  const { addToast } = useToast();

  const [recordings, setRecordings] = useState<Recording[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [selectedRecording, setSelectedRecording] = useState<Recording | null>(null);
  const [segments, setSegments] = useState<Segment[]>([]);
  const [currentSegmentIndex, setCurrentSegmentIndex] = useState(0);
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [totalElements, setTotalElements] = useState(0);

  // Delete dialog
  const [deleteTarget, setDeleteTarget] = useState<Recording | null>(null);
  // Download state
  const [downloading, setDownloading] = useState(false);

  const basePath = import.meta.env.BASE_URL.replace(/\/$/, '');

  const loadRecordings = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const data = await getRecordings({ page, size: 20 });
      setRecordings(data.content);
      setTotalPages(data.total_pages);
      setTotalElements(data.total_elements);
    } catch (err) {
      setError('Не удалось загрузить записи');
      console.error(err);
    } finally {
      setLoading(false);
    }
  }, [page]);

  useEffect(() => {
    loadRecordings();
  }, [loadRecordings]);

  const handleSelect = async (recording: Recording) => {
    setSelectedRecording(recording);
    setSegments([]);
    setCurrentSegmentIndex(0);
    try {
      const data = await getRecordingSegments(recording.id);
      setSegments(data.segments || []);
    } catch (err) {
      console.error('Failed to load segments', err);
    }
  };

  const handleDelete = async () => {
    if (!deleteTarget) return;
    try {
      await deleteRecording(deleteTarget.id);
      addToast('success', 'Запись удалена');
      if (selectedRecording?.id === deleteTarget.id) {
        setSelectedRecording(null);
      }
      loadRecordings();
    } catch {
      addToast('error', 'Не удалось удалить запись');
    }
    setDeleteTarget(null);
  };

  const handleDownload = async (recording: Recording) => {
    setDownloading(true);
    try {
      const { blob, filename } = await downloadRecording(recording.id);
      const url = window.URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = filename;
      document.body.appendChild(a);
      a.click();
      window.URL.revokeObjectURL(url);
      document.body.removeChild(a);
      addToast('success', 'Скачивание началось');
    } catch {
      addToast('error', 'Не удалось скачать запись');
    } finally {
      setDownloading(false);
    }
  };

  const getSegmentUrl = (segment: Segment) => {
    return `${basePath}/prg-segments/${segment.s3_key}`;
  };

  return (
    <div className="flex h-[calc(100vh-7rem)] gap-0 -mx-4 sm:-mx-6 lg:-mx-8 -my-6">
      {/* Recordings list */}
      <div className={`${selectedRecording ? 'w-80 border-r border-gray-200' : 'flex-1'} flex flex-col bg-white overflow-hidden`}>
        <div className="px-4 py-3 border-b border-gray-200 flex items-center justify-between">
          <div>
            <h1 className={`font-bold tracking-tight text-gray-900 ${selectedRecording ? 'text-lg' : 'text-2xl'}`}>
              Архив записей
            </h1>
            {!selectedRecording && (
              <p className="mt-1 text-sm text-gray-600">
                {totalElements > 0 ? `${totalElements} записей` : 'Записи экранов операторов'}
              </p>
            )}
          </div>
          <button
            type="button"
            onClick={loadRecordings}
            className="p-2 rounded-md text-gray-400 hover:text-gray-600 hover:bg-gray-100 transition-colors"
            title="Обновить"
          >
            <ArrowPathIcon className={`h-5 w-5 ${loading ? 'animate-spin' : ''}`} />
          </button>
        </div>

        <div className="flex-1 overflow-y-auto">
          {error && (
            <div className="px-4 py-3 text-sm text-red-600 bg-red-50">{error}</div>
          )}

          {recordings.map((recording) => (
            <button
              key={recording.id}
              type="button"
              onClick={() => handleSelect(recording)}
              className={`w-full text-left px-4 py-3 border-b border-gray-100 hover:bg-gray-50 transition-colors ${
                selectedRecording?.id === recording.id ? 'bg-red-50 border-l-2 border-l-red-600' : ''
              }`}
            >
              <div className="flex items-center justify-between">
                <div className="flex items-center min-w-0">
                  <p className="text-sm font-medium text-gray-900 truncate">{recording.device_hostname}</p>
                  {recording.device_deleted && (
                    <span className="ml-1 inline-flex items-center rounded px-1.5 py-0.5 text-[10px] font-medium bg-red-100 text-red-700 flex-shrink-0">
                      Комп. удалён
                    </span>
                  )}
                </div>
                <StatusBadge status={recording.status} />
              </div>
              <div className="flex items-center justify-between mt-1">
                <p className="text-xs text-gray-400">{formatTime(recording.started_ts)}</p>
                <p className="text-xs text-gray-400">{formatDuration(recording.total_duration_ms)}</p>
              </div>
            </button>
          ))}

          {!loading && recordings.length === 0 && !error && (
            <div className="flex flex-col items-center justify-center py-12 text-center">
              <FilmIcon className="h-12 w-12 text-gray-300 mb-3" />
              <p className="text-sm text-gray-500">Записи не найдены</p>
              <p className="text-xs text-gray-400 mt-1">Записи появятся после подключения агентов</p>
            </div>
          )}

          {/* Pagination */}
          {totalPages > 1 && (
            <div className="flex items-center justify-between px-4 py-2 border-t border-gray-200">
              <button
                type="button"
                onClick={() => setPage(p => Math.max(0, p - 1))}
                disabled={page === 0}
                className="text-xs text-gray-500 hover:text-gray-700 disabled:opacity-50"
              >
                &larr; Назад
              </button>
              <span className="text-xs text-gray-400">{page + 1} / {totalPages}</span>
              <button
                type="button"
                onClick={() => setPage(p => Math.min(totalPages - 1, p + 1))}
                disabled={page >= totalPages - 1}
                className="text-xs text-gray-500 hover:text-gray-700 disabled:opacity-50"
              >
                Вперёд &rarr;
              </button>
            </div>
          )}
        </div>
      </div>

      {/* Recording detail + player */}
      {selectedRecording && (
        <div className="flex-1 flex flex-col bg-gray-50 overflow-hidden">
          <div className="px-6 py-3 border-b border-gray-200 bg-white flex items-center justify-between">
            <div>
              <h2 className="text-lg font-semibold text-gray-900">
                {selectedRecording.device_hostname}
                {selectedRecording.device_deleted && (
                  <span className="ml-2 text-sm font-normal text-red-600">(удалено)</span>
                )}
              </h2>
              <p className="text-sm text-gray-500">{formatTime(selectedRecording.started_ts)}</p>
            </div>
            <div className="flex items-center gap-2">
              {/* Download button */}
              <PermissionGate permission="RECORDINGS:EXPORT">
                {selectedRecording.status === 'completed' && segments.length > 0 && (
                  <button
                    type="button"
                    onClick={() => handleDownload(selectedRecording)}
                    disabled={downloading}
                    className="p-2 rounded-md text-gray-400 hover:text-indigo-600 hover:bg-indigo-50 transition-colors disabled:opacity-50"
                    title="Скачать запись"
                  >
                    <ArrowDownTrayIcon className={`h-5 w-5 ${downloading ? 'animate-bounce' : ''}`} />
                  </button>
                )}
              </PermissionGate>
              {/* Delete button */}
              <PermissionGate permission="RECORDINGS:DELETE">
                {selectedRecording.status !== 'active' && (
                  <button
                    type="button"
                    onClick={() => setDeleteTarget(selectedRecording)}
                    className="p-2 rounded-md text-gray-400 hover:text-red-600 hover:bg-red-50 transition-colors"
                    title="Удалить запись"
                  >
                    <TrashIcon className="h-5 w-5" />
                  </button>
                )}
              </PermissionGate>
              {/* Close button */}
              <button
                type="button"
                onClick={() => setSelectedRecording(null)}
                className="p-2 rounded-md text-gray-400 hover:text-gray-600 hover:bg-gray-100 transition-colors"
              >
                <XMarkIcon className="h-5 w-5" />
              </button>
            </div>
          </div>

          {/* Video player */}
          <div className="px-6 pt-4">
            <div className="bg-gray-900 rounded-lg aspect-video flex items-center justify-center relative overflow-hidden">
              {selectedRecording.status === 'completed' && segments.length > 0 ? (
                <video
                  key={segments[currentSegmentIndex]?.id}
                  className="w-full h-full"
                  controls
                  autoPlay
                  onEnded={() => {
                    if (currentSegmentIndex < segments.length - 1) {
                      setCurrentSegmentIndex(i => i + 1);
                    }
                  }}
                >
                  <source src={getSegmentUrl(segments[currentSegmentIndex])} type="video/mp4" />
                </video>
              ) : selectedRecording.status === 'active' ? (
                <div className="text-center">
                  <div className="flex items-center gap-2 text-green-400 mb-2">
                    <span className="h-2 w-2 rounded-full bg-green-400 animate-pulse" />
                    <span className="text-sm font-medium">Идёт запись</span>
                  </div>
                  <p className="text-xs text-gray-400">Воспроизведение доступно после завершения</p>
                </div>
              ) : segments.length === 0 && selectedRecording.status === 'completed' ? (
                <div className="text-center">
                  <p className="text-gray-400 text-sm">Загрузка сегментов...</p>
                </div>
              ) : (
                <div className="text-center">
                  <p className="text-red-400 text-sm font-medium">Запись недоступна</p>
                </div>
              )}
            </div>
            {segments.length > 1 && (
              <div className="flex items-center justify-center mt-2 gap-2">
                <span className="text-xs text-gray-500">
                  Сегмент {currentSegmentIndex + 1} из {segments.length}
                </span>
              </div>
            )}
          </div>

          {/* Metadata */}
          <div className="px-6 py-4 flex-1 overflow-y-auto">
            <h3 className="text-sm font-semibold text-gray-900 mb-3">Метаданные записи</h3>
            <div className="card">
              <dl className="grid grid-cols-1 sm:grid-cols-2 gap-x-6 gap-y-4 p-4">
                <div>
                  <dt className="text-xs font-medium text-gray-500">Устройство</dt>
                  <dd className="mt-1 text-sm text-gray-900">
                    {selectedRecording.device_hostname}
                    {selectedRecording.device_deleted && (
                      <span className="ml-1 text-red-600">(удалено)</span>
                    )}
                  </dd>
                </div>
                <div>
                  <dt className="text-xs font-medium text-gray-500">Начало</dt>
                  <dd className="mt-1 text-sm text-gray-900">{formatTime(selectedRecording.started_ts)}</dd>
                </div>
                <div>
                  <dt className="text-xs font-medium text-gray-500">Окончание</dt>
                  <dd className="mt-1 text-sm text-gray-900">{formatTime(selectedRecording.ended_ts)}</dd>
                </div>
                <div>
                  <dt className="text-xs font-medium text-gray-500">Длительность</dt>
                  <dd className="mt-1 text-sm text-gray-900">{formatDuration(selectedRecording.total_duration_ms)}</dd>
                </div>
                <div>
                  <dt className="text-xs font-medium text-gray-500">Размер</dt>
                  <dd className="mt-1 text-sm text-gray-900">{formatBytes(selectedRecording.total_bytes)}</dd>
                </div>
                <div>
                  <dt className="text-xs font-medium text-gray-500">Сегменты</dt>
                  <dd className="mt-1 text-sm text-gray-900">{selectedRecording.segment_count}</dd>
                </div>
                <div>
                  <dt className="text-xs font-medium text-gray-500">Статус</dt>
                  <dd className="mt-1"><StatusBadge status={selectedRecording.status} /></dd>
                </div>
                <div>
                  <dt className="text-xs font-medium text-gray-500">ID</dt>
                  <dd className="mt-1 text-sm text-gray-900 font-mono text-xs">{selectedRecording.id}</dd>
                </div>
              </dl>
            </div>
          </div>
        </div>
      )}

      {/* Delete confirmation dialog */}
      <ConfirmDialog
        open={!!deleteTarget}
        title="Удалить запись"
        message={`Вы уверены, что хотите удалить запись с устройства "${deleteTarget?.device_hostname}" от ${formatTime(deleteTarget?.started_ts ?? null)}? Все видеосегменты будут удалены из хранилища. Это действие необратимо.`}
        confirmText="Удалить"
        cancelText="Отмена"
        onConfirm={handleDelete}
        onCancel={() => setDeleteTarget(null)}
      />
    </div>
  );
}
