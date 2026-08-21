import { useEffect, useRef, useState } from 'react';
import type { CSSProperties, VideoHTMLAttributes } from 'react';
import { useReactor } from './hooks';
import type { ReactorError } from '../errors';

export interface WebcamStreamProps {
  /** Name of the sendonly video track to publish the webcam to. Must match a
   *  track name declared in the model's capabilities. */
  track: string;
  /** Capture and publish the microphone alongside the webcam. `true` for
   *  default constraints, or explicit `MediaTrackConstraints`. Requires
   *  `audioTrack`. Defaults to `false`. */
  audio?: boolean | MediaTrackConstraints;
  /** Name of the sendonly audio track to publish the mic to. Ignored unless
   *  `audio` is set. */
  audioTrack?: string;
  className?: string;
  style?: CSSProperties;
  videoConstraints?: MediaTrackConstraints;
  showWebcam?: boolean;
  videoObjectFit?: NonNullable<VideoHTMLAttributes<HTMLVideoElement>['style']>['objectFit'];
  /** Fires once `getUserMedia` is rejected with `NotAllowedError` or
   *  `PermissionDeniedError`. */
  onPermissionDenied?: () => void;
  /** Fires after the local media has been published (video, and audio when
   *  `audio` is enabled). Re-fires after a reconnect. */
  onPublished?: () => void;
  /** Fires on non-permission `getUserMedia` failures and on publish/unpublish
   *  rejections. Permission denials route to `onPermissionDenied` instead. */
  onError?: (error: Error) => void;
}

const DEFAULT_VIDEO_CONSTRAINTS: MediaTrackConstraints = {
  width: { ideal: 1280 },
  height: { ideal: 720 },
};

