import { Reactor } from '@reactor-team/js-sdk';

// 05 — Two clients, one session.
//
// The first client creates the session; the second joins it by id — the id
// is the whole handoff, no coordination beyond it. Only the creator's
// disconnect ends the session server-side, which is what makes joining
// safe from a tab that may close at any moment.
//
// Teardown order is the lesson: the joiner disconnects first, then the
// creator. A creator that disappears without disconnecting leaves the
// session orphaned server-side.
//
//   export REACTOR_API_KEY=...
//   npm run dev
//
// Docs: https://docs.reactor.inc/concepts/sessions#multiple-connections-per-session
//       https://docs.reactor.inc/concepts/sessions#adopting-an-existing-session

const MODEL_NAME = 'reactor/helios';
const PROMPT = 'a forest at dawn, sunbeams through the canopy';
const OUTPUT_TRACK = 'main_video';

const statusEl = document.querySelector<HTMLParagraphElement>('#status')!;
const creatorVideoEl = document.querySelector<HTMLVideoElement>('#creator')!;
const joinerVideoEl = document.querySelector<HTMLVideoElement>('#joiner')!;
const button = document.querySelector<HTMLButtonElement>('#go')!;
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

function client(label: string, video: HTMLVideoElement): Reactor {
  const reactor = new Reactor({ modelName: MODEL_NAME, jwt: fetchToken });

  reactor.on('statusChanged', (status) => log(`[${label}] status: ${status}`));
  reactor.on('error', (error) => log(`[${label}] error: ${error.message}`));
  reactor.on('trackReceived', (name, track) => {
    log(`[${label}] track received: ${name}`);
    if (name === OUTPUT_TRACK) {
      video.srcObject = new MediaStream([track]);
    }
  });
  return reactor;
}

const creator = client('creator', creatorVideoEl);
const joiner = client('joiner', joinerVideoEl);
let connected = false;

button.addEventListener('click', async () => {
  button.disabled = true;

  if (connected) {
    // The joiner only watches — disconnect it first, then the creator,
    // whose own disconnect is what ends the session server-side.
    await joiner.disconnect();
    log('joiner disconnected');
    await creator.disconnect();
    log('creator disconnected; session ended');

    connected = false;
    statusEl.textContent = 'disconnected';
    button.textContent = 'Connect both';
    button.disabled = false;
    return;
  }

  log(`connecting creator to ${MODEL_NAME}...`);
  await creator.connect();
  const sessionId = creator.getSessionId();

  statusEl.textContent = `session: ${sessionId ?? '?'}`;
  log(`session: ${sessionId ?? '?'}`);

  await creator.sendCommand('set_prompt', { prompt: PROMPT });
  await creator.sendCommand('start');

  log('joiner adopting the same session...');
  // The id is the whole handoff — no second session, no coordination.
  await joiner.connect(undefined, { sessionId });
  log('joiner is ready');

  connected = true;
  button.textContent = 'Disconnect both';
  button.disabled = false;
});
