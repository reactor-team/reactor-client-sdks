import { useEffect, useMemo, useRef, useState } from 'react';
import type { CSSProperties, VideoHTMLAttributes } from 'react';
import { useReactor } from './hooks';

export interface ReactorViewProps {
  /** Name of the recvonly video track to render. Must match a track name
   *  declared in the model's capabilities. Defaults to `"main_video"`. */
  track?: string;
  /** Name of a recvonly audio track to mix into the same `<video>` element
   *  alongside `track`. */
  audioTrack?: string;
  width?: number;
  height?: number;
  className?: string;
  style?: CSSProperties;
  videoObjectFit?: NonNullable<VideoHTMLAttributes<HTMLVideoElement>['style']>['objectFit'];
  /** Defaults to `true` when no `audioTrack` is set (keeps the element within
   *  browser autoplay policies), `false` otherwise. Pass an explicit value to
   *  override either default. */
  muted?: boolean;
}

/** The current `MediaStreamTrack` for `name`, kept live across `trackReceived`
 *  events. Reads through `internal.reactor` directly rather than the store —
 *  there's no reactive `tracks` map on `ReactorState` to select from. */
function useReceivedTrack(name: string | undefined): MediaStreamTrack | undefined {
  const reactor = useReactor((state) => state.internal.reactor);
  const [track, setTrack] = useState(() => (name ? reactor.getTrackByName(name) : undefined));

  useEffect(() => {
    setTrack(name ? reactor.getTrackByName(name) : undefined);

    if (!name) {
      return;
    }

    const listener = (receivedName: string, receivedTrack: MediaStreamTrack) => {
      if (receivedName === name) {
        setTrack(receivedTrack);
      }
    };

    reactor.on('trackReceived', listener);
    return () => reactor.off('trackReceived', listener);
  }, [reactor, name]);

  return track;
}

export function ReactorView({
  track = 'main_video',
  audioTrack,
  width,
  height,
  className,
  style,
  videoObjectFit = 'contain',
  muted = audioTrack === undefined,
}: ReactorViewProps) {
  const videoMediaTrack = useReceivedTrack(track);
  const audioMediaTrack = useReceivedTrack(audioTrack);
  const videoRef = useRef<HTMLVideoElement>(null);

  const mediaStream = useMemo(() => {
    const tracks: MediaStreamTrack[] = [];

    if (videoMediaTrack) {
      tracks.push(videoMediaTrack);
    }
    if (audioMediaTrack) {
      tracks.push(audioMediaTrack);
    }
    return tracks.length > 0 ? new MediaStream(tracks) : null;
  }, [videoMediaTrack, audioMediaTrack]);

  useEffect(() => {
    const el = videoRef.current;

    if (!el || !mediaStream) {
      return;
    }

    const attach = (reset: boolean) => {
      if (reset) {
        el.srcObject = null;
      }
      el.srcObject = mediaStream;
      void el.play().catch(() => {});
    };

    attach(false);

    // A recvonly track negotiated while the server has it paused arrives
    // muted (no RTP). When the server resumes sending, the track fires
    // `unmute`, but some browsers keep rendering black on the already-attached
    // `srcObject` until it's reattached — so re-attach on `unmute` to render
    // auto-resumed tracks without a manual pause/resume round-trip.
    const onUnmute = () => attach(true);
    const tracks = mediaStream.getTracks();

    for (const t of tracks) {
      t.addEventListener('unmute', onUnmute);
    }

    return () => {
      for (const t of tracks) {
        t.removeEventListener('unmute', onUnmute);
      }
      el.srcObject = null;
    };
  }, [mediaStream]);

  return (
    <div
      style={{
        position: 'relative',
        background: '#000',
        ...(width !== undefined && { width }),
        ...(height !== undefined && { height }),
        ...style,
      }}
      className={className}
    >
      <video
        ref={videoRef}
        style={{
          width: '100%',
          height: '100%',
          objectFit: videoObjectFit,
          display: videoMediaTrack ? 'block' : 'none',
        }}
        muted={muted}
        playsInline
      />
    </div>
  );
}
