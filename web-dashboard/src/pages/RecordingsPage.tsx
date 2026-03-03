import { useState } from 'react';
import { FilmIcon, PlayIcon, XMarkIcon } from '@heroicons/react/24/outline';

// Mock data for recordings
interface Recording {
  id: string;
  user_name: string;
  device_name: string;
  started_at: string;
  ended_at: string;
  duration_seconds: number;
  status: 'completed' | 'in_progress' | 'failed';
  file_size_mb: number;
}

const MOCK_RECORDINGS: Recording[] = [
  {
    id: '1',
    user_name: 'Иванов И.И.',
    device_name: 'PC-OPERATOR-01',
    started_at: '2026-03-03T09:00:00Z',
    ended_at: '2026-03-03T09:32:15Z',
    duration_seconds: 1935,
    status: 'completed',
    file_size_mb: 245,
  },
  {
    id: '2',
    user_name: 'Петров П.П.',
    device_name: 'PC-OPERATOR-02',
    started_at: '2026-03-03T09:15:00Z',
    ended_at: '2026-03-03T10:01:42Z',
    duration_seconds: 2802,
    status: 'completed',
    file_size_mb: 312,
  },
  {
    id: '3',
    user_name: 'Сидорова А.В.',
    device_name: 'PC-OPERATOR-03',
    started_at: '2026-03-03T10:05:00Z',
    ended_at: '2026-03-03T10:45:30Z',
    duration_seconds: 2430,
    status: 'completed',
    file_size_mb: 198,
  },
  {
    id: '4',
    user_name: 'Козлов Д.С.',
    device_name: 'PC-OPERATOR-04',
    started_at: '2026-03-03T10:30:00Z',
    ended_at: '',
    duration_seconds: 0,
    status: 'in_progress',
    file_size_mb: 0,
  },
  {
    id: '5',
    user_name: 'Морозова Е.К.',
    device_name: 'PC-OPERATOR-05',
    started_at: '2026-03-03T08:00:00Z',
    ended_at: '2026-03-03T08:02:10Z',
    duration_seconds: 130,
    status: 'failed',
    file_size_mb: 15,
  },
];

