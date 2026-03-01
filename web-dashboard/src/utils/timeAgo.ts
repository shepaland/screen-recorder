/**
 * Format a timestamp as a relative "time ago" string in Russian.
 */
export function timeAgo(dateStr: string | null): string {
  if (!dateStr) return '\u2014';
  const diff = Date.now() - new Date(dateStr).getTime();
  const seconds = Math.floor(diff / 1000);
  if (seconds < 60) return `${seconds} \u0441\u0435\u043a \u043d\u0430\u0437\u0430\u0434`;
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return `${minutes} \u043c\u0438\u043d \u043d\u0430\u0437\u0430\u0434`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours} \u0447 \u043d\u0430\u0437\u0430\u0434`;
  return new Date(dateStr).toLocaleDateString('ru-RU');
}

/**
 * Format bytes into a human-readable string.
 */
export function formatBytes(bytes: number): string {
  if (bytes === 0) return '0 \u0411';
  const k = 1024;
  const sizes = ['\u0411', '\u041a\u0411', '\u041c\u0411', '\u0413\u0411'];
  const i = Math.floor(Math.log(bytes) / Math.log(k));
  return parseFloat((bytes / Math.pow(k, i)).toFixed(1)) + ' ' + sizes[i];
}

/**
 * Format a duration in milliseconds as HH:MM:SS.
 */
export function formatDuration(ms: number): string {
  const seconds = Math.floor(ms / 1000);
  const h = Math.floor(seconds / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  const s = seconds % 60;
  return `${h.toString().padStart(2, '0')}:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}`;
}
