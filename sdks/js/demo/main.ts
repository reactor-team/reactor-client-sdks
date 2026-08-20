// Minimal, framework-free demo: connect, print status changes to the
// console (and this page), disconnect. Exercises the same
// `@reactor-team/js-sdk` package a plain-JS consumer would `npm install`.
import { Reactor } from '@reactor-team/js-sdk';
import type { Reactor as ReactorType } from '@reactor-team/js-sdk';

const statusEl = document.querySelector<HTMLSpanElement>('#status')!;
const logEl = document.querySelector<HTMLDivElement>('#log')!;
const modelNameEl = document.querySelector<HTMLInputElement>('#modelName')!;
const apiUrlEl = document.querySelector<HTMLInputElement>('#apiUrl')!;
const localEl = document.querySelector<HTMLInputElement>('#local')!;
const apiKeyEl = document.querySelector<HTMLInputElement>('#apiKey')!;
const connectButton = document.querySelector<HTMLButtonElement>('#connect')!;
const disconnectButton = document.querySelector<HTMLButtonElement>('#disconnect')!;
const commandEl = document.querySelector<HTMLInputElement>('#command')!;
const commandDataEl = document.querySelector<HTMLInputElement>('#commandData')!;
const sendCommandButton = document.querySelector<HTMLButtonElement>('#sendCommand')!;
const getSchemaButton = document.querySelector<HTMLButtonElement>('#getSchema')!;
const commandsEl = document.querySelector<HTMLDivElement>('#commands')!;
const commandsEmptyEl = document.querySelector<HTMLParagraphElement>('#commandsEmpty')!;
const tracksEl = document.querySelector<HTMLDivElement>('#tracks')!;

function log(message: string): void {
  console.log(message);
  logEl.textContent += `${new Date().toLocaleTimeString()}  ${message}\n`;
  logEl.scrollTop = logEl.scrollHeight;
}

interface OpenApiCommand {
  name: string;
  description?: string;
  /** The requestBody JSON schema (`{ properties: { ... } }`), if declared —
   *  used to stub out Data with each property's default. */
  dataSchema?: unknown;
}

// getSchema() is `unknown` — an OpenAPI document this package doesn't model.
// Real runtimes (confirmed against a live model) key `paths` by
// `/events/<command>`, each with a `post.operationId` (the name sendCommand
// expects), a `post.summary` (human description), and a `post.requestBody`
// JSON schema for the command's data — capabilities().commands looked like
// the "proper" typed source for the name/description part, but a real
// runtime never populated it, only `tracks`, so this reads the schema
// instead. Read defensively since nothing here is a contract this repo
// controls.
function commandsFromSchema(schema: unknown): OpenApiCommand[] {
  const paths = (schema as { paths?: unknown } | null)?.paths;

  if (!paths || typeof paths !== 'object') {
    return [];
  }

  const commands: OpenApiCommand[] = [];

  for (const pathItem of Object.values(paths as Record<string, unknown>)) {
    const post = (
      pathItem as {
        post?: { operationId?: unknown; summary?: unknown; requestBody?: unknown };
      } | null
    )?.post;

    if (typeof post?.operationId !== 'string') {
      continue;
    }
    const dataSchema = (
      post.requestBody as { content?: { ['application/json']?: { schema?: unknown } } } | undefined
    )?.content?.['application/json']?.schema;

    commands.push({
      name: post.operationId,
      description: typeof post.summary === 'string' ? post.summary : undefined,
      dataSchema,
    });
  }
  return commands;
}

