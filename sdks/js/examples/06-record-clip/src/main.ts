import {
  DEFAULT_PLAYLIST_POLL_SLACK_MS,
  Reactor,
  downloadClipAsFile,
  fetchPlaylist,
} from '@reactor-team/js-sdk';

// 06 — Ask for a clip, then download it.
//
// `requestClip()` returns as soon as the platform accepts the request, not
// once the manifest is fetchable — `downloadClipAsFile()` is what waits and
// remuxes the chunks into one MP4.
//
//   export REACTOR_API_KEY=...
//   npm run dev
//
// Docs: https://docs.reactor.inc/concepts/recordings

const MODEL_NAME = 'reactor/helios';
const PROMPT = 'a forest at dawn, sunbeams through the canopy';
const OUTPUT_TRACK = 'main_video';

const statusEl = document.querySelector<HTMLParagraphElement>('#status')!;
const videoEl = document.querySelector<HTMLVideoElement>('video')!;
const connectButton = document.querySelector<HTMLButtonElement>('#connect')!;
const secondsInput = document.querySelector<HTMLInputElement>('#seconds')!;
const recordButton = document.querySelector<HTMLButtonElement>('#record')!;
const logEl = document.querySelector<HTMLPreElement>('#log')!;

function log(line: string): void {
  const time = new Date().toLocaleTimeString();

  logEl.textContent += `[${time}] ${line}\n`;
  logEl.scrollTop = logEl.scrollHeight;
}

async function fetchToken(): Promise<string> {
  const r = await fetch('/api/token');

  if (!r.ok) {
    throw new Error(`token fetch failed: ${r.status}`);
  }
  const { jwt } = (await r.json()) as { jwt: string };

  return jwt;
}

const reactor = new Reactor({ modelName: MODEL_NAME, jwt: fetchToken });
let frameCount = 0;
// Cancels an in-flight download so a disconnect doesn't leave it polling forever.
let downloadController: AbortController | undefined;

reactor.on('statusChanged', (status) => {
  statusEl.textContent = status;
  statusEl.dataset.status = status;
  connectButton.textContent = status === 'disconnected' ? 'Connect' : 'Disconnect';
  if (status === 'disconnected') {
    recordButton.disabled = true;
    frameCount = 0;
    downloadController?.abort();
  }
  log(`status: ${status}`);
});

reactor.on('message', (message) => {
  log(`message: ${JSON.stringify(message)}`);
});

reactor.on('trackReceived', (name, track) => {
  log(`track received: ${name}`);
  if (name !== OUTPUT_TRACK) {
    return;
  }
  videoEl.srcObject = new MediaStream([track]);

  // Nothing to cut until a frame has actually been fed to the recorder.
  const armRecordButton = () => {
    frameCount++;
    if (frameCount === 1) {
      recordButton.disabled = false;
    }
  };

  const hasFrameCallback = typeof (videoEl as { requestVideoFrameCallback?: unknown }).requestVideoFrameCallback === 'function';

  if (hasFrameCallback) {
    const onFrame = () => {
      armRecordButton();
      videoEl.requestVideoFrameCallback(onFrame);
    };

    videoEl.requestVideoFrameCallback(onFrame);
  } else {
    // Safari/Firefox as of this writing — no per-frame callback, so the
    // first 'timeupdate' is the readiness signal instead.
    videoEl.addEventListener('timeupdate', armRecordButton, { once: true });
  }
});

reactor.on('error', (error) => {
  log(`error: ${error.message}`);
});

connectButton.addEventListener('click', async () => {
  if (reactor.getStatus() !== 'disconnected') {
    await reactor.disconnect();
    log('disconnected');
    return;
  }

  log(`connecting to ${MODEL_NAME}...`);
  await reactor.connect();
  log(`session ${reactor.getSessionId() ?? '?'} is ready`);
  await reactor.sendCommand('set_prompt', { prompt: PROMPT });
  await reactor.sendCommand('start');
});

recordButton.addEventListener('click', async () => {
  recordButton.disabled = true;
  const seconds = Number(secondsInput.value) || 5;

  try {
    log(`requesting the last ${seconds}s...`);
    const clip = await reactor.requestClip(seconds);

    log(`clip: ${clip.kind}, window ${clip.startMarker.toFixed(1)} -> ${clip.endMarker.toFixed(1)}s (now ${clip.nowMarker.toFixed(1)}s)`);
    if (clip.endMarker - clip.startMarker < seconds * 0.5) {
      log('warning: the session has less video than the window asked for');
    }

    const jwt = await fetchToken();

    downloadController = new AbortController();
    // Bounded first pass — downloadClipAsFile() itself polls with no limit.
    await fetchPlaylist(clip.playlistUrl, {
      predictedReadyAtMs: clip.predictedReadyAtMs,
      slackMs: DEFAULT_PLAYLIST_POLL_SLACK_MS,
      signal: downloadController.signal,
      jwt,
    });
    await downloadClipAsFile(clip, 'reactor-clip.mp4', {
      jwt,
      signal: downloadController.signal,
      onProgress: ({ fetched, total }) => log(`fetched chunk ${fetched}/${total}`),
    });
    log('saved: reactor-clip.mp4');
  } catch (error) {
    // These throw rather than going through the 'error' event.
    log(`error: ${error instanceof Error ? error.message : String(error)}`);
  } finally {
    downloadController = undefined;
    recordButton.disabled = false;
  }
});
