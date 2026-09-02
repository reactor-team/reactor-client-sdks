// Synthetic, deterministic media sources — no `getUserMedia`, so assertions
// on what comes back don't depend on a browser's fake-device pattern (which
// differs across Chromium versions and isn't something this suite controls).

/** A solid-color video track via `canvas.captureStream()`. Fixed, saturated
 *  colors make server-side effects (grayscale, invert, ...) cheap to verify
 *  by sampling a pixel back out of the received track. */
export function makeVideoTrack(color = '#ff2222', width = 320, height = 240): MediaStreamTrack {
  const canvas = document.createElement('canvas');

  canvas.width = width;
  canvas.height = height;
  const ctx = canvas.getContext('2d')!;

  ctx.fillStyle = color;
  ctx.fillRect(0, 0, width, height);

  // A static canvas emits no frames at all under `captureStream()` — redraw
  // every tick (even to the same pixels) so the track actually carries video.
  let stop = false;
  const redraw = () => {
    if (stop) {return;}
    ctx.fillStyle = color;
    ctx.fillRect(0, 0, width, height);
    requestAnimationFrame(redraw);
  };

  redraw();

  const stream = canvas.captureStream(30);
  const [track] = stream.getVideoTracks();
  const originalStop = track.stop.bind(track);

  track.stop = () => {
    stop = true;
    originalStop();
  };
  return track;
}

/** A steady tone via `AudioContext`, as a `MediaStreamTrack`. */
export function makeAudioTrack(frequencyHz = 440): MediaStreamTrack {
  const ctx = new AudioContext();
  const osc = ctx.createOscillator();

  osc.frequency.value = frequencyHz;
  const dest = ctx.createMediaStreamDestination();

  osc.connect(dest);
  osc.start();
  return dest.stream.getAudioTracks()[0];
}

/** Renders a `MediaStreamTrack` into an off-DOM `<video>` and samples the
 *  center pixel once a frame has actually decoded. Used to verify a model's
 *  video effect landed on the track this SDK handed back. */
export async function samplePixel(
  track: MediaStreamTrack,
): Promise<{ r: number; g: number; b: number }> {
  const video = document.createElement('video');

  video.muted = true;
  video.playsInline = true;
  video.srcObject = new MediaStream([track]);
  document.body.appendChild(video);
  await video.play();

  await new Promise<void>((resolve) => {
    if ('requestVideoFrameCallback' in video) {
      (video as HTMLVideoElement & { requestVideoFrameCallback: (cb: () => void) => void })
        .requestVideoFrameCallback(() => resolve());
    } else {
      video.addEventListener('timeupdate', () => resolve(), { once: true });
    }
  });

  const canvas = document.createElement('canvas');

  canvas.width = video.videoWidth || 1;
  canvas.height = video.videoHeight || 1;
  const ctx = canvas.getContext('2d')!;

  ctx.drawImage(video, 0, 0);
  const [r, g, b] = ctx.getImageData(canvas.width >> 1, canvas.height >> 1, 1, 1).data;

  video.remove();
  return { r, g, b };
}

/** A tiny solid-color PNG, built at runtime (no binary fixture to keep in
 *  the repo) — for `uploadFile()` / file-taking commands. */
export function makeTestImageFile(name = 'overlay.png'): File {
  const canvas = document.createElement('canvas');

  canvas.width = 32;
  canvas.height = 32;
  const ctx = canvas.getContext('2d')!;

  ctx.fillStyle = '#2222ff';
  ctx.fillRect(0, 0, 32, 32);
  const dataUrl = canvas.toDataURL('image/png');
  const bytes = atob(dataUrl.split(',')[1]);
  const arr = new Uint8Array(bytes.length);

  for (let i = 0; i < bytes.length; i++) {arr[i] = bytes.charCodeAt(i);}
  return new File([arr], name, { type: 'image/png' });
}