// A starting point for Data, not a validated payload: each property gets its
// declared `default`, or a zero value for its `type` when there isn't one.
// Good enough to edit from — this demo doesn't need a real JSON-schema
// instantiator.
function stubDataFor(dataSchema: unknown): Record<string, unknown> {
  const properties = (dataSchema as { properties?: unknown } | null)?.properties;

  if (!properties || typeof properties !== 'object') {
    return {};
  }

  const stub: Record<string, unknown> = {};

  for (const [key, propSchema] of Object.entries(properties as Record<string, unknown>)) {
    const prop = propSchema as { default?: unknown; type?: unknown } | null;

    if (prop && 'default' in prop) {
      stub[key] = prop.default;
      continue;
    }
    switch (prop?.type) {
      case 'string':
        stub[key] = '';
        break;
      case 'integer':
      case 'number':
        stub[key] = 0;
        break;
      case 'boolean':
        stub[key] = false;
        break;
      case 'array':
        stub[key] = [];
        break;
      case 'object':
        stub[key] = {};
        break;
      default:
        stub[key] = null;
    }
  }
  return stub;
}

// One click-to-fill button per command the schema declares.
function renderCommands(commands: OpenApiCommand[] | undefined): void {
  commandsEl.innerHTML = '';
  commandsEmptyEl.style.display = commands?.length ? 'none' : '';
  for (const command of commands ?? []) {
    const button = document.createElement('button');

    button.textContent = command.name;
    if (command.description) {
      button.title = command.description;
    }
    button.addEventListener('click', () => {
      commandEl.value = command.name;
      commandDataEl.value = JSON.stringify(stubDataFor(command.dataSchema));
      commandDataEl.focus();
      log(`${command.name} data schema -> ${JSON.stringify(command.dataSchema)}`);
    });
    commandsEl.append(button);
  }
}

// One <video> or <audio> per received track name, created lazily and reused
// across later `trackReceived` events for the same name (e.g. a reconnect).
const trackElements = new Map<string, HTMLVideoElement | HTMLAudioElement>();

function renderTrack(name: string, stream: MediaStream | undefined): void {
  if (!reactor || !stream) {
    log(`trackReceived(${name}): no stream resolved`);
    return;
  }
  // `tracks()` carries the declared `kind` per name — trackReceived's own
  // payload doesn't, so this is the only way to know whether to render a
  // <video> or an <audio> element for it.
  const kind = reactor.tracks().find((track) => track.name === name)?.kind ?? 'video';

  let element = trackElements.get(name);

  if (!element) {
    const wrapper = document.createElement('div');

    wrapper.className = 'track';
    const label = document.createElement('div');

    label.textContent = name;
    element = document.createElement(kind === 'audio' ? 'audio' : 'video');
    element.autoplay = true;
    if (element instanceof HTMLVideoElement) {
      element.playsInline = true;
      // Muted so a locally-published webcam/mic doesn't echo back through
      // its own model-side round-trip — audio-kind tracks stay unmuted,
      // since muting those would defeat the point of rendering them at all.
      element.muted = true;
    } else {
      // Audio elements render as nothing visible otherwise — controls make
      // it obvious in the demo that a stream is attached and playing.
      element.controls = true;
    }
    wrapper.append(label, element);
    tracksEl.append(wrapper);
    trackElements.set(name, element);
  }
  element.srcObject = stream;
}

function clearTracks(): void {
  tracksEl.innerHTML = '';
  trackElements.clear();
}

// Demo-only convenience: persist every field in this browser's localStorage
// so the form comes back as you left it. Fine for a local tool nobody else's
// browser ever loads — never do this in a real app served to users.
const STORAGE_PREFIX = 'reactor-demo-';

function persistText(el: HTMLInputElement, key: string): void {
  const stored = localStorage.getItem(STORAGE_PREFIX + key);

  if (stored !== null) {
    el.value = stored;
  }
  el.addEventListener('input', () => localStorage.setItem(STORAGE_PREFIX + key, el.value));
}

function persistCheckbox(el: HTMLInputElement, key: string): void {
  const stored = localStorage.getItem(STORAGE_PREFIX + key);

  if (stored !== null) {
    el.checked = stored === 'true';
  }
  el.addEventListener('change', () => localStorage.setItem(STORAGE_PREFIX + key, String(el.checked)));
}

persistCheckbox(localEl, 'local');
persistText(modelNameEl, 'model-name');
persistText(apiUrlEl, 'api-url');
persistText(apiKeyEl, 'api-key');
// Command/Data are deliberately not persisted: they're only ever usable
// against a live "ready" session, so a stale value from a previous model
// isn't worth carrying across page loads the way the connection fields are.

