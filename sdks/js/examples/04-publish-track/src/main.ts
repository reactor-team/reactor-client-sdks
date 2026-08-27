import { Reactor } from '@reactor-team/js-sdk';
import { log } from '../../shared/log';
import { fetchToken } from '../../shared/fetch-token';

// 04 — Publish an input track, and see it edited.
//
// X2 edits whatever you stream at it: publish its `source` track and the
// re-rendered result comes back on `main_video`. Publishing is what puts a
// sender behind the slot — pushing media before it is published raises —
// so it always comes before the prompt that starts generation. Unlike
// Helios, X2 has no `start`: it begins as soon as it has both a prompt and
// frames.
//
// The webcam stands in for whatever real input an app would publish; the
// point is the publish-then-prompt order, not the source of the frames.
//
//   export REACTOR_API_KEY=...
//   npm run dev
//
// Docs: https://docs.reactor.inc/concepts/tracks#input-tracks-app-to-model
//       https://docs.reactor.inc/model-api-reference/x2/schema

const MODEL_NAME = 'xmax/x2';
const PROMPT = 'repaint the scene as a watercolour painting';
const INPUT_TRACK = 'source';
const OUTPUT_TRACK = 'main_video';

const statusEl = document.querySelector<HTMLParagraphElement>('#status')!;
const localVideoEl = document.querySelector<HTMLVideoElement>('#local')!;
const remoteVideoEl = document.querySelector<HTMLVideoElement>('#remote')!;
const button = document.querySelector<HTMLButtonElement>('#go')!;

const reactor = new Reactor({ modelName: MODEL_NAME });
let localStream: MediaStream | undefined;

reactor.on('statusChanged', (status) => {
  statusEl.textContent = status;
  statusEl.dataset.status = status;
  button.textContent = status === 'disconnected' ? 'Connect & Publish' : 'Disconnect';
  log(`status: ${status}`);
});

reactor.on('message', (message) => {
  log(`message: ${JSON.stringify(message)}`);
});

reactor.on('trackReceived', (name, track) => {
  log(`track received: ${name}`);
  if (name === OUTPUT_TRACK) {
    remoteVideoEl.srcObject = new MediaStream([track]);
  }
});

reactor.on('error', (error) => {
  log(`error: ${error.message}`);
});

button.addEventListener('click', async () => {
  if (reactor.getStatus() !== 'disconnected') {
    await reactor.unpublishTrack(INPUT_TRACK);
    await reactor.disconnect();
    localStream?.getTracks().forEach((track) => track.stop());
    localStream = undefined;
    localVideoEl.srcObject = null;
    log('disconnected');
    return;
  }

  log(`connecting to ${MODEL_NAME}...`);
  await reactor.connect(await fetchToken());
  log(`session ${reactor.getSessionId() ?? '?'} is ready`);

  log('requesting the webcam...');
  localStream = await navigator.mediaDevices.getUserMedia({ video: true });
  localVideoEl.srcObject = localStream;
  const [track] = localStream.getVideoTracks();

  if (!track) {
    throw new Error('no camera track');
  }

  // Publish first: a prompt with no sender behind the slot buys nothing,
  // and this SDK refuses a push before the slot is published rather than
  // dropping it silently.
  await reactor.publishTrack(INPUT_TRACK, track);
  log(`publishing: ${INPUT_TRACK}`);

  log(`sending set_prompt: "${PROMPT}"`);
  const reply = await reactor.sendCommand('set_prompt', { prompt: PROMPT });

  log(`set_prompt -> ${JSON.stringify(reply)}`);
});
