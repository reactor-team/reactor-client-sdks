import {
  DEFAULT_PLAYLIST_POLL_SLACK_MS,
  Reactor,
  downloadClipAsFile,
  fetchPlaylist,
} from '@reactor-team/js-sdk';

// 06 — Ask for a clip, then download it.
//
// `requestClip()` asks for the last N seconds and returns as soon as the
// platform accepts the request — it does not block until the manifest is
// actually fetchable. `downloadClipAsFile()` is what waits: it polls past
// `clip.predictedReadyAtMs` until the manifest is ready, fetches the chunks
// it references, and remuxes them into one downloadable MP4.
//
// Readiness is in *media* time, not wall clock: a snap clip's window ends
// at "now", so its boundary chunk is always the one still open — and it
// only closes because the model keeps generating. That's why the wait has
// no fixed deadline here; `requestRecording()` is the same call for the
// whole session.
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
// Cancels an in-flight recordButton click's poll/download — otherwise a
// disconnect while one is waiting leaves it polling a session that will
// never produce another chunk.
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

// Where a session end announces itself, among the runtime's other traffic —
// worth having in the log when a clip never becomes ready.
reactor.on('message', (message) => {
  log(`message: ${JSON.stringify(message)}`);
});

reactor.on('trackReceived', (name, track) => {
  log(`track received: ${name}`);
  if (name !== OUTPUT_TRACK) {
    return;
  }
  videoEl.srcObject = new MediaStream([track]);
  if (!('requestVideoFrameCallback' in videoEl)) {
    return; // Safari/Firefox as of this writing — the recorder still works.
  }
  const onFrame = () => {
    frameCount++;
    // The recorder has nothing to cut until a frame has actually been fed
    // to it; asking before that fails with "no media generated yet".
    if (frameCount === 1) {
      recordButton.disabled = false;
    }
    videoEl.requestVideoFrameCallback(onFrame);
  };

  videoEl.requestVideoFrameCallback(onFrame);
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

    const deadline = Math.max(clip.predictedReadyAtMs, Date.now()) + DEFAULT_PLAYLIST_POLL_SLACK_MS;

    log(
      'waiting for the recorder to pass the end of the window... ' +
        `(runtime predicts ready at ${new Date(clip.predictedReadyAtMs).toLocaleTimeString()}, ` +
        `giving up at ${new Date(deadline).toLocaleTimeString()} if it isn't)`,
    );
    const jwt = await fetchToken();

    downloadController = new AbortController();
    // `downloadClipAsFile()` polls its manifest with no bound of its own —
    // fine for a clip that's genuinely seconds away, but a boundary chunk
    // that never closes (session dropped, model stopped generating) would
    // otherwise retry every 200ms–2s forever. Gating on a bounded
    // `fetchPlaylist()` first turns that into one clear error instead.
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
    // requestClip()/downloadClipAsFile() throw rather than going through the
    // `error` event — without this, a rejection here is just an unhandled
    // promise rejection in devtools, invisible in the on-page log.
    log(`error: ${error instanceof Error ? error.message : String(error)}`);
  } finally {
    downloadController = undefined;
    recordButton.disabled = false;
  }
});