// A local runtime serves exactly one, already-loaded model and takes no auth
// (see `local_start_session` in reactor-core's coordinator.rs — it never
// sends `model_name`, and there's no JWT check). `apiUrl` stays enabled in
// both modes: it's the override for a local runtime on a non-default
// host/port, not something tied to prod vs. local.
function updateFieldAvailability(): void {
  const disabledForLocal = localEl.checked;

  modelNameEl.disabled = disabledForLocal;
  apiKeyEl.disabled = disabledForLocal;
}

localEl.addEventListener('change', updateFieldAvailability);
updateFieldAvailability();

// Called as the Reactor's `jwt` resolver — the SDK invokes this before every
// authenticated request (session-ready polling, heartbeats, ...), not once
// per connect. Minting a fresh token on every one of those would both hammer
// `/api/generate-jwt` and hand the coordinator a different token per poll, so
// this caches the in-flight/resolved token per API key and only mints a new
// one when that key actually changes.
let cachedJwt: { apiKey: string; promise: Promise<string> } | undefined;

async function fetchJwt(apiKey: string): Promise<string> {
  if (!apiKey) {
    throw new Error('enter an API key first');
  }
  if (cachedJwt?.apiKey === apiKey) {
    return cachedJwt.promise;
  }

  log('generating JWT...');
  const promise = (async () => {
    const response = await fetch('/api/generate-jwt', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ apiKey }),
    });
    const body = await response.json();

    if (!response.ok) {
      throw new Error(body.error ?? `HTTP ${response.status}`);
    }
    log('JWT generated');
    return body.jwt as string;
  })();

  cachedJwt = { apiKey, promise };
  // Don't cache a failure — a transient fetch error shouldn't wedge every
  // later resolver call behind it.
  promise.catch(() => {
    if (cachedJwt?.promise === promise) {
      cachedJwt = undefined;
    }
  });
  return promise;
}

let reactor: ReactorType | undefined;
// modelName/apiUrl/local are fixed for a Reactor's lifetime. This tracks what
// the current `reactor`, if any, was built with, so a changed target (e.g.
// toggling `local`) rebuilds instead of silently reconnecting under the old
// settings. `apiKey` isn't part of this: the `jwt` resolver below reads it
// fresh on every call, so a changed key doesn't need a new instance.
let reactorTargetKey: string | undefined;

