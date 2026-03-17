import { ArrowDownTrayIcon } from '@heroicons/react/24/outline';
import type { Segment } from '../../api/ingest';

interface SegmentTimelineProps {
  segments: Segment[];
  activeIndex: number;
  onSegmentClick: (index: number) => void;
  onDownloadSegment?: (segment: Segment) => void;
}

export default function SegmentTimeline({
  segments,
  activeIndex,
  onSegmentClick,
  onDownloadSegment,
}: SegmentTimelineProps) {
  const totalDuration = segments.reduce((sum, s) => sum + (s.duration_ms || 60000), 0);

  return (
    <div className="space-y-2">
      <div className="flex items-center gap-2">
        <span className="text-xs text-gray-400 shrink-0">Сегменты</span>
        <div className="flex gap-0.5 h-6 flex-1 rounded overflow-hidden">
          {segments.map((seg, i) => {
            const isActive = i === activeIndex;
            const widthPct = ((seg.duration_ms || 60000) / totalDuration) * 100;
            return (
              <button
                key={seg.id}
                onClick={() => onSegmentClick(i)}
                className={`relative transition-colors ${
                  isActive ? 'bg-red-500' : 'bg-white/20 hover:bg-white/30'
                }`}
                style={{ width: `${Math.max(widthPct, 0.5)}%` }}
                title={`#${seg.sequence_num} - ${Math.round((seg.duration_ms || 0) / 1000)}с - ${(seg.size_bytes / (1024 * 1024)).toFixed(1)}MB`}
              >
                {isActive && segments.length <= 30 && (
                  <span className="absolute inset-0 flex items-center justify-center text-[9px] text-white font-bold">
                    {seg.sequence_num}
                  </span>
                )}
              </button>
            );
          })}
        </div>
        {onDownloadSegment && segments[activeIndex] && (
          <button
            onClick={() => onDownloadSegment(segments[activeIndex])}
            className="shrink-0 p-1 rounded hover:bg-white/10 text-gray-400 hover:text-white transition-colors"
            title="Скачать текущий сегмент"
          >
            <ArrowDownTrayIcon className="w-4 h-4" />
          </button>
        )}
      </div>
      <div className="flex justify-between text-[10px] text-gray-500">
        <span>#{segments[0]?.sequence_num ?? 1}</span>
        <span>Сегмент #{segments[activeIndex]?.sequence_num ?? '-'} из {segments.length}</span>
        <span>#{segments[segments.length - 1]?.sequence_num ?? segments.length}</span>
      </div>
    </div>
  );
}