export function WebcamStream({
  track,
  audio = false,
  audioTrack,
  className,
  style,
  videoConstraints = DEFAULT_VIDEO_CONSTRAINTS,
  showWebcam = true,
  videoObjectFit = 'contain',
  onPermissionDenied,
  onPublished,
  onError,
}: WebcamStreamProps) {
  const [stream, setStream] = useState<MediaStream | null>(null);
  const [isPublishing, setIsPublishing] = useState(false);
  const [permissionDenied, setPermissionDenied] = useState(false);

  const { status, publish, unpublish, reactor } = useReactor((state) => ({
    status: state.status,
    publish: state.publish,
    unpublish: state.unpublish,
    reactor: state.internal.reactor,
  }));

  const videoRef = useRef<HTMLVideoElement>(null);

  // Held in refs so inline callback identity doesn't churn the
  // publish/unpublish effect below on every parent render.
  const onPermissionDeniedRef = useRef(onPermissionDenied);
  const onPublishedRef = useRef(onPublished);
  const onErrorRef = useRef(onError);

  onPermissionDeniedRef.current = onPermissionDenied;
  onPublishedRef.current = onPublished;
  onErrorRef.current = onError;

  // Without an `audioTrack` the captured mic has nowhere to publish, so
  // capture stays video-only.
  const audioRequested = audio !== false;
  const audioEnabled = audioRequested && audioTrack !== undefined;

  // The mount/unmount effect below only runs once (empty deps), so its
  // cleanup would otherwise close over the props/state from the initial
  // render — mirror them here so teardown unpublishes the right track names
  // and stops the right stream even if they changed since mount.
  const latestRef = useRef({ track, audioTrack, audioEnabled, unpublish, stream });

  latestRef.current = { track, audioTrack, audioEnabled, unpublish, stream };

  useEffect(() => {
    let cancelled = false;

    navigator.mediaDevices
      .getUserMedia({
        video: videoConstraints,
        audio: audioEnabled ? audio : false,
      })
      .then((mediaStream) => {
        if (cancelled) {
          for (const t of mediaStream.getTracks()) {
            t.stop();
          }
          return;
        }
        setStream(mediaStream);
        setPermissionDenied(false);
      })
      .catch((err: unknown) => {
        if (cancelled) {
          return;
        }

        if (err instanceof DOMException && (err.name === 'NotAllowedError' || err.name === 'PermissionDeniedError')) {
          setPermissionDenied(true);
          onPermissionDeniedRef.current?.();
        } else {
          onErrorRef.current?.(err instanceof Error ? err : new Error(String(err)));
        }
      });

    return () => {
      cancelled = true;

      const current = latestRef.current;
      // Unpublish failures don't block local-track teardown — leaving
      // tracks running keeps the camera/mic indicator on after unmount.
      const tasks: Array<Promise<void>> = [current.unpublish(current.track)];

      if (current.audioEnabled && current.audioTrack) {
        tasks.push(current.unpublish(current.audioTrack));
      }
      void Promise.allSettled(tasks).then((results) => {
        for (const r of results) {
          if (r.status === 'rejected') {
            onErrorRef.current?.(r.reason instanceof Error ? r.reason : new Error(String(r.reason)));
          }
        }
      });

      current.stream?.getTracks().forEach((t) => t.stop());
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    const el = videoRef.current;

    if (!el) {
      return;
    }
    el.srcObject = stream;
  }, [stream]);

  useEffect(() => {
    if (!stream) {
      return;
    }

    if (status === 'ready' && !isPublishing) {
      const videoMediaTrack = stream.getVideoTracks()[0];
      const audioMediaTrack = audioEnabled ? stream.getAudioTracks()[0] : undefined;
      const tasks: Array<Promise<void>> = [];

      if (videoMediaTrack) {
        tasks.push(publish(track, videoMediaTrack));
      }
      if (audioMediaTrack && audioTrack) {
        tasks.push(publish(audioTrack, audioMediaTrack));
      }
      if (tasks.length === 0) {
        return;
      }

      Promise.all(tasks)
        .then(() => {
          setIsPublishing(true);
          onPublishedRef.current?.();
        })
        .catch((err: unknown) => {
          onErrorRef.current?.(err instanceof Error ? err : new Error(String(err)));
        });
    } else if (status !== 'ready' && isPublishing) {
      const tasks: Array<Promise<void>> = [unpublish(track)];

      if (audioEnabled && audioTrack) {
        tasks.push(unpublish(audioTrack));
      }

      void Promise.allSettled(tasks).then((results) => {
        for (const r of results) {
          if (r.status === 'rejected') {
            onErrorRef.current?.(r.reason instanceof Error ? r.reason : new Error(String(r.reason)));
          }
        }
        setIsPublishing(false);
      });
    }
  }, [status, stream, isPublishing, publish, unpublish, track, audioEnabled, audioTrack]);

  useEffect(() => {
    const handleError = (error: ReactorError) => {
      if (error.code === 'TRACK_PUBLISH_FAILED') {
        setIsPublishing(false);
      }
    };

    reactor.on('error', handleError);
    return () => reactor.off('error', handleError);
  }, [reactor]);

  const showPlaceholder = !stream;

  return (
    <div
      style={{
        display: showWebcam ? 'block' : 'none',
        position: 'relative',
        background: '#000',
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
          display: showPlaceholder ? 'none' : 'block',
        }}
        muted
        playsInline
        autoPlay
      />
      {showPlaceholder && (
        <div
          style={{
            position: 'absolute',
            top: 0,
            left: 0,
            width: '100%',
            height: '100%',
            color: '#fff',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            fontSize: '16px',
            fontFamily: 'monospace',
            textAlign: 'center',
            padding: '20px',
            boxSizing: 'border-box',
            flexDirection: 'column',
            gap: '12px',
          }}
        >
          {permissionDenied ? (
            <div style={{ fontSize: '12px', fontFamily: 'monospace' }}>
              Camera access denied.
              <br />
              Please allow access in your browser settings.
            </div>
          ) : (
            <div style={{ fontSize: '12px', fontFamily: 'monospace' }}>Starting camera...</div>
          )}
        </div>
      )}
    </div>
  );
}
