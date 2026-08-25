import { Reactor } from '@reactor-team/js-sdk';

// 01 — Connect, send the model's first command, receive frames. The spine
// every other example builds on.
//
// "The minimum it needs" is per model and not optional. Helios stays silent
// until `set_prompt` and then `start` — its own schema is where that's
// written down, and the first place to look when nothing arrives.
//
//   export REACTOR_API_KEY=...
//   npm run dev
//
// Docs: https://docs.reactor.inc/model-api-reference/helios/schema

// `owner/name`, always. A bare name resolves under `reactor/`, so it works by
// luck of ownership and answers 403 for anyone else's model.
const MODEL_NAME = 'reactor/helios';
const PROMPT = 'a red fox in tall grass, cinematic';

const statusEl = document.querySelector<HTMLParagraphElement>('#status')!;
const videoEl = document.querySelector<HTMLVideoElement>('video')!;
const button = document.querySelector<HTMLButtonElement>('#connect')!;
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

reactor.on('statusChanged', (status) => {
  statusEl.textContent = status;
  statusEl.dataset.status = status; // drives the status dot's color, in CSS
  button.textContent = status === 'disconnected' ? 'Connect' : 'Disconnect';
  log(`status: ${status}`);
});

let frameCount = 0;

// A count proves something arrived, not that it's the right something —
// same caveat the cpp/python examples print alongside theirs.
function countFrames(video: HTMLVideoElement): void {
  frameCount = 0;
  if (!('requestVideoFrameCallback' in video)) {
    return; // Safari/Firefox as of this writing — the log just stays quiet.
  }
  const onFrame = (_now: number, metadata: VideoFrameCallbackMetadata) => {
    frameCount++;
    if (frameCount === 1) {
      log(`first frame: ${metadata.width}x${metadata.height}`);
    }
    video.requestVideoFrameCallback(onFrame);
  };

  video.requestVideoFrameCallback(onFrame);
}

reactor.on('trackReceived', (name, track) => {
  log(`track received: ${name}`);
  if (name === 'main_video') {
    videoEl.srcObject = new MediaStream([track]);
    countFrames(videoEl);
  }
});

reactor.on('error', (error) => {
  statusEl.textContent = `error: ${error.message}`;
  log(`error: ${error.message}`);
});

button.addEventListener('click', async () => {
  if (reactor.getStatus() !== 'disconnected') {
    await reactor.disconnect();
    log(`frames received: ${frameCount}`);
    log('disconnected');
    return;
  }

  log(`connecting to ${MODEL_NAME}...`);
  await reactor.connect();
  log(`session ${reactor.getSessionId() ?? '?'} is ready`);

  // Helios' own minimum, in its own order.
  log(`sending set_prompt: "${PROMPT}"`);
  await reactor.sendCommand('set_prompt', { prompt: PROMPT });
  log('sending start');
  await reactor.sendCommand('start');
});
