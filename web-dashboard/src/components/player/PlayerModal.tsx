import { useState, useEffect, useRef, useCallback } from 'react';
import { XMarkIcon, ArrowDownTrayIcon } from '@heroicons/react/24/outline';
import SegmentTimeline from './SegmentTimeline';
import { getAccessToken } from '../../api/client';
import { downloadRecording, downloadSegment } from '../../api/ingest';
import type { Segment } from '../../api/ingest';

interface PlayerModalProps {
  sessionId: string;
  segments: Segment[];
  initialSegmentIndex?: number;
  onClose: () => void;
}

export default function PlayerModal({
  sessionId,
  segments,
  initialSegmentIndex = 0,
  onClose,
}: PlayerModalProps) {
  const token = getAccessToken();
  const basePath = import.meta.env.BASE_URL.replace(/\/$/, '');

  // Only confirmed segments can be played
  const playableSegments = segments.filter(
    (s) => s.status === 'confirmed' || s.status === 'indexed',
  );

  const [activeIndex, setActiveIndex] = useState(
    Math.min(initialSegmentIndex, Math.max(0, playableSegments.length - 1)),
  );
  const [downloading, setDownloading] = useState<string | null>(null);
  const [autoplay, setAutoplay] = useState(true);
  const videoRef = useRef<HTMLVideoElement>(null);

  // Build segment video URL (direct MP4 proxy from playback-service)
  const getSegmentUrl = useCallback(
    (seg: Segment) =>
      `${basePath}/api/playback/v1/playback/sessions/${sessionId}/segments/${seg.id}`,
    [basePath, sessionId],
  );

  const currentSegment = playableSegments[activeIndex];
  const currentUrl = currentSegment ? getSegmentUrl(currentSegment) : '';

  // Fetch segment with auth header using blob URL
  const [blobUrl, setBlobUrl] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    if (!currentUrl || !token) return;
    let cancelled = false;
    setLoading(true);

    fetch(currentUrl, {
      headers: { Authorization: `Bearer ${token}` },
    })
      .then((res) => {
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        return res.blob();
      })
      .then((blob) => {
        if (cancelled) return;
        const url = URL.createObjectURL(blob);
        setBlobUrl((prev) => {
          if (prev) URL.revokeObjectURL(prev);
          return url;
        });
        setLoading(false);
      })
      .catch((err) => {
        if (cancelled) return;
        console.error('Failed to load segment:', err);
        setLoading(false);
      });

    return () => {
      cancelled = true;
    };
  }, [currentUrl, token]);

  // Auto-play when blob URL changes
  useEffect(() => {
    if (blobUrl && videoRef.current && autoplay) {
      videoRef.current.play().catch(() => {});
    }
  }, [blobUrl, autoplay]);

  // Auto-advance to next segment when current ends
  const handleEnded = useCallback(() => {
    if (activeIndex < playableSegments.length - 1) {
      setActiveIndex(activeIndex + 1);
    }
  }, [activeIndex, playableSegments.length]);

  // Click on segment in timeline
  const handleSegmentClick = (index: number) => {
    setActiveIndex(index);
    setAutoplay(true);
  };

  // Escape key
  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, [onClose]);

  // Cleanup blob URL on unmount
  useEffect(() => {
    return () => {
      if (blobUrl) URL.revokeObjectURL(blobUrl);
    };
  }, []);

  // Downloads
  const handleDownloadSession = async () => {
    setDownloading('session');
    try {
      const { blob, filename } = await downloadRecording(sessionId);
      triggerDownload(blob, filename);
    } catch (err) {
      console.error('Download session failed:', err);
    } finally {
      setDownloading(null);
    }
  };

  const handleDownloadSegment = async (seg: Segment) => {
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

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/80"
      onClick={(e) => {
        if (e.target === e.currentTarget) onClose();
      }}
    >
      <div className="relative w-full max-w-5xl mx-4 bg-gray-900 rounded-xl overflow-hidden shadow-2xl">
        {/* Header */}
        <div className="flex items-center justify-between px-4 py-3 border-b border-white/10">
          <div className="flex items-center gap-3">
            <h3 className="text-white font-semibold text-sm">Просмотр записи</h3>
            <span className="text-gray-500 text-xs font-mono">{sessionId.slice(0, 12)}...</span>
            {currentSegment && (
              <span className="text-gray-400 text-xs">
                Сегмент {activeIndex + 1} / {playableSegments.length}
              </span>
            )}
          </div>
          <button
            onClick={onClose}
            className="p-1 rounded hover:bg-white/10 text-gray-400 hover:text-white transition-colors"
          >
            <XMarkIcon className="w-5 h-5" />
          </button>
        </div>

        {/* Video */}
        <div className="px-4 pt-3">
          {loading && !blobUrl ? (
            <div className="bg-black rounded-lg flex items-center justify-center h-64 text-gray-400">
              Загрузка сегмента...
            </div>
          ) : blobUrl ? (
            <div className="relative bg-black rounded-lg overflow-hidden">
              <video
                ref={videoRef}
                src={blobUrl}
                controls
                onEnded={handleEnded}
                className="w-full"
                style={{ maxHeight: '70vh' }}
              />
              {loading && (
                <div className="absolute top-2 right-2 bg-black/60 text-white text-xs px-2 py-1 rounded">
                  Загрузка...
                </div>
              )}
            </div>
          ) : (
            <div className="bg-black rounded-lg flex items-center justify-center h-64 text-gray-400">
              {playableSegments.length === 0
                ? 'Нет доступных сегментов для воспроизведения'
                : 'Ошибка загрузки'}
            </div>
          )}
        </div>

        {/* Segment Timeline */}
        {playableSegments.length > 0 && (
          <div className="px-4 pt-3">
            <SegmentTimeline
              segments={playableSegments}
              activeIndex={activeIndex}
              onSegmentClick={handleSegmentClick}
              onDownloadSegment={handleDownloadSegment}
            />
          </div>
        )}

        {/* Actions */}
        <div className="flex items-center gap-3 px-4 py-3 border-t border-white/10 mt-3">
          <button
            onClick={handleDownloadSession}
            disabled={downloading === 'session'}
            className="flex items-center gap-2 px-3 py-1.5 bg-white/10 hover:bg-white/20 text-white text-sm rounded-lg transition-colors disabled:opacity-50"
          >
            <ArrowDownTrayIcon className="w-4 h-4" />
            {downloading === 'session' ? 'Загрузка...' : 'Скачать сессию (ZIP)'}
          </button>
          {currentSegment && (
            <button
              onClick={() => handleDownloadSegment(currentSegment)}
              disabled={downloading === currentSegment.id}
              className="flex items-center gap-2 px-3 py-1.5 bg-white/10 hover:bg-white/20 text-white text-sm rounded-lg transition-colors disabled:opacity-50"
            >
              <ArrowDownTrayIcon className="w-4 h-4" />
              {downloading === currentSegment.id
                ? 'Загрузка...'
                : `Скачать сегмент #${currentSegment.sequence_num} (MP4)`}
            </button>
          )}
        </div>
      </div>
    </div>
  );
}
