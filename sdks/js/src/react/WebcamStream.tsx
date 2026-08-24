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
  /** Read once, at mount — changing this after mount doesn't re-request
   *  `getUserMedia`. Matches v2. */
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
  const [published, setPublished] = useState<{ video: string; audio?: string } | null>(null);
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
  // render — mirror them here so teardown unpublishes whatever is actually
  // published and stops the right stream even if they changed since mount.
  //
  // Same empty-deps effect also means `videoConstraints`/`audio`/`audioTrack`
  // are only read once, at mount — changing them later doesn't re-request
  // getUserMedia. Matches v2's WebcamStream, which has the same limitation.
  const latestRef = useRef({ published, unpublish, stream });

  latestRef.current = { published, unpublish, stream };

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
      const tasks: Array<Promise<void>> = [];

      if (current.published) {
        tasks.push(current.unpublish(current.published.video));

        if (current.published.audio) {
          tasks.push(current.unpublish(current.published.audio));
        }
      }

      if (tasks.length > 0) {
        void Promise.allSettled(tasks).then((results) => {
          for (const r of results) {
            if (r.status === 'rejected') {
              onErrorRef.current?.(r.reason instanceof Error ? r.reason : new Error(String(r.reason)));
            }
          }
        });
      }

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

    let cancelled = false;
    const desiredAudio = audioEnabled ? audioTrack : undefined;
    const activeStream = stream;

    async function sync() {
      if (status === 'ready') {
        if (published && published.video === track && published.audio === desiredAudio) {
          return;
        }

        // Names changed while already published: drop the stale
        // publication before publishing under the new ones.
        if (published) {
          const rollback: Array<Promise<void>> = [unpublish(published.video)];

          if (published.audio) {
            rollback.push(unpublish(published.audio));
          }
          await Promise.allSettled(rollback);

          if (cancelled) {
            return;
          }
          setPublished(null);
        }

        const videoMediaTrack = activeStream.getVideoTracks()[0];
        const audioMediaTrack = desiredAudio ? activeStream.getAudioTracks()[0] : undefined;
        const attempts: Array<{ name: string; task: Promise<void> }> = [];

        if (videoMediaTrack) {
          attempts.push({ name: track, task: publish(track, videoMediaTrack) });
        }
        if (audioMediaTrack && desiredAudio) {
          attempts.push({ name: desiredAudio, task: publish(desiredAudio, audioMediaTrack) });
        }
        if (attempts.length === 0) {
          return;
        }

        const results = await Promise.allSettled(attempts.map((a) => a.task));

        if (cancelled) {
          return;
        }

        const failureIndex = results.findIndex((r) => r.status === 'rejected');

        if (failureIndex !== -1) {
          // Roll back whatever did succeed so we never report an error
          // while leaving some names published behind our backs.
          const succeeded = attempts.filter((_, i) => results[i]?.status === 'fulfilled');

          await Promise.allSettled(succeeded.map((a) => unpublish(a.name)));

          if (cancelled) {
            return;
          }

          const failure = results[failureIndex] as PromiseRejectedResult;

          onErrorRef.current?.(failure.reason instanceof Error ? failure.reason : new Error(String(failure.reason)));
          return;
        }

        const newPublished: { video: string; audio?: string } = { video: track };

        if (audioMediaTrack && desiredAudio) {
          newPublished.audio = desiredAudio;
        }
        setPublished(newPublished);
        onPublishedRef.current?.();
      } else if (published) {
        const tasks: Array<Promise<void>> = [unpublish(published.video)];

        if (published.audio) {
          tasks.push(unpublish(published.audio));
        }

        const results = await Promise.allSettled(tasks);

        if (cancelled) {
          return;
        }

        for (const r of results) {
          if (r.status === 'rejected') {
            onErrorRef.current?.(r.reason instanceof Error ? r.reason : new Error(String(r.reason)));
          }
        }
        setPublished(null);
      }
    }

    void sync();

    return () => {
      cancelled = true;
    };
  }, [status, stream, published, publish, unpublish, track, audioEnabled, audioTrack]);

  // A rejected publish() is already handled inline in sync() above (awaited,
  // then routed to onError). This listener is for TRACK_PUBLISH_FAILED
  // reported out-of-band by the runtime *after* publish() already resolved —
  // e.g. the track was accepted but later failed to actually flow. Resetting
  // `published` here makes the sync effect re-run and republish.
  useEffect(() => {
    const handleError = (error: ReactorError) => {
      if (error.code === 'TRACK_PUBLISH_FAILED') {
        setPublished(null);
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
