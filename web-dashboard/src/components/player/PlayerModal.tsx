import { useState, useEffect, useCallback } from 'react';
import { XMarkIcon, ArrowDownTrayIcon } from '@heroicons/react/24/outline';
import VideoPlayer from './VideoPlayer';
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
  const playlistUrl = `${basePath}/api/playback/v1/playback/sessions/${sessionId}/playlist.m3u8`;

  const [activeIndex, setActiveIndex] = useState(initialSegmentIndex);
  const [seekPosition, setSeekPosition] = useState<number | undefined>(undefined);
  const [downloading, setDownloading] = useState<string | null>(null);

  // Calculate cumulative offsets for each segment (in seconds)
  const segmentOffsets = segments.reduce<number[]>((acc, _seg, i) => {
    if (i === 0) {
      acc.push(0);
    } else {
      acc.push(acc[i - 1] + (segments[i - 1].duration_ms || 0) / 1000);
    }
    return acc;
  }, []);

  // Set initial seek position
  useEffect(() => {
    if (initialSegmentIndex > 0 && segmentOffsets[initialSegmentIndex] != null) {
      setSeekPosition(segmentOffsets[initialSegmentIndex]);
    }
  }, []);

  // Track current segment based on playback time
  const handleTimeUpdate = useCallback(
    (currentTime: number) => {
      for (let i = segmentOffsets.length - 1; i >= 0; i--) {
        if (currentTime >= segmentOffsets[i]) {
          setActiveIndex(i);
          break;
        }
      }
    },
    [segmentOffsets],
  );

  // Click on segment in timeline
  const handleSegmentClick = (index: number) => {
    setActiveIndex(index);
    setSeekPosition(segmentOffsets[index]);
  };

  // Escape key handler
  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, [onClose]);

  // Download full session ZIP
  const handleDownloadSession = async () => {
    setDownloading('session');
    try {
      const { blob, filename } = await downloadRecording(sessionId);
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = filename;
      a.click();
      URL.revokeObjectURL(url);
    } catch (err) {
      console.error('Download session failed:', err);
    } finally {
      setDownloading(null);
    }
  };

  // Download single segment MP4
  const handleDownloadSegment = async (seg: Segment) => {
    setDownloading(seg.id);
    try {
      const { blob, filename } = await downloadSegment(sessionId, seg.id);
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = filename;
      a.click();
      URL.revokeObjectURL(url);
    } catch (err) {
      console.error('Download segment failed:', err);
    } finally {
      setDownloading(null);
    }
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
          {token ? (
            <VideoPlayer
              playlistUrl={playlistUrl}
              token={token}
              startPosition={seekPosition}
              onTimeUpdate={handleTimeUpdate}
            />
          ) : (
            <div className="bg-black rounded-lg flex items-center justify-center h-64 text-gray-400">
              Требуется авторизация
            </div>
          )}
        </div>

        {/* Segment Timeline */}
        {segments.length > 0 && (
          <div className="px-4 pt-3">
            <SegmentTimeline
              segments={segments}
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
          {segments[activeIndex] && (
            <button
              onClick={() => handleDownloadSegment(segments[activeIndex])}
              disabled={downloading === segments[activeIndex].id}
              className="flex items-center gap-2 px-3 py-1.5 bg-white/10 hover:bg-white/20 text-white text-sm rounded-lg transition-colors disabled:opacity-50"
            >
              <ArrowDownTrayIcon className="w-4 h-4" />
              {downloading === segments[activeIndex].id
                ? 'Загрузка...'
                : `Скачать сегмент #${segments[activeIndex].sequence_num} (MP4)`}
            </button>
          )}
        </div>
      </div>
    </div>
  );
}