function formatDuration(seconds: number): string {
  if (seconds === 0) return '--';
  const h = Math.floor(seconds / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  const s = seconds % 60;
  if (h > 0) return `${h}ч ${m}м ${s}с`;
  if (m > 0) return `${m}м ${s}с`;
  return `${s}с`;
}

function formatTime(iso: string): string {
  if (!iso) return '--';
  return new Date(iso).toLocaleString('ru-RU', {
    day: '2-digit',
    month: '2-digit',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  });
}

function StatusBadge({ status }: { status: string }) {
  const styles: Record<string, string> = {
    completed: 'bg-green-50 text-green-700 ring-green-600/20',
    in_progress: 'bg-blue-50 text-blue-700 ring-blue-600/20',
    failed: 'bg-red-50 text-red-700 ring-red-600/20',
  };
  const labels: Record<string, string> = {
    completed: 'Завершена',
    in_progress: 'Идёт запись',
    failed: 'Ошибка',
  };
  return (
    <span className={`inline-flex items-center rounded-md px-2 py-1 text-xs font-medium ring-1 ring-inset ${styles[status] || ''}`}>
      {labels[status] || status}
    </span>
  );
}

export default function RecordingsPage() {
  const [selectedRecording, setSelectedRecording] = useState<Recording | null>(null);

  return (
    <div className="flex h-[calc(100vh-7rem)] gap-0 -mx-4 sm:-mx-6 lg:-mx-8 -my-6">
      {/* Recordings list */}
      <div className={`${selectedRecording ? 'w-80 border-r border-gray-200' : 'flex-1'} flex flex-col bg-white overflow-hidden`}>
        <div className="px-4 py-3 border-b border-gray-200">
          <h1 className={`font-bold tracking-tight text-gray-900 ${selectedRecording ? 'text-lg' : 'text-2xl'}`}>
            Архив записей
          </h1>
          {!selectedRecording && (
            <p className="mt-1 text-sm text-gray-600">
              Записи экранов операторов контактного центра.
            </p>
          )}
        </div>

        <div className="flex-1 overflow-y-auto">
          {MOCK_RECORDINGS.map((recording) => (
            <button
              key={recording.id}
              type="button"
              onClick={() => setSelectedRecording(recording)}
              className={`w-full text-left px-4 py-3 border-b border-gray-100 hover:bg-gray-50 transition-colors ${
                selectedRecording?.id === recording.id ? 'bg-red-50 border-l-2 border-l-red-600' : ''
              }`}
            >
              <div className="flex items-center justify-between">
                <p className="text-sm font-medium text-gray-900 truncate">{recording.user_name}</p>
                <StatusBadge status={recording.status} />
              </div>
              <p className="text-xs text-gray-500 mt-0.5">{recording.device_name}</p>
              <div className="flex items-center justify-between mt-1">
                <p className="text-xs text-gray-400">{formatTime(recording.started_at)}</p>
                <p className="text-xs text-gray-400">{formatDuration(recording.duration_seconds)}</p>
              </div>
            </button>
          ))}

          {MOCK_RECORDINGS.length === 0 && (
            <div className="flex flex-col items-center justify-center py-12 text-center">
              <FilmIcon className="h-12 w-12 text-gray-300 mb-3" />
              <p className="text-sm text-gray-500">Записи не найдены</p>
            </div>
          )}
        </div>
      </div>

      {/* Recording detail + player */}
      {selectedRecording && (
        <div className="flex-1 flex flex-col bg-gray-50 overflow-hidden">
          {/* Close button + header */}
          <div className="px-6 py-3 border-b border-gray-200 bg-white flex items-center justify-between">
            <div>
              <h2 className="text-lg font-semibold text-gray-900">{selectedRecording.user_name}</h2>
              <p className="text-sm text-gray-500">{selectedRecording.device_name} &middot; {formatTime(selectedRecording.started_at)}</p>
            </div>
            <button
              type="button"
              onClick={() => setSelectedRecording(null)}
              className="p-2 rounded-md text-gray-400 hover:text-gray-600 hover:bg-gray-100 transition-colors"
            >
              <XMarkIcon className="h-5 w-5" />
            </button>
          </div>

          {/* Player area */}
          <div className="px-6 pt-4">
            <div className="bg-gray-900 rounded-lg aspect-video flex items-center justify-center relative">
              {selectedRecording.status === 'completed' ? (
                <button
                  type="button"
                  className="flex items-center justify-center w-16 h-16 rounded-full bg-white/20 hover:bg-white/30 transition-colors"
                >
                  <PlayIcon className="h-8 w-8 text-white ml-1" />
                </button>
              ) : selectedRecording.status === 'in_progress' ? (
                <div className="text-center">
                  <div className="flex items-center gap-2 text-green-400 mb-2">
                    <span className="h-2 w-2 rounded-full bg-green-400 animate-pulse" />
                    <span className="text-sm font-medium">Идёт запись</span>
                  </div>
                  <p className="text-xs text-gray-400">Воспроизведение будет доступно после завершения</p>
                </div>
              ) : (
                <div className="text-center">
                  <p className="text-red-400 text-sm font-medium">Запись повреждена</p>
                  <p className="text-xs text-gray-400 mt-1">Произошла ошибка при записи</p>
                </div>
              )}
            </div>
          </div>

          {/* Metadata */}
          <div className="px-6 py-4 flex-1 overflow-y-auto">
            <h3 className="text-sm font-semibold text-gray-900 mb-3">Метаданные записи</h3>
            <div className="card">
              <dl className="grid grid-cols-1 sm:grid-cols-2 gap-x-6 gap-y-4">
                <div>
                  <dt className="text-xs font-medium text-gray-500">Оператор</dt>
                  <dd className="mt-1 text-sm text-gray-900">{selectedRecording.user_name}</dd>
                </div>
                <div>
                  <dt className="text-xs font-medium text-gray-500">Устройство</dt>
                  <dd className="mt-1 text-sm text-gray-900">{selectedRecording.device_name}</dd>
                </div>
                <div>
                  <dt className="text-xs font-medium text-gray-500">Начало</dt>
                  <dd className="mt-1 text-sm text-gray-900">{formatTime(selectedRecording.started_at)}</dd>
                </div>
                <div>
                  <dt className="text-xs font-medium text-gray-500">Окончание</dt>
                  <dd className="mt-1 text-sm text-gray-900">{formatTime(selectedRecording.ended_at)}</dd>
                </div>
                <div>
                  <dt className="text-xs font-medium text-gray-500">Длительность</dt>
                  <dd className="mt-1 text-sm text-gray-900">{formatDuration(selectedRecording.duration_seconds)}</dd>
                </div>
                <div>
                  <dt className="text-xs font-medium text-gray-500">Размер файла</dt>
                  <dd className="mt-1 text-sm text-gray-900">{selectedRecording.file_size_mb ? `${selectedRecording.file_size_mb} МБ` : '--'}</dd>
                </div>
                <div>
                  <dt className="text-xs font-medium text-gray-500">Статус</dt>
                  <dd className="mt-1"><StatusBadge status={selectedRecording.status} /></dd>
                </div>
                <div>
                  <dt className="text-xs font-medium text-gray-500">ID записи</dt>
                  <dd className="mt-1 text-sm text-gray-900 font-mono">{selectedRecording.id}</dd>
                </div>
              </dl>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
