import { useState, useEffect } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { ArrowLeftIcon } from '@heroicons/react/24/outline';
import VideoPlayer from '../components/player/VideoPlayer';
import { getAccessToken } from '../api/client';
import { getRecordingSegments } from '../api/ingest';
import type { Segment } from '../api/ingest';

export default function SessionDetailPage() {
  const { sessionId } = useParams<{ sessionId: string }>();
  const navigate = useNavigate();
  const token = getAccessToken();
  const [segments, setSegments] = useState<Segment[]>([]);
  const [loading, setLoading] = useState(true);

  const basePath = import.meta.env.BASE_URL.replace(/\/$/, '');
  const playlistUrl = `${basePath}/api/playback/v1/playback/sessions/${sessionId}/playlist.m3u8`;

  useEffect(() => {
    if (!sessionId) return;
    setLoading(true);
    getRecordingSegments(sessionId)
      .then((data) => setSegments(data.segments || []))
      .catch(() => setSegments([]))
      .finally(() => setLoading(false));
  }, [sessionId]);

  const totalDuration = segments.reduce((sum, s) => sum + (s.duration_ms || 0), 0);
  const totalSize = segments.reduce((sum, s) => sum + (s.size_bytes || 0), 0);

  return (
    <div className="p-6">
      <button
        onClick={() => navigate(-1)}
        className="flex items-center gap-2 text-gray-400 hover:text-white mb-4"
      >
        <ArrowLeftIcon className="w-4 h-4" />
        Назад
      </button>

      <h1 className="text-2xl font-bold text-white mb-2">Просмотр записи</h1>
      <p className="text-gray-400 text-sm mb-6 font-mono">{sessionId}</p>

      <div className="grid grid-cols-1 sm:grid-cols-3 gap-4 mb-6">
        <div className="bg-white/5 rounded-lg p-4">
          <div className="text-gray-400 text-xs uppercase">Сегментов</div>
          <div className="text-white text-xl font-bold">{segments.length}</div>
        </div>
        <div className="bg-white/5 rounded-lg p-4">
          <div className="text-gray-400 text-xs uppercase">Длительность</div>
          <div className="text-white text-xl font-bold">
            {Math.round(totalDuration / 60000)} мин
          </div>
        </div>
        <div className="bg-white/5 rounded-lg p-4">
          <div className="text-gray-400 text-xs uppercase">Размер</div>
          <div className="text-white text-xl font-bold">
            {(totalSize / (1024 * 1024)).toFixed(1)} MB
          </div>
        </div>
      </div>

      {token && !loading ? (
        <VideoPlayer playlistUrl={playlistUrl} token={token} />
      ) : (
        <div className="bg-black rounded-lg flex items-center justify-center h-64 text-gray-400">
          {loading ? 'Загрузка...' : 'Требуется авторизация'}
        </div>
      )}

      {segments.length > 0 && (
        <div className="mt-6">
          <h2 className="text-lg font-bold text-white mb-3">Сегменты</h2>
          <div className="flex gap-0.5 h-3 rounded overflow-hidden">
            {segments.map((seg) => (
              <div
                key={seg.id}
                className="bg-red-600 hover:bg-red-400 transition-colors"
                style={{ flex: seg.duration_ms || 60000 }}
                title={`#${seg.sequence_num} — ${Math.round((seg.duration_ms || 0) / 1000)}с — ${(seg.size_bytes / (1024 * 1024)).toFixed(1)}MB`}
              />
            ))}
          </div>
        </div>
      )}
    </div>
  );
}
