import { Reactor } from '@reactor-team/js-sdk';
import { log } from '../../shared/log';
import { fetchToken } from '../../shared/fetch-token';

// 03 — Pause a track, then resume it.
//
// `pauseTrack()` is transport-level — it tells the runtime to stop
// producing that track, not a local mute — so nothing is generated while
// paused. On video that's visible only as a frozen frame, which is why the
// thing to watch here is the frame rate below, sampled once a second: it
// drops to zero, then comes back.
//
//   export REACTOR_API_KEY=...
//   npm run dev
//
// Docs: https://docs.reactor.inc/concepts/tracks

const MODEL_NAME = 'reactor/helios';
const PROMPT = 'a forest at dawn, sunbeams through the canopy';
const OUTPUT_TRACK = 'main_video';

const statusEl = document.querySelector<HTMLParagraphElement>('#status')!;
const videoEl = document.querySelector<HTMLVideoElement>('video')!;
const connectButton = document.querySelector<HTMLButtonElement>('#connect')!;
const pauseButton = document.querySelector<HTMLButtonElement>('#pause')!;
const resumeButton = document.querySelector<HTMLButtonElement>('#resume')!;
const fpsEl = document.querySelector<HTMLParagraphElement>('#fps')!;

const reactor = new Reactor({ modelName: MODEL_NAME });

let framesThisSecond = 0;
let fpsTimer: ReturnType<typeof setInterval> | undefined;

function startFpsCounter(video: HTMLVideoElement): void {
  if (!('requestVideoFrameCallback' in video)) {
    return; // Safari/Firefox as of this writing — the readout just stays quiet.
  }
  const onFrame = () => {
    framesThisSecond++;
    video.requestVideoFrameCallback(onFrame);
  };

  video.requestVideoFrameCallback(onFrame);
  fpsTimer = setInterval(() => {
    fpsEl.textContent = `${framesThisSecond} fps`;
    log(`frame rate: ${framesThisSecond}/s`);
    framesThisSecond = 0;
  }, 1000);
}

function stopFpsCounter(): void {
  if (fpsTimer !== undefined) {
    clearInterval(fpsTimer);
  }
  fpsTimer = undefined;
  framesThisSecond = 0;
  fpsEl.textContent = '—';
}

reactor.on('statusChanged', (status) => {
  statusEl.textContent = status;
  statusEl.dataset.status = status;
  connectButton.textContent = status === 'disconnected' ? 'Connect' : 'Disconnect';
  pauseButton.disabled = status !== 'ready';
  resumeButton.disabled = status !== 'ready';
  if (status === 'disconnected') {
    stopFpsCounter();
  }
  log(`status: ${status}`);
});

reactor.on('trackReceived', (name, track) => {
  log(`track received: ${name}`);
  if (name === OUTPUT_TRACK) {
    videoEl.srcObject = new MediaStream([track]);
    startFpsCounter(videoEl);
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
  await reactor.connect(await fetchToken());
  log(`session ${reactor.getSessionId() ?? '?'} is ready`);
  await reactor.sendCommand('set_prompt', { prompt: PROMPT });
  await reactor.sendCommand('start');
});

pauseButton.addEventListener('click', async () => {
  // Transport-level, and separate from any `pause` command a model exposes.
  await reactor.pauseTrack(OUTPUT_TRACK);
  log(`paused ${OUTPUT_TRACK}`);
});

resumeButton.addEventListener('click', async () => {
  await reactor.resumeTrack(OUTPUT_TRACK);
  log(`resumed ${OUTPUT_TRACK}`);
});
