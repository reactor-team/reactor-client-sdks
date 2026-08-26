import { Reactor } from '@reactor-team/js-sdk';

// 07 — Save a snapshot, list them, rewind to one. `sendCommand()`'s return
// value is the point here: every other example either fire-and-forgets it
// or reads only its bare `type`. `save_snapshot`/`list_snapshots`/`rewind`
// each reply with a `data` payload worth actually looking at.
//
//   export REACTOR_API_KEY=...
//   npm run dev
//
// Docs: https://docs.reactor.inc/concepts/commands-and-messages
//       https://docs.reactor.inc/model-api-reference/helios/schema

const MODEL_NAME = 'reactor/helios';
const PROMPT = 'a red fox in tall grass, cinematic';
const OUTPUT_TRACK = 'main_video';

interface Snapshot {
  snapshot_index: number;
  chunk: number;
  label: string;
  parent_id: number | null;
}

const statusEl = document.querySelector<HTMLParagraphElement>('#status')!;
const videoEl = document.querySelector<HTMLVideoElement>('video')!;
const connectButton = document.querySelector<HTMLButtonElement>('#connect')!;
const labelInput = document.querySelector<HTMLInputElement>('#label')!;
const saveButton = document.querySelector<HTMLButtonElement>('#save')!;
const snapshotsEl = document.querySelector<HTMLDivElement>('#snapshots')!;
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

function renderSnapshots(snapshots: Snapshot[]): void {
  snapshotsEl.replaceChildren(
    ...snapshots.map((snapshot) => {
      const row = document.createElement('div');

      row.className = 'snapshot';

      const dl = document.createElement('dl');

      dl.innerHTML = `<strong>#${snapshot.snapshot_index}</strong> chunk ${snapshot.chunk}${snapshot.label ? ` · ${snapshot.label}` : ''}`;

      const rewindButton = document.createElement('button');

      rewindButton.textContent = 'Rewind';
      rewindButton.addEventListener('click', () => void rewindTo(snapshot.snapshot_index));

      row.append(dl, rewindButton);

      return row;
    }),
  );
}

async function refreshSnapshots(): Promise<void> {
  const reply = await reactor.sendCommand('list_snapshots');

  log(`list_snapshots -> ${JSON.stringify(reply)}`);
  renderSnapshots((reply?.data as { snapshots?: Snapshot[] } | undefined)?.snapshots ?? []);
}

async function rewindTo(snapshotIndex: number): Promise<void> {
  log(`rewinding to snapshot ${snapshotIndex}...`);
  const reply = await reactor.sendCommand('rewind', { snapshot_index: snapshotIndex });

  log(`rewind -> ${JSON.stringify(reply)}`);
}

reactor.on('statusChanged', (status) => {
  statusEl.textContent = status;
  statusEl.dataset.status = status;
  connectButton.textContent = status === 'disconnected' ? 'Connect' : 'Disconnect';
  if (status === 'disconnected') {
    saveButton.disabled = true;
    renderSnapshots([]);
  }
  log(`status: ${status}`);
});

// A broadcast, not a reply — the correlated replies below arrive as each
// sendCommand() call's own resolved value instead. `state` and
// `chunk_complete` fire continuously (a full state snapshot after every
// command *and* every chunk, plus one chunk_complete per chunk) and carry
// nothing this example cares about — logged as just their `type` would
// still bury the replies below in noise, so they're skipped entirely.
const NOISY_MESSAGE_TYPES = new Set(['state', 'chunk_complete']);

reactor.on('message', (message) => {
  if (NOISY_MESSAGE_TYPES.has(message.type)) {
    return;
  }
  log(`message: ${message.type}`);
});

// Refetched automatically on every "ready" transition. Logged here so the
// commands below are checked against what this model *actually* publishes,
// rather than assumed — `paths` lists every client-triggerable command as
// `POST /events/<name>`.
reactor.on('schemaReceived', (schema) => {
  const commands = Object.keys(schema.paths ?? {}).map((path) => path.replace(/^\/events\//, ''));

  log(`model commands: ${commands.join(', ') || '(none)'}`);
});

reactor.on('trackReceived', (name, track) => {
  log(`track received: ${name}`);
  if (name !== OUTPUT_TRACK) {
    return;
  }
  videoEl.srcObject = new MediaStream([track]);

  // `save_snapshot` captures "the current world state" — nothing to save
  // until a frame has actually been generated, the same readiness gate
  // `requestClip()` needs a produced frame for.
  const armSaveButton = () => {
    saveButton.disabled = false;
  };

  if (typeof (videoEl as { requestVideoFrameCallback?: unknown }).requestVideoFrameCallback === 'function') {
    videoEl.requestVideoFrameCallback(armSaveButton);
  } else {
    // Safari/Firefox as of this writing — no per-frame callback, so the
    // first 'timeupdate' is the readiness signal instead.
    videoEl.addEventListener('timeupdate', armSaveButton, { once: true });
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

saveButton.addEventListener('click', async () => {
  const label = labelInput.value.trim();

  log(`save_snapshot${label ? ` (label: "${label}")` : ''}...`);
  const reply = await reactor.sendCommand('save_snapshot', label ? { label } : {});

  log(`save_snapshot -> ${JSON.stringify(reply)}`);
  labelInput.value = '';
  await refreshSnapshots();
});