document.querySelector('#connect')!.addEventListener('click', () => {
  void (async () => {
    if (!localEl.checked && !modelNameEl.value.trim()) {
      log('connect failed: enter a model name (required against prod)');
      return;
    }
    // The wasm binding requires a non-empty modelName unconditionally, even
    // though a local runtime ignores it (serves whatever one it was started
    // with — see `local_start_session` in coordinator.rs). The field is
    // disabled while `local` is checked, so fall back instead of trusting
    // whatever's left in it.
    const modelName = localEl.checked ? modelNameEl.value.trim() || 'local' : modelNameEl.value.trim();
    const apiUrl = apiUrlEl.value.trim() || undefined;
    const local = localEl.checked;
    const targetKey = JSON.stringify({ modelName, apiUrl, local });

    if (reactor && targetKey !== reactorTargetKey) {
      log('connection target changed — disposing the previous instance first');
      reactor[Symbol.dispose]();
      reactor = undefined;
    }

    // Reuse the existing instance across a disconnect/connect cycle — only
    // disposing it (`reactor[Symbol.dispose]()`) needs a fresh one.
    if (!reactor) {
      reactor = new Reactor({
        modelName,
        apiUrl,
        local,
        // Omitted for a local runtime: it takes no auth. Otherwise a
        // resolver, not a fetched-once string — the SDK calls this before
        // every authenticated request, so connect()/reconnect() always get
        // a fresh token without the demo managing one by hand.
        jwt: local ? undefined : () => fetchJwt(apiKeyEl.value.trim()),
      });
      reactorTargetKey = targetKey;
      // Debug-only convenience: poke at the instance from devtools, e.g.
      // `reactor.tracks()` — it's otherwise unreachable from the console
      // since it's a module-scoped variable, not a global.
      (window as unknown as { reactor: ReactorType }).reactor = reactor;
      reactor.on('statusChanged', (status) => {
        statusEl.textContent = status;
        log(`statusChanged -> ${status}`);
        // Command/Data and their buttons only make sense against a "ready"
        // session — sendCommand would just reject otherwise.
        const isReady = status === 'ready';

        commandEl.disabled = !isReady;
        commandDataEl.disabled = !isReady;
        sendCommandButton.disabled = !isReady;
        getSchemaButton.disabled = !isReady;
        // Connect only makes sense from "disconnected"; Disconnect only once
        // truly "ready" — both stay disabled through "connecting"/"waiting"
        // rather than offering a mid-transition disconnect.
        connectButton.disabled = status !== 'disconnected';
        disconnectButton.disabled = !isReady;
        if (status === 'disconnected') {
          renderCommands(undefined);
          clearTracks();
        }
      });
      reactor.on('sessionIdChanged', (sessionId) => log(`sessionIdChanged -> ${sessionId}`));
      reactor.on('error', (error) => log(`error -> ${error.code}: ${error.message}`));
      reactor.on('message', (message) => log(`message -> ${JSON.stringify(message)}`));
      reactor.on('runtimeMessage', (message) => log(`runtimeMessage -> ${JSON.stringify(message)}`));
      reactor.on('trackReceived', (name, _track, stream, mid) => {
        log(`trackReceived -> name=${name} mid=${mid}`);
        renderTrack(name, stream);
      });
      // The auto-request on "ready" fires this once it lands — reading
      // getSchema() straight off statusChanged("ready") would race it, since
      // that fetch is async and dispatched separately.
      reactor.on('schema', (schema) => renderCommands(commandsFromSchema(schema)));
    }

    log('connecting...');
    try {
      await reactor.connect();
      log(`connected. sessionId=${reactor.getSessionId()}`);
    } catch (error) {
      log(`connect failed: ${String(error)}`);
    }
  })();
});

document.querySelector('#disconnect')!.addEventListener('click', () => {
  void (async () => {
    if (!reactor) {
      return;
    }
    // Default (recoverable = false): ends the session and frees the wasm
    // client in one step — the instance stays around, but a subsequent
    // connect() rebuilds the client from scratch.
    await reactor.disconnect();
    log('disconnected');
  })();
});

document.querySelector('#sendCommand')!.addEventListener('click', () => {
  void (async () => {
    if (!reactor) {
      log('send command failed: connect first');
      return;
    }
    const command = commandEl.value.trim();

    if (!command) {
      log('send command failed: enter a command name');
      return;
    }

    let data: Record<string, unknown> | undefined;
    const raw = commandDataEl.value.trim();

    try {
      data = raw ? JSON.parse(raw) : undefined;
    } catch (error) {
      log(`send command failed: invalid JSON data — ${String(error)}`);
      return;
    }

    log(`sendCommand(${command}, ${JSON.stringify(data)})...`);
    try {
      const reply = await reactor.sendCommand(command, data);

      log(`sendCommand -> ${JSON.stringify(reply)}`);
    } catch (error) {
      log(`sendCommand failed: ${String(error)}`);
    }
  })();
});

document.querySelector('#getSchema')!.addEventListener('click', () => {
  if (!reactor) {
    log('get schema failed: connect first');
    return;
  }
  log(`getSchema() -> ${JSON.stringify(reactor.getSchema())}`);
});

document.querySelector('#clearLog')!.addEventListener('click', () => {
  logEl.textContent = '';
});

document.querySelector('#copyLog')!.addEventListener('click', () => {
  void (async () => {
    try {
      await navigator.clipboard.writeText(logEl.textContent ?? '');
    } catch (error) {
      log(`copy failed: ${String(error)}`);
    }
  })();
});
