import { useState, useEffect, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import { getUserRecordings } from '../../api/user-activity';
import type { RecordingItem } from '../../types/user-activity';
import LoadingSpinner from '../LoadingSpinner';
import {
  VideoCameraIcon,
  PlayIcon,
  ChevronLeftIcon,
  ChevronRightIcon,
} from '@heroicons/react/24/outline';

interface Props {
  username: string;
  from: string;
  to: string;
}

function formatDuration(ms: number): string {
  if (ms <= 0) return '0 сек';
  const seconds = Math.floor(ms / 1000);
  const minutes = Math.floor(seconds / 60);
  const hours = Math.floor(minutes / 60);
  if (hours > 0) return `${hours} ч ${minutes % 60} мин`;
  if (minutes > 0) return `${minutes} мин ${seconds % 60} сек`;
  return `${seconds} сек`;
}

function formatBytes(bytes: number): string {
  if (bytes === 0) return '0 Б';
  const k = 1024;
  const sizes = ['Б', 'КБ', 'МБ', 'ГБ'];
  const i = Math.floor(Math.log(bytes) / Math.log(k));
  return parseFloat((bytes / Math.pow(k, i)).toFixed(1)) + ' ' + sizes[i];
}

function formatDateTime(iso: string | null): string {
  if (!iso) return '-';
  return new Date(iso).toLocaleString('ru-RU', {
    day: '2-digit',
    month: '2-digit',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  });
}

/** Extract YYYY-MM-DD from ISO timestamp for navigation to device recordings page */
function extractDate(iso: string | null): string | null {
  if (!iso) return null;
  return iso.substring(0, 10);
}

export default function EmployeeRecordings({ username, from, to }: Props) {
  const navigate = useNavigate();
  const [recordings, setRecordings] = useState<RecordingItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [totalElements, setTotalElements] = useState(0);

  const fetchRecordings = useCallback(async () => {
    try {
      setLoading(true);
      const resp = await getUserRecordings(username, from, to, { page, size: 20 });
      setRecordings(resp.content);
      setTotalPages(resp.total_pages);
      setTotalElements(resp.total_elements);
    } catch (err) {
      console.error('Failed to load recordings', err);
    } finally {
      setLoading(false);
    }
  }, [username, from, to, page]);

  useEffect(() => {
    fetchRecordings();
  }, [fetchRecordings]);

  useEffect(() => {
    setPage(0);
  }, [from, to]);

  /** Navigate to device recordings page with the date of this recording */
  const handlePlayClick = (rec: RecordingItem) => {
    const date = extractDate(rec.started_ts);
    navigate(`/archive/devices/${rec.device_id}${date ? `?date=${date}` : ''}`);
  };

  if (loading && recordings.length === 0) {
    return (
      <div className="flex items-center justify-center h-64">
        <LoadingSpinner size="lg" />
      </div>
    );
  }

  if (recordings.length === 0) {
    return (
      <div className="text-center py-12">
        <VideoCameraIcon className="mx-auto h-12 w-12 text-gray-400" />
        <h3 className="mt-2 text-sm font-semibold text-gray-900">Нет записей</h3>
        <p className="mt-1 text-sm text-gray-500">
          Записи экранов за выбранный период не найдены для этого сотрудника
        </p>
      </div>
    );
  }

  return (
    <div className="space-y-4">
      <div className="text-sm text-gray-500">
        Найдено {totalElements} запис{totalElements === 1 ? 'ь' : totalElements < 5 ? 'и' : 'ей'}
      </div>

      <div className="overflow-hidden rounded-lg border border-gray-200 shadow-sm">
        <table className="min-w-full divide-y divide-gray-200">
          <thead className="bg-gray-50">
            <tr>
              <th className="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider w-10"></th>
              <th className="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
                Устройство
              </th>
              <th className="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
                Начало
              </th>
              <th className="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
                Окончание
              </th>
              <th className="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
                Длительность
              </th>
              <th className="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
                Сегменты
              </th>
              <th className="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
                Размер
              </th>
              <th className="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
                Статус
              </th>
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-200 bg-white">
            {recordings.map((rec) => (
              <tr
                key={rec.id}
                className="hover:bg-gray-50 cursor-pointer transition-colors"
                onClick={() => handlePlayClick(rec)}
              >
                <td className="px-4 py-4 whitespace-nowrap">
                  <button
                    onClick={(e) => { e.stopPropagation(); handlePlayClick(rec); }}
                    className="p-1.5 text-red-600 hover:text-red-700 hover:bg-red-50 rounded-full transition-colors"
                    title="Воспроизвести запись"
                  >
                    <PlayIcon className="h-5 w-5" />
                  </button>
                </td>
                <td className="px-4 py-4 whitespace-nowrap text-sm font-medium text-gray-900">
                  {rec.device_hostname || rec.device_id.substring(0, 8)}
                </td>
                <td className="px-4 py-4 whitespace-nowrap text-sm text-gray-500">
                  {formatDateTime(rec.started_ts)}
                </td>
                <td className="px-4 py-4 whitespace-nowrap text-sm text-gray-500">
                  {formatDateTime(rec.ended_ts)}
                </td>
                <td className="px-4 py-4 whitespace-nowrap text-sm text-gray-500">
                  {formatDuration(rec.total_duration_ms)}
                </td>
                <td className="px-4 py-4 whitespace-nowrap text-sm text-gray-500">
                  {rec.segment_count}
                </td>
                <td className="px-4 py-4 whitespace-nowrap text-sm text-gray-500">
                  {formatBytes(rec.total_bytes)}
                </td>
                <td className="px-4 py-4 whitespace-nowrap">
                  <span
                    className={`inline-flex items-center rounded-full px-2 py-1 text-xs font-medium ${
                      rec.status === 'active'
                        ? 'bg-green-100 text-green-700'
                        : 'bg-gray-100 text-gray-500'
                    }`}
                  >
                    {rec.status === 'active' ? 'Идет' : 'Завершена'}
                  </span>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {totalPages > 1 && (
        <div className="flex items-center justify-center gap-4 pt-4">
          <button
            onClick={() => setPage(Math.max(0, page - 1))}
            disabled={page === 0}
            className="flex items-center gap-1 px-3 py-2 text-sm font-medium text-gray-700 bg-white border border-gray-300 rounded-md hover:bg-gray-50 disabled:opacity-50 disabled:cursor-not-allowed"
          >
            <ChevronLeftIcon className="h-4 w-4" />
            Назад
          </button>
          <span className="text-sm text-gray-700">
            Страница {page + 1} из {totalPages}
          </span>
          <button
            onClick={() => setPage(Math.min(totalPages - 1, page + 1))}
            disabled={page >= totalPages - 1}
            className="flex items-center gap-1 px-3 py-2 text-sm font-medium text-gray-700 bg-white border border-gray-300 rounded-md hover:bg-gray-50 disabled:opacity-50 disabled:cursor-not-allowed"
          >
            Далее
            <ChevronRightIcon className="h-4 w-4" />
          </button>
        </div>
      )}
    </div>
  );
}
