import { Reactor } from '../reactor';
import type { ReactorError } from '../errors';
import type { FileRef } from '../file-ref';
import type {
  Clip,
  ConnectOptions,
  JwtSource,
  MessageScope,
  ReactorMessage,
  ReactorOptions,
  ReactorStatus,
} from '../types';

/** State kept reactive for `useReactor` selectors. Stats/schema/capabilities
 *  aren't mirrored here — reach them through the `internal.reactor` escape
 *  hatch. */
export interface ReactorState {
  status: ReactorStatus;
  sessionId: string | undefined;
  lastError: ReactorError | undefined;
  /** Most recent app-scope `message` payload; `runtimeMessage` isn't mirrored
   *  here — subscribe on `internal.reactor` for that. */
  lastMessage: ReactorMessage | undefined;
  /** Media tracks received from the model, keyed by track name. Reset to
   *  `{}` on every "disconnected" status transition — a fresh connection
   *  invalidates whatever arrived under the previous one. Append-only within
   *  a session: a track pausing/unpublishing mid-session doesn't remove its
   *  entry, so a stale `MediaStreamTrack` can linger until the next
   *  disconnect. Matches v2's identical behavior (`core/store.ts`). */
  tracks: Record<string, MediaStreamTrack>;
  /** The `jwt` the store was created with — see `createReactorStore`'s
   *  `options.jwt`. Set once at creation, not resynced afterward. */
  jwtToken: JwtSource | undefined;
  /** The `defaultConnectOptions` the store was created with — set once at
   *  creation, not resynced afterward. Lets a component read the
   *  provider-level defaults (e.g. `autoResumeTracks`) without threading
   *  them through props of its own. */
  connectOptions: ConnectOptions | undefined;
}

export interface ReactorActions {
  connect: (jwt?: JwtSource, options?: ConnectOptions) => Promise<void>;
  disconnect: (recoverable?: boolean) => Promise<void>;
  reconnect: (options?: ConnectOptions) => Promise<void>;
  sendCommand: (
    command: string,
    data?: Record<string, unknown>,
    scope?: MessageScope,
  ) => Promise<ReactorMessage | undefined>;
  publish: (name: string, track: MediaStreamTrack) => Promise<void>;
  unpublish: (name: string) => Promise<void>;
  pauseTrack: (name: string) => Promise<void>;
  resumeTrack: (name: string) => Promise<void>;
  uploadFile: (file: File | Blob, options?: { name?: string }) => Promise<FileRef>;
  requestClip: (durationSeconds: number) => Promise<Clip>;
  requestRecording: () => Promise<Clip>;
}

export interface ReactorInternal {
  /** Escape hatch: the underlying `Reactor` instance, for anything the
   *  selector state / action bindings above don't cover. */
  reactor: Reactor;
}

export type ReactorStore = ReactorState & ReactorActions & { internal: ReactorInternal };

export const defaultReactorState: ReactorState = {
  status: 'disconnected',
  sessionId: undefined,
  lastError: undefined,
  lastMessage: undefined,
  tracks: {},
  jwtToken: undefined,
  connectOptions: undefined,
};

export interface StoreApi<T> {
  getState: () => T;
  subscribe: (listener: () => void) => () => void;
}

/** Bare-bones observable-state container: a `set`/`get` pair for the
 *  builder to mirror events into, `subscribe` for `useSyncExternalStore`. */
function createStore<T extends object>(build: (set: (partial: Partial<T>) => void, get: () => T) => T): StoreApi<T> {
  const listeners = new Set<() => void>();
  let state: T;

  const set = (partial: Partial<T>): void => {
    state = { ...state, ...partial };
    listeners.forEach((listener) => listener());
  };
  const get = (): T => state;

  state = build(set, get);

  return {
    getState: get,
    subscribe: (listener) => {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
  };
}

/**
 * Builds a `Reactor` and the store mirroring it. `defaultConnectOptions` is
 * applied to every `connect()`/`reconnect()` call; a call-site `options`
 * argument still wins field-by-field.
 */
export function createReactorStore(
  options: ReactorOptions,
  defaultConnectOptions?: ConnectOptions,
): StoreApi<ReactorStore> {
  return createStore<ReactorStore>((set, get) => {
    const reactor = new Reactor(options);

    reactor.on('statusChanged', (status) => {
      set(status === 'disconnected' ? { status, tracks: {} } : { status });
    });
    reactor.on('sessionIdChanged', (sessionId) => set({ sessionId }));
    reactor.on('error', (lastError) => set({ lastError }));
    reactor.on('message', (lastMessage) => set({ lastMessage }));
    reactor.on('trackReceived', (name, track) => set({ tracks: { ...get().tracks, [name]: track } }));

    return {
      ...defaultReactorState,
      jwtToken: options.jwt,
      connectOptions: defaultConnectOptions,
      internal: { reactor },
      connect: (jwt, callOptions) =>
        get().internal.reactor.connect(jwt, { ...defaultConnectOptions, ...callOptions }),
      disconnect: (recoverable) => get().internal.reactor.disconnect(recoverable),
      reconnect: (callOptions) =>
        get().internal.reactor.reconnect({ ...defaultConnectOptions, ...callOptions }),
      sendCommand: (command, data, scope) => get().internal.reactor.sendCommand(command, data, scope),
      publish: (name, track) => get().internal.reactor.publishTrack(name, track),
      unpublish: (name) => get().internal.reactor.unpublishTrack(name),
      pauseTrack: (name) => get().internal.reactor.pauseTrack(name),
      resumeTrack: (name) => get().internal.reactor.resumeTrack(name),
      uploadFile: (file, options) => get().internal.reactor.uploadFile(file, options),
      requestClip: (durationSeconds) => get().internal.reactor.requestClip(durationSeconds),
      requestRecording: () => get().internal.reactor.requestRecording(),
    };
  });
}
