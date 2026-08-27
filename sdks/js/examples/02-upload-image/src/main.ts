import { Reactor } from '@reactor-team/js-sdk';
import { log } from '../../shared/log';
import { fetchToken } from '../../shared/fetch-token';

// 02 — Upload a file, then pass the reference into a command.
//
// The bytes cross the wire once: `uploadFile()` hands them to the platform
// and resolves with a `FileRef`; the command that follows carries the
// *reference*, not the bytes again. Helios takes a prompt and an image
// together through `set_conditioning`, so the two arrive as one atomic
// change rather than as two the model has to reconcile — `start` never
// observes a session with only one of them set.
//
//   export REACTOR_API_KEY=...
//   npm run dev
//
// Docs: https://docs.reactor.inc/concepts/file-uploads
//       https://docs.reactor.inc/model-api-reference/helios/schema

const MODEL_NAME = 'reactor/helios';
const PROMPT = 'the same scene at night, lit by a campfire';

const statusEl = document.querySelector<HTMLParagraphElement>('#status')!;
const videoEl = document.querySelector<HTMLVideoElement>('video')!;
const fileInput = document.querySelector<HTMLInputElement>('#file')!;
const button = document.querySelector<HTMLButtonElement>('#go')!;

const reactor = new Reactor({ modelName: MODEL_NAME });

function updateButton(): void {
  button.disabled = reactor.getStatus() === 'disconnected' && !fileInput.files?.length;
}

reactor.on('statusChanged', (status) => {
  statusEl.textContent = status;
  statusEl.dataset.status = status; // drives the status dot's color, in CSS
  fileInput.disabled = status !== 'disconnected';
  button.textContent = status === 'disconnected' ? 'Connect & Send' : 'Disconnect';
  log(`status: ${status}`);
  updateButton();
});

// A refused upload arrives as a `command_error` message, not as a failed call.
reactor.on('message', (message) => {
  log(`message: ${JSON.stringify(message)}`);
});

reactor.on('trackReceived', (name, track) => {
  log(`track received: ${name}`);
  if (name === 'main_video') {
    videoEl.srcObject = new MediaStream([track]);
  }
});

reactor.on('error', (error) => {
  log(`error: ${error.message}`);
});

fileInput.addEventListener('change', updateButton);
updateButton();

button.addEventListener('click', async () => {
  if (reactor.getStatus() !== 'disconnected') {
    await reactor.disconnect();
    log('disconnected');
    return;
  }

  const file = fileInput.files?.[0];

  if (!file) {
    return;
  }

  log(`connecting to ${MODEL_NAME}...`);
  await reactor.connect(await fetchToken());
  log(`session ${reactor.getSessionId() ?? '?'} is ready`);

  // Needs a ready session: the bytes go to that session's own object store.
  // Name and MIME type are inferred from the `File`.
  log(`uploading ${file.name} (${file.size} bytes)...`);
  const uploaded = await reactor.uploadFile(file);

  log(`uploaded: ${uploaded.name} ${uploaded.mimeType} (${uploaded.size} bytes)`);

  log(`sending set_conditioning: "${PROMPT}"`);
  await reactor.sendCommand('set_conditioning', { prompt: PROMPT, image: uploaded });
  log('sending start');
  await reactor.sendCommand('start');
});
