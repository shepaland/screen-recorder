import { useEffect, useRef } from 'react';
import Hls from 'hls.js';

interface VideoPlayerProps {
  playlistUrl: string;
  token: string;
  startPosition?: number;
  onTimeUpdate?: (currentTime: number) => void;
}

export default function VideoPlayer({ playlistUrl, token, startPosition, onTimeUpdate }: VideoPlayerProps) {
  const videoRef = useRef<HTMLVideoElement>(null);
  const hlsRef = useRef<Hls | null>(null);
  const startPositionRef = useRef(startPosition);

  // Keep startPositionRef in sync for seeking after manifest load
  useEffect(() => {
    startPositionRef.current = startPosition;
  }, [startPosition]);

  useEffect(() => {
    if (!videoRef.current) return;
    const video = videoRef.current;

    if (Hls.isSupported()) {
      const hls = new Hls({
        xhrSetup: (xhr) => {
          xhr.setRequestHeader('Authorization', `Bearer ${token}`);
        },
      });
      hlsRef.current = hls;
      hls.loadSource(playlistUrl);
      hls.attachMedia(video);
      hls.on(Hls.Events.MANIFEST_PARSED, () => {
        if (startPositionRef.current != null && startPositionRef.current > 0) {
          video.currentTime = startPositionRef.current;
        }
      });
      hls.on(Hls.Events.ERROR, (_event, data) => {
        if (data.fatal) {
          console.error('HLS fatal error:', data.type, data.details);
        }
      });
      return () => {
        hlsRef.current = null;
        hls.destroy();
      };
    } else if (video.canPlayType('application/vnd.apple.mpegurl')) {
      video.src = playlistUrl;
    }
  }, [playlistUrl, token]);

  // Seek when startPosition changes externally (e.g., clicking a segment)
  useEffect(() => {
    if (startPosition != null && videoRef.current && hlsRef.current) {
      videoRef.current.currentTime = startPosition;
    }
  }, [startPosition]);

  // Time update callback
  useEffect(() => {
    if (!videoRef.current || !onTimeUpdate) return;
    const video = videoRef.current;
    const handler = () => onTimeUpdate(video.currentTime);
    video.addEventListener('timeupdate', handler);
    return () => video.removeEventListener('timeupdate', handler);
  }, [onTimeUpdate]);

  return (
    <div className="relative bg-black rounded-lg overflow-hidden">
      <video
        ref={videoRef}
        controls
        className="w-full"
        style={{ maxHeight: '70vh' }}
      />
    </div>
  );
}
