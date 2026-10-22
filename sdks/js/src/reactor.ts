import { AwaitQueue } from 'awaitqueue';
import { toPublicCapabilities } from './internal/capabilities';
import { debugLog } from './internal/debug-log';
import { InvalidStateError, toReactorError, type ReactorError } from './errors';
import { Emitter } from './internal/emitter';
import { extractFileRefs, rewriteNestedFileRefs, toPublicFileRef } from './internal/file-ref';
import { holdFrame, isAudioFrame, toPixels } from './internal/push-frames';
import { toPublicClip } from './internal/recording';
import type { ReactorClient } from './internal/reactor-wasm.types';
import {
  LOCAL_STATS_INTERVAL_MS,
  RUNTIME_REPORT_INTERVAL_MS,
  createConnectionStatsExtractor,
  createTrackStatsExtractor,
  pollPeerStats,
  toClientConnectionStat,
  type TrackStatsExtractor,
} from './internal/stats';
import { loadReactorWasm } from './internal/wasm';
import packageJson from '../package.json';
import type { FileRef } from './file-ref';
import { downloadClipAsFile as downloadClipAsFileFn, type DownloadClipOptions } from './recording';
import type {
  Capabilities,
  Clip,
  ConnectionStats,
  ConnectionTimings,
  ConnectOptions,
  JwtSource,
  MessageScope,
  ModelSchema,
  NotFunction,
  PushFrame,
  PublishedTrack,
  PushFrameOptions,
  ReactorEventMap,
  ReactorMessage,
  ReactorOptions,
  ReactorStatus,
  SessionResponse,
  StepOptions,
  StepResult,
  TrackCapability,
  TrackMappingEntry,
} from './types';

/**
 * A live connection to a Reactor model.
 */
export class Reactor implements Disposable {
  private readonly clientOptions: Omit<ReactorOptions, 'jwt'>;
  private jwt: JwtSource | null | undefined;
  private client: ReactorClient | undefined;
  private clientPromise: Promise<ReactorClient> | undefined;
  private disposed = false;
  private _lastError: ReactorError | undefined;
  private schema: ModelSchema | undefined;
  /** Bumped on every `refreshSchema()` call — lets a call detect it's been
   *  superseded by a newer one even when `client` itself hasn't changed
   *  (e.g. two "ready" transitions on the same reused client). */
  private schemaRefreshId = 0;
  private capabilities: Capabilities | undefined;

  private stats: ConnectionStats | undefined;
  private connectionTimings: ConnectionTimings | undefined;
  /** The stop functions of the running stats pollers — see `pollPeerStats()`. */
  private stopStatsPollers: Array<() => void> = [];
  /** Whether this connection has already reported its time to connect —
   *  a one-time fact, sent on the first client-stats report that carries it
   *  and omitted on every one after. Set only once a batch actually went out
   *  with it, so a batch sent before the timings exist doesn't use it up.
   *  Reset per `startStatsPolling()` call, i.e. per connect()/reconnect(). */
  private hasSentConnectTime = false;
  /** Whether this connection has sent a client-stats batch yet. The first
   *  one goes out even with no reading, for the environment the bridge adds
   *  to it. Reset with `hasSentConnectTime`. */
  private hasSentClientStats = false;
  /** Set on the "connecting" status transition, cleared once `connectionTimings`
   *  is finalized on "ready" — see `handleStatusChanged()`. */
  private connectStartTime: number | undefined;
  private waitingStartTime: number | undefined;

  /** The tail of every `pushFrame()` so far, settled either way — pushes
   *  reach the binding in call order however long each one's pixels take to
   *  read, and `step()` waits for the ones called before it. */
  private pendingPushes: Promise<void> = Promise.resolve();

  private readonly emitter = new Emitter<ReactorEventMap>();
  /** Serializes connect()/reconnect()/disconnect() (and the free() inside
   *  disconnect()/[Symbol.dispose]) against each other. Calling into the
   *  client concurrently with one of these (e.g. a user clicking Disconnect
   *  mid-connect) races its internal state and can throw ("attempted to
   *  take ownership of Rust value while it was borrowed") or corrupt it
   *  outright. */
  private readonly queue = new AwaitQueue();

  /** In-flight calls to `withReaderAccess()` — a "reply" `.catch(() => {})`
   *  copy of each, never the caller's own promise, so one rejecting can't
   *  make `Promise.all()` in `withWriterAccess()` reject and skip waiting
   *  for the rest. See `withReaderAccess()`/`withWriterAccess()`. */
  private readonly activeReads = new Set<Promise<unknown>>();
  /** True for the span of a `withWriterAccess()` call — after it has waited
   *  out every read active when it started, before it releases the ones
   *  that arrived while it ran. */
  private writerActive = false;
  /** Counts `disconnect()`s and disposal. A push captures it when called, and
   *  one whose frame was still being read when it moved is dropped, rather
   *  than recreating a client to carry the frame into the next session. */
  private connectionEpoch = 0;
  /** Input tracks published on this connection, each with the publication
   *  that claimed it: a push needs one, and a `PublishedTrack` only feeds the
   *  publication it came from. Cleared with the connection. */
  private readonly publishedInputs = new Map<string, number>();
  private publications = 0;
  /** Readers parked in `withReaderAccess()`'s wait loop, woken once
   *  `withWriterAccess()` finishes; each re-checks `writerActive` rather
   *  than assuming its turn, since another writer already queued behind the
   *  first one may have claimed it first. */
  private writerIdleWaiters: Array<() => void> = [];

  /**
   * Runs a read — sendCommand()/requestSchema()/requestClip()/
   * requestRecording() — excluded from a lifecycle op (`withWriterAccess()`)
   * but not from other reads: unlike `queue`, concurrent reads run
   * concurrently. Queuing them the same way `queue` serializes writes would
   * head-of-line block an unrelated command, or even disconnect() itself,
   * behind one slow reply (the binding correlates replies independently, so
   * nothing requires this to be serial).
   *
   * Race-free the same way the wasm binding's own `readyState` check is: the
   * `writerActive` check and this call's registration in `activeReads` both
   * happen before the first `await` inside *fn*, so nothing can run between
   * them on a single-threaded JS event loop.
   */
  private async withReaderAccess<T>(fn: () => Promise<T>): Promise<T> {
    while (this.writerActive) {
      await new Promise<void>((resolve) => this.writerIdleWaiters.push(resolve));
    }
    const result = fn();
    const tracked = result.catch(() => undefined);

    this.activeReads.add(tracked);
    try {
      return await result;
    } finally {
      this.activeReads.delete(tracked);
    }
  }

  /**
   * Runs a lifecycle op's client-touching body exclusively: waits out every
   * read already in flight, then blocks new ones (`writerActive`) until it
   * finishes. Only ever called from inside a `queue`-serialized task, so at
   * most one writer runs this at a time — it doesn't serialize against
   * itself, only against `activeReads`.
   */
  private async withWriterAccess<T>(fn: () => Promise<T>): Promise<T> {
    // Mark the writer active *before* awaiting the current reads, not after:
    // withReaderAccess()'s gate check is what keeps a new read from entering
    // during that wait, and it only works once this flag is already true.
    // Setting it first can't race a second writer — writers only ever run
    // from inside a `queue`-serialized task, so at most one is ever here.
    this.writerActive = true;
    // Skip the await entirely when nothing is in flight — `await
    // Promise.all([])` still costs a microtask tick, which the common
    // (uncontended) case shouldn't pay: connect()/disconnect()/reconnect()
    // calling into the client promptly is a property callers (React's
    // effect-cleanup unmount included) rely on.
    if (this.activeReads.size > 0) {
      await Promise.all(this.activeReads);
    }
    try {
      return await fn();
    } finally {
      this.writerActive = false;
      const waiters = this.writerIdleWaiters;

      this.writerIdleWaiters = [];
      for (const wake of waiters) {wake();}
    }
  }

  constructor(options: ReactorOptions) {
    const { jwt, ...clientOptions } = options;

    // The wasm binding's own default (`reactor-core`'s crate version) tracks
    // the Rust workspace, not this package — without this, `client_info`
    // reports the wrong version to the coordinator. Still overridable via
    // `options.sdkVersion`.
    this.clientOptions = { sdkVersion: packageJson.version, ...clientOptions };
    this.jwt = jwt ?? null;
  }

  // ── Lifecycle ───────────────────────────────────────────────────────────

  /**
   * `jwt`, when given, replaces whatever `new Reactor({ jwt })` was given —
   * including on a client that's still around from a recoverable
   * `disconnect(true)`.
   *
   * Throws if already connected or connecting; call `disconnect()` first.
   */
  async connect(jwt?: JwtSource, options?: ConnectOptions): Promise<void> {
    this.assertNotDisposed();
    try {
      if (this.getStatus() !== 'disconnected') {
        throw new Error('Already connected or connecting.');
      }
      if (jwt !== undefined) {
        this.jwt = jwt;
        this.client?.setJwt(jwt);
      }
      await this.queue.push(
        () =>
          this.withWriterAccess(async () => {
            const client = await this.getOrCreateClient();

            await client.connect(options);
          }),
        'connect',
      );
    } catch (cause) {
      throw this.captureError(cause);
    }
  }

  /**
   * Ends the connection. By default (`recoverable = false`) this also frees
   * the wasm resource graph — the pump/dispatcher/heartbeat tasks and the
   * peer connection — in one step. Pass `recoverable: true` to keep the
   * wasm client alive so a later `connect()`/`reconnect()` doesn't have to
   * reload wasm and reconstruct it from scratch.
   *
   * Note this only governs the *local* resource graph — the binding's own
   * `disconnect()` always ends the session server-side regardless of this
   * flag, so `recoverable` isn't a way to keep the session itself alive.
   *
   * Queued behind any in-flight `connect()`/`reconnect()` — see `queue`'s
   * docs for why calling into the client concurrently with one of those is
   * unsafe.
   */
  async disconnect(recoverable = false): Promise<void> {
    this.assertNotDisposed();
    try {
      await this.queue.push(
        () =>
          this.withWriterAccess(async () => {
            if (this.client) {
              await this.client.disconnect();
            }
            this.resetConnectionState();
            if (!recoverable) {
              this.freeClient();
            }
          }),
        'disconnect',
      );
    } catch (cause) {
      throw this.captureError(cause);
    }
  }

  /**
   * Only `options.maxAttempts` has an effect here — the rest of
   * `ConnectOptions` (session adoption, connection id, auto-resume) only
   * makes sense when establishing a session in the first place, same as v2's
   * `reconnect()` always ignored them.
   */
  async reconnect(options?: ConnectOptions): Promise<void> {
    this.assertNotDisposed();
    try {
      await this.queue.push(
        () =>
          this.withWriterAccess(async () => {
            const client = await this.getOrCreateClient();

            await client.reconnect(options);
          }),
        'reconnect',
      );
    } catch (cause) {
      throw this.captureError(cause);
    }
  }

  // ── Messaging ───────────────────────────────────────────────────────────

  /**
   * Sends a command to the model and resolves with its correlated reply.
   *
   * `undefined` means the handler acknowledged the command but sent no
   * reply body. The binding's own typed signature promises `undefined` for
   * that case, but its serializer (serde_wasm_bindgen's `json_compatible`
   * mode) actually hands back `null` — normalized here so callers can rely
   * on the documented type instead of the binding's serialization quirk.
   *
   * A `FileRef` (from `uploadFile`) may be passed in `data` wherever the
   * model's schema declares a file. As a top-level value it is extracted and
   * sent as a separate upload reference rather than embedded in the JSON
   * payload; inside an array or object (a parameter that takes several files,
   * say) it is serialized as an upload reference in place.
   *
   * Unlike every other method here, this never rejects — a failure (the
   * session isn't `"ready"`, the send itself fails, …) is reported through
   * `getLastError()`/the `error` event instead, resolving with `undefined`.
   * This is a JS-only compatibility shim, kept because callers routinely
   * fire-and-forget `sendCommand(...)` without `await`/`catch`, and a
   * rejection nobody handles is an unhandled-rejection warning at best. It
   * is deliberately not applied to `publishTrack`/`uploadFile`/etc., which
   * throw normally.
   *
   * `scope` is forwarded to `sendRuntimeScopedCommand()` — see there for
   * what `"runtime"` actually does.
   *
   * `data` accepts any object, except a function.
   */
  async sendCommand<T extends object = Record<string, unknown>>(
    command: string,
    data?: NotFunction<T>,
    scope?: MessageScope,
  ): Promise<ReactorMessage | undefined> {
    if (scope === 'runtime') {
      return this.sendRuntimeScopedCommand(command, data);
    }
    try {
      this.assertNotDisposed();
      const extracted = extractFileRefs(data as Record<string, unknown> | undefined);
      // Excluded from connect()/disconnect()/reconnect() — a concurrent
      // disconnect() can otherwise free the wasm client while this call into
      // it is still in flight (see `withReaderAccess()`'s docs) — but not
      // from other reads, unlike the track ops on `queue`: two sendCommand()
      // calls (or a slow one and a disconnect()) shouldn't head-of-line
      // block each other when the binding correlates replies independently.
      const reply = await this.withReaderAccess(async () => {
        const client = await this.getOrCreateClient();

        return client.sendCommand(command, extracted.data, extracted.uploads);
      });

      return reply ?? undefined;
    } catch (cause) {
      this.emitError(cause);
      return undefined;
    }
  }

  /**
   * Handles `sendCommand(command, data, "runtime")` — this SDK has no
   * generic runtime-scope channel. `reactor-core`'s `send_command` is a
   * single channel; `requestSchema`/clip requests/heartbeat are each their
   * own dedicated RPC instead of a scoped envelope, so there's nothing
   * generic to route a scope through at the wire level.
   *
   * `requestSchema` and `requestCapabilities` route to their direct
   * equivalents below, neither of which returns a `ReactorMessage` the way a
   * normal `sendCommand()` reply would, so both resolve `undefined`; the
   * actual data surfaces through `getSchema()`/`schemaReceived` and
   * `getCapabilities()`/`capabilitiesReceived` as it already does. Anything
   * else has no runtime-scope destination to route to — rather than silently
   * doing nothing, it falls through as a normal application-scope send, with
   * a console warning.
   */
  private async sendRuntimeScopedCommand(
    command: string,
    data?: object,
  ): Promise<ReactorMessage | undefined> {
    switch (command) {
      case 'requestSchema':
        try {
          // Same exclusion as `sendCommand()` above.
          await this.withReaderAccess(async () => {
            const client = await this.getOrCreateClient();

            await this.refreshSchema(client);
          });
        } catch (cause) {
          // refreshSchema() reports failures itself when it has a client to
          // key the race guard on; getOrCreateClient() failing before that
          // doesn't, so report it here. Either way, sendCommand's own
          // contract never rejects.
          this.emitError(cause);
        }
        return undefined;
      case 'requestCapabilities':
        // No dedicated "request" exists — reactor-core pushes capabilities
        // once negotiated, so getCapabilities()/capabilitiesReceived already
        // reflect the latest value with nothing left to trigger here.
        return undefined;
      default:
        console.warn(
          `[Reactor] sendCommand(${JSON.stringify(command)}, …, "runtime") has no runtime-scope destination in this SDK — sending as a normal application-scope command instead.`,
        );
        return this.sendCommand(command, data);
    }
  }

  /** Requests the model's command schema directly. Most callers don't need
   *  this — it's already fetched once and cached as soon as the session
   *  reaches `"ready"`; use `getSchema()` for that. Resolves `undefined`
   *  when the model doesn't expose a schema — the binding replies with a
   *  wire `null` in that case rather than omitting the field. */
  async requestSchema(): Promise<ModelSchema | undefined> {
    this.assertNotDisposed();
    try {
      // Excluded from connect()/disconnect()/reconnect() — see
      // `withReaderAccess()`'s docs.
      return await this.withReaderAccess(async () => {
        const client = await this.getOrCreateClient();

        return this.normalizeSchema(await client.requestSchema());
      });
    } catch (cause) {
      throw this.captureError(cause);
    }
  }

  /** The model's command schema (an OpenAPI document), cached from the
   *  `requestSchema()` call fired automatically on `"ready"`. `undefined`
   *  until that reply has landed. */
  getSchema(): ModelSchema | undefined {
    return this.schema;
  }

  /** The runtime's declared capabilities (negotiated tracks, and the
   *  command set when the model exposes one) — pushed once available, no
   *  explicit request needed. `undefined` until `capabilitiesReceived`
   *  fires. */
  getCapabilities(): Capabilities | undefined {
    return this.capabilities;
  }

  /** The session resource, as the coordinator reports it — id, state,
   *  model, cluster, server info, and (once negotiated) `capabilities`.
   *  A live read straight off the wasm client, not cached — reflects
   *  whatever's current. `undefined` before a client exists. */
  getSessionInfo(): SessionResponse | undefined {
    return this.client?.sessionInfo();
  }

  // ── Tracks ──────────────────────────────────────────────────────────────

  /**
   * Publishes a local `MediaStreamTrack` under `name` — the counterpart to a
   * `sendonly` track the model declares. Awaitable. Queued behind
   * connect()/disconnect()/reconnect() (unlike `sendCommand()`): a track
   * operation awaits its own control round-trip, and a concurrent
   * disconnect() freeing the wasm client mid-await would otherwise resume
   * into a freed client — the same class of use-after-free `queue` exists to
   * prevent for connect/disconnect/reconnect themselves.
   *
   * Resolves with a `PublishedTrack` handle whose `pushFrame()` feeds the
   * track in a stepped session.
   *
   * In a `generation: "stepped"` session, input frames never travel over
   * RTP: `track` is optional, and the frames are pushed with
   * `published.pushFrame(...)` for the next `step()`. A `MediaStreamTrack`
   * passed there is ignored, with a console warning — nothing is sent from
   * it. In a streamed session `track` is required, and omitting it rejects
   * with an `InvalidStateError`.
   *
   * ```ts
   * const camera = await reactor.publishTrack('camera');
   * await camera.pushFrame(imageData);
   * const result = await reactor.step();
   * ```
   */
  async publishTrack(name: string, track?: MediaStreamTrack): Promise<PublishedTrack> {
    this.assertNotDisposed();
    try {
      let media = track;

      if (this.isStepped() && media !== undefined) {
        console.warn(
          `[Reactor] publishTrack('${name}'): ignoring the MediaStreamTrack — a stepped session ` +
            'sends no RTP and takes frames through track.pushFrame(...) on the track publishTrack() returns.',
        );
        media = undefined;
      }
      if (!this.isStepped() && media === undefined) {
        throw new InvalidStateError(
          `publishTrack('${name}') needs a MediaStreamTrack in a streamed session.`,
          { operation: 'publishTrack' },
        );
      }
      const publication = (this.publications += 1);

      await this.queue.push(async () => {
        const client = await this.getOrCreateClient();

        await client.publishTrack(name, media);
        this.publishedInputs.set(name, publication);
      }, 'publishTrack');

      return {
        name,
        pushFrame: (frame, options) => this.pushTo(name, publication, frame, options),
      };
    } catch (cause) {
      throw this.captureError(cause);
    }
  }

  /**
   * Unlike every other track method, this doesn't reject — a failure is
   * reported through the `error` event instead, since this is commonly the
   * last call in a `finally` block, and raising there would replace
   * whatever exception was already propagating.
   */
  async unpublishTrack(name: string): Promise<void> {
    this.assertNotDisposed();
    try {
      await this.queue.push(async () => {
        const client = await this.getOrCreateClient();

        await client.unpublishTrack(name);
        this.publishedInputs.delete(name);
      }, 'unpublishTrack');
    } catch (cause) {
      this.emitError(cause);
    }
  }

  /**
   * Stops a received track: the receiver goes inactive and the runtime stops
   * producing it. Awaitable, and queued behind connect()/disconnect()/
   * reconnect() for the same reason as `publishTrack()`.
   */
  async pauseTrack(name: string): Promise<void> {
    this.assertNotDisposed();
    try {
      await this.queue.push(async () => {
        const client = await this.getOrCreateClient();

        await client.pauseTrack(name);
      }, 'pauseTrack');
    } catch (cause) {
      throw this.captureError(cause);
    }
  }

  /**
   * Bounds what one sender may spend, in bits per second.
   *
   * This is the ceiling that actually caps a video encoder, and it is easy to
   * hit without knowing it exists: with nothing set, a Chromium-based browser
   * derives a sender's maximum from the frame size alone, and that maximum is
   * 2500 kbps for anything above 960x540. A 720p or 1080p publish caps at
   * 2.5 Mbps until this raises it, however good the connection is.
   *
   * ```ts
   * await reactor.publishTrack('camera', cameraTrack);
   * await reactor.setTrackBitrate('camera', { maxBps: 8_000_000 });
   * ```
   *
   * A ceiling is permission, not a target: the encoder still spends only what
   * the congestion controller allocated and what the picture needs. What
   * raising it buys is headroom for the moments that would otherwise clip.
   *
   * Needs a connected client, and a published track — there is no sender to
   * bound before `publishTrack()`.
   *
   * Omit a bound to leave it at the browser default. `minBps` is non-standard —
   * Chromium honours it, other engines ignore it.
   *
   * There is no connection-wide counterpart: browsers expose no equivalent of
   * the native SDKs' `setBitrate`, and a method that quietly did nothing would
   * be worse than its absence.
   */
  async setTrackBitrate(
    name: string,
    bounds: { minBps?: number; maxBps?: number } = {},
  ): Promise<void> {
    this.assertNotDisposed();
    try {
      await this.queue.push(async () => {
        const client = await this.getOrCreateClient();

        await client.setTrackBitrate(name, bounds.minBps, bounds.maxBps);
      }, 'setTrackBitrate');
    } catch (cause) {
      throw this.captureError(cause);
    }
  }

  /** Resumes a track previously stopped with `pauseTrack()`. */
  async resumeTrack(name: string): Promise<void> {
    this.assertNotDisposed();
    try {
      await this.queue.push(async () => {
        const client = await this.getOrCreateClient();

        await client.resumeTrack(name);
      }, 'resumeTrack');
    } catch (cause) {
      throw this.captureError(cause);
    }
  }

  // ── Stepped generation ──────────────────────────────────────────────────

  /**
   * Runs one step of a `generation: "stepped"` session and resolves with
   * what it produced, per output track.
   *
   * The request carries `state`, `commands` and every frame pushed since the
   * previous step. The runtime applies them in that order — each `state` key
   * as the model's `set_<key>` command, then `commands`, then the frames —
   * and then generates. They are separate calls, not a transaction: one that
   * fails does not undo the ones before it. All of it is optional:
   * `step()` with nothing set and nothing pushed runs on the model's current
   * state, which commands sent outside a step (`sendCommand()`) also change.
   * A file goes in the way it does for a command: upload it with
   * `uploadFile()`, then put the `FileRef` in a command's `data` or a state
   * value.
   *
   * A step that ran but that the model reported as failed still resolves,
   * with `result.error` set. `result.files` lists the files the model kept
   * with the step (name, content type, size); their bytes are served over
   * HTTP from the session's step results, not carried here.
   *
   * Rejects with a `StepRefusedError` when the runtime or the model turns
   * the step down (the runtime checks a whole request first, so nothing it
   * carried was applied), `StepNotReadyError` when the session cannot take a
   * step yet (neither ran the step), `StepCommandFailedError` when a state key
   * or command failed (the calls before it stay applied; the step did not
   * run); both name the part at fault in `entry`, and `StepCommandFailedError`
   * the handler's code in `command_code`,
   * `StepSessionEndedError` when the session ended before the result came
   * back, `StepResultTooLargeError` when the result does not fit in one
   * message, `MessageTooLargeError` when the request itself is too large
   * (nothing was sent), `InvalidStateError` outside a ready stepped
   * session, or whatever else failed the step (`DisconnectedError`,
   * `RequestTimeoutError`, ...). The frames pushed for a step belong to it:
   * when its request cannot be sent, they are dropped with it rather than
   * kept for the next step.
   *
   * ```ts
   * const camera = await reactor.publishTrack('camera');
   * await camera.pushFrame(imageData);
   * const result = await reactor.step({ state: { prompt: 'a cat' } });
   * if (result.error) console.warn('step failed:', result.error);
   * const frame = result.tracks.find((t) => t.track === 'main_video')?.frames[0];
   * if (frame) ctx.putImageData(stepFrameToImageData(frame), 0, 0);
   * ```
   */
  async step(options: StepOptions = {}): Promise<StepResult> {
    this.assertNotDisposed();
    try {
      // A step has no uploads map: a FileRef rides inline, in its wire shape.
      const state = rewriteNestedFileRefs(options.state ?? null);
      const commands = (options.commands ?? []).map((command) =>
        command.data ? { ...command, data: rewriteNestedFileRefs(command.data) } : command,
      );

      await this.pendingPushes;
      // Excluded from connect()/disconnect()/reconnect() like sendCommand():
      // a step can take minutes, and must neither be freed out from under
      // nor block unrelated reads.
      return await this.withReaderAccess(async () => {
        const client = await this.getOrCreateClient();

        return client.step(state, commands);
      });
    } catch (cause) {
      throw this.captureError(cause);
    }
  }

  /**
   * Buffers one input frame for the next `step()` on the sendonly track
   * `trackName`. Nothing is sent until that step, which carries every frame
   * pushed since the one before it, per track in push order. The
   * `PublishedTrack` that `publishTrack()` resolves with does the same
   * through `track.pushFrame(frame)`.
   *
   * Video: a `VideoFrame` (cloned when called, so the caller may close it as
   * soon as this returns; it stays the caller's), an
   * `ImageData`, or `{ data, width, height, format? }` raw pixels (`"rgba"`
   * by default, or `"rgb"`/`"bgra"`). Sent to the model as RGB. A
   * `VideoFrame` is sent as it is displayed: its `rotation` and `flip` are
   * applied, so a 90° frame arrives with its width and height swapped.
   * Audio: `{ samples, sampleRate, channels? }`, interleaved `Int16Array`
   * PCM at the rate the model's track declares.
   *
   * Pushes keep their call order, and a `step()` waits for every push called
   * before it. A refused push rejects: await it, or `.catch()` it in a frame
   * loop, or every bad frame is an unhandled rejection. What waits for
   * the next step is at most one message's worth of frames: pushing faster
   * than steps run drops the oldest frames, across tracks. Rejects with
   * an `InvalidStateError` outside a stepped session, for a track not
   * published on this connection (`publishTrack(trackName)` first), for a
   * frame whose size does not match its dimensions, or for a name the model
   * does not declare as a sendonly track of that kind.
   */
  async pushFrame(trackName: string, frame: PushFrame, options: PushFrameOptions = {}): Promise<void> {
    return this.pushTo(trackName, undefined, frame, options);
  }

  /** `pushFrame()`, for the track's current publication or, from a
   *  `PublishedTrack`, only for the `publication` it came from. */
  private async pushTo(
    trackName: string,
    publication: number | undefined,
    frame: PushFrame,
    options: PushFrameOptions = {},
  ): Promise<void> {
    this.assertNotDisposed();
    const epoch = this.connectionEpoch;
    let held: ReturnType<typeof holdFrame>;

    try {
      held = holdFrame(frame);
    } catch (cause) {
      throw this.captureError(cause);
    }
    const push = this.pendingPushes.then(async () => {
      let pixels;

      try {
        if (!this.isStepped()) {
          throw new InvalidStateError(
            `pushFrame('${trackName}') needs a session created with generation: "stepped"; ` +
              'a streamed session sends media from a MediaStreamTrack given to publishTrack().',
            { operation: 'pushFrame' },
          );
        }
        pixels = isAudioFrame(held.frame) ? undefined : await toPixels(held.frame);
      } finally {
        held.release();
      }

      // Read access, like step(): a disconnect() must not free the client
      // between resolving it and the (synchronous) push into it.
      await this.withReaderAccess(async () => {
        if (epoch !== this.connectionEpoch) {
          throw new InvalidStateError(
            `pushFrame('${trackName}'): the connection it was pushed to ended while its frame was read`,
            { operation: 'pushFrame' },
          );
        }
        const current = this.publishedInputs.get(trackName);

        if (current === undefined || (publication !== undefined && publication !== current)) {
          throw new InvalidStateError(
            publication === undefined
              ? `pushFrame('${trackName}') needs publishTrack('${trackName}') first.`
              : `pushFrame('${trackName}'): this track was unpublished, or its connection ended.`,
            { operation: 'pushFrame' },
          );
        }
        const client = await this.getOrCreateClient();

        if (pixels) {
          client.pushVideoFrame(
            trackName,
            pixels.data,
            pixels.width,
            pixels.height,
            pixels.format,
            options.metadata ?? null,
            options.captureTimeUs ?? null,
          );
        } else if (isAudioFrame(frame)) {
          client.pushAudioFrame(trackName, frame.samples, frame.sampleRate, frame.channels ?? 1);
        }
      });
    });

    this.pendingPushes = push.catch(() => undefined);
    try {
      await push;
    } catch (cause) {
      throw this.captureError(cause);
    }
  }

  /** Drops every frame buffered for the next step, pushes called before it
   *  included: it waits its turn behind them, as a `step()` does. */
  async clearStepInputs(): Promise<void> {
    this.assertNotDisposed();
    const clear = this.pendingPushes.then(() =>
      this.withReaderAccess(() => {
        this.client?.clearStepInputs();
        return Promise.resolve();
      }),
    );

    this.pendingPushes = clear.catch(() => undefined);
    try {
      await clear;
    } catch (cause) {
      throw this.captureError(cause);
    }
  }

  // ── Recording ───────────────────────────────────────────────────────────

  /**
   * Requests a clip covering the last `durationSeconds` of the session.
   * `reactor-core` correlates the reply itself (and enforces `"ready"`), so
   * this is a thin delegation, same as `requestSchema()`. See
   * `downloadClipAsFile()` to turn the result into a file.
   */
  async requestClip(durationSeconds: number): Promise<Clip> {
    this.assertNotDisposed();
    try {
      // Excluded from connect()/disconnect()/reconnect() — see
      // `withReaderAccess()`'s docs.
      return await this.withReaderAccess(async () => {
        const client = await this.getOrCreateClient();

        return toPublicClip(await client.requestClip(durationSeconds));
      });
    } catch (cause) {
      throw this.captureError(cause);
    }
  }

  /** Requests a clip covering the entire session up to now. See `requestClip()`. */
  async requestRecording(): Promise<Clip> {
    this.assertNotDisposed();
    try {
      // Excluded from connect()/disconnect()/reconnect() — see
      // `withReaderAccess()`'s docs.
      return await this.withReaderAccess(async () => {
        const client = await this.getOrCreateClient();

        return toPublicClip(await client.requestRecording());
      });
    } catch (cause) {
      throw this.captureError(cause);
    }
  }

  /** Thin delegation to the standalone `downloadClipAsFile()` — see its own doc comment. */
  async downloadClipAsFile(
    clip: Clip,
    filename: string | null = 'reactor-clip.mp4',
    options?: DownloadClipOptions,
  ): Promise<Blob> {
    return downloadClipAsFileFn(clip, filename, options);
  }

  // ── Uploads ─────────────────────────────────────────────────────────────

  /**
   * Uploads a file to the session's object store, resolving with a
   * `FileRef` to pass in a `sendCommand()`'s `data`, at the top level or
   * inside an array or object (see `extractFileRefs`). The binding itself
   * takes `(file, name?)`
   * positionally and already applies the right defaulting — a bare
   * `Blob`'s name falls back to `"upload"`, and an empty/missing mime type
   * to `"application/octet-stream"` — so there's nothing to wrap here.
   *
   * Queued behind connect()/disconnect()/reconnect(), same as the track
   * ops: this awaits its own round-trip, and a concurrent disconnect()
   * freeing the wasm client mid-upload would otherwise resume into a freed
   * client — the same use-after-free class of race the queue exists to
   * prevent.
   */
  async uploadFile(file: File | Blob, options?: { name?: string }): Promise<FileRef> {
    this.assertNotDisposed();
    try {
      const wireFileRef = await this.queue.push(async () => {
        const client = await this.getOrCreateClient();

        return client.uploadFile(file, options?.name);
      }, 'uploadFile');

      return toPublicFileRef(wireFileRef);
    } catch (cause) {
      throw this.captureError(cause);
    }
  }

  /** All tracks the model declared, whether or not media has arrived for —
   *  or been published to — them yet. Empty before a client exists. */
  tracks(): TrackCapability[] {
    return this.client?.tracks() ?? [];
  }

  /** Same as `tracks()`, plus each entry's negotiated `mid` — the id
   *  `trackReceived`'s `mid` argument and the `getXByMid` escape hatches key
   *  on. Only populated once SDP negotiation has assigned mids. */
  trackMapping(): TrackMappingEntry[] {
    return this.client?.trackMapping() ?? [];
  }

  pausedTracks(): string[] {
    return this.client?.pausedTracks() ?? [];
  }

  // ── Escape hatches ──────────────────────────────────────────────────────

  /** Drops to the raw `RTCPeerConnection` for anything this class doesn't
   *  wrap directly. `undefined` before a client exists. */
  getPeerConnection(): RTCPeerConnection | undefined {
    return this.client?.getPeerConnection();
  }

  getTrackByMid(mid: string): MediaStreamTrack | undefined {
    return this.client?.getTrackByMid(mid);
  }

  getStreamByMid(mid: string): MediaStream | undefined {
    return this.client?.getStreamByMid(mid);
  }

  getTrackByName(name: string): MediaStreamTrack | undefined {
    return this.client?.getTrackByName(name);
  }

  getStreamByName(name: string): MediaStream | undefined {
    return this.client?.getStreamByName(name);
  }

  /**
   * Supports `using reactor = new Reactor(...)`: releases the wasm resource
   * graph and drops every registered event handler for good. Unlike a plain
   * `disconnect()`, this instance is unusable afterward — construct a new
   * `Reactor` instead of trying to `connect()` again.
   */
  [Symbol.dispose](): void {
    if (this.disposed) {
      return;
    }
    this.disposed = true;
    const client = this.client;

    this.client = undefined;
    this.clientPromise = undefined;
    this.schema = undefined;
    this.capabilities = undefined;
    this.resetConnectionState();
    this.emitter.clear();
    if (client) {
      // Queued behind any in-flight connect()/reconnect()/disconnect(), same
      // as freeClient() — this can't await that itself, since [Symbol.dispose]
      // is synchronous per the `using` contract. Also excluded from any read
      // still in flight, same as freeClient() — see `withWriterAccess()`.
      void this.queue
        .push(() => this.withWriterAccess(() => Promise.resolve(client.free())), 'dispose')
        .catch(() => {});
    }
  }

  // ── Introspection ───────────────────────────────────────────────────────

  /** The wasm binding's own terser `status()` name can be added alongside
   *  this later if it turns out to be worth it. */
  getStatus(): ReactorStatus {
    return this.client?.status() ?? 'disconnected';
  }

  /** See `getStatus()`. */
  getSessionId(): string | undefined {
    return this.client?.sessionId();
  }

  /** The `jwt` this instance was constructed (or last `connect()`ed) with —
   *  lets a component composed inside a `ReactorProvider` (e.g. `ClipPlayer`)
   *  authenticate its own requests without the caller repeating the resolver. */
  getJwtResolver(): JwtSource | undefined {
    return this.jwt ?? undefined;
  }

  /** The most recent `ReactorError`, from either an `error` event or a
   *  rejected call — whichever landed last. `undefined` until the first
   *  failure. */
  getLastError(): ReactorError | undefined {
    return this._lastError;
  }

  /** The most recent WebRTC connection stats, polled every `LOCAL_STATS_INTERVAL_MS`
   *  while "ready" — see `statsUpdate`. `undefined` before the first sample. */
  getStats(): ConnectionStats | undefined {
    return this.stats;
  }

  /** Timing breakdown from the most recent `connect()`/`reconnect()`
   *  handshake — see `ConnectionTimings`. */
  getConnectionTimings(): ConnectionTimings | undefined {
    return this.connectionTimings;
  }

  // ── Events ──────────────────────────────────────────────────────────────

  on<Name extends keyof ReactorEventMap>(event: Name, handler: ReactorEventMap[Name]): void {
    this.emitter.on(event, handler);
  }

  off<Name extends keyof ReactorEventMap>(event: Name, handler: ReactorEventMap[Name]): void {
    this.emitter.off(event, handler);
  }

  once<Name extends keyof ReactorEventMap>(event: Name, handler: ReactorEventMap[Name]): void {
    this.emitter.once(event, handler);
  }

  // ── Internal ────────────────────────────────────────────────────────────

  private getOrCreateClient(): Promise<ReactorClient> {
    if (!this.clientPromise) {
      this.clientPromise = this.createClient().catch((cause) => {
        // A failed first connect shouldn't wedge every later attempt behind
        // a client that never came up.
        this.clientPromise = undefined;
        throw cause;
      });
    }
    return this.clientPromise;
  }

  private async createClient(): Promise<ReactorClient> {
    const { ReactorClient: WasmReactorClient } = await loadReactorWasm();

    // [Symbol.dispose] may have run while the wasm module was loading above;
    // bail out before constructing a client that would otherwise outlive it
    // and never get freed.
    if (this.disposed) {
      throw new Error('Reactor was disposed while connecting.');
    }
    const client = new WasmReactorClient(this.clientOptions, this.jwt);

    client.onStatusChanged((status) => {
      this.emitter.emit('statusChanged', status);
      this.handleStatusChanged(client, status);
    });
    client.onSessionIdChanged((sessionId) => this.emitter.emit('sessionIdChanged', sessionId));
    client.onError((error) => this.emitError(error));
    // DATA channel — the model's own application traffic.
    client.onMessage((message) => {
      debugLog('[Reactor] message:', message);
      this.emitter.emit('message', message);
    });
    // CONTROL channel — platform traffic (moderation, clip/recording lifecycle).
    client.onRuntimeMessage((message) => {
      debugLog('[Reactor] runtimeMessage:', message);
      this.emitter.emit('runtimeMessage', message);
    });
    client.onCapabilitiesReceived((capabilities) => {
      this.capabilities = toPublicCapabilities(capabilities);
      this.emitter.emit('capabilitiesReceived', this.capabilities);
    });
    client.onTrackReceived((name, mid) => {
      const track = client.getTrackByName(name);
      const stream = client.getStreamByName(name);

      // Structurally shouldn't happen — `reactor-core` only dispatches this
      // event once the track is already resolvable — but if it ever does
      // (e.g. a teardown racing the dispatch), skip rather than emitting a
      // lie about the (non-optional) type.
      if (!track || !stream) {
        return;
      }
      this.emitter.emit('trackReceived', name, track, stream, mid);
    });
    this.client = client;
    return client;
  }

  /** Fired once per `"ready"` transition — see `getSchema()`. `getSchema()`
   *  isn't guaranteed populated by the time a `statusChanged` "ready" handler
   *  runs (this fetch is async and dispatched separately), so callers that
   *  need the schema as soon as it lands should listen for `schemaReceived`
   *  instead of reading `getSchema()` synchronously off `statusChanged`.
   *
   *  Guards against a stale reply: if `disconnect()`/a later `connect()`
   *  replaces `client` before this resolves, or a newer `refreshSchema()`
   *  call for the same client wins the race, the result is discarded rather
   *  than clobbering `this.schema` (or emitting `schema`) with old data.
   *
   *  A rejection here (e.g. the session drops mid-request) surfaces like any
   *  other binding failure, through the same `error` event as `onError`. */
  private async refreshSchema(client: ReactorClient): Promise<void> {
    const refreshId = ++this.schemaRefreshId;

    try {
      const schema = this.normalizeSchema(await client.requestSchema());

      if (this.client !== client || refreshId !== this.schemaRefreshId) {
        return;
      }
      // No schema for this model (wire `null`): leave the cache/event alone
      // rather than surfacing an empty document.
      if (schema === undefined) {
        return;
      }
      this.schema = schema;
      this.emitter.emit('schemaReceived', this.schema);
      // Also surface as a generic runtime-scope message so consumers that
      // filter `runtimeMessage` by `type` (rather than the typed event) see
      // it too — the same reply, two shapes, emitted from the same `schema`
      // value in the same tick, so they can't disagree here. `schemaReceived`
      // /`getSchema()` is the authoritative surface; `runtimeMessage` is the
      // generic fan-out kept for parity with v2 (`core/Reactor.ts`), which
      // does the same dual emit from the same `requestSchema` reply.
      this.emitter.emit('runtimeMessage', { type: 'modelSchema', data: this.schema });
    } catch (cause) {
      if (this.client !== client || refreshId !== this.schemaRefreshId) {
        return;
      }
      this.emitError(cause);
    }
  }

  /** The binding replies with a wire `null` (not an omitted field) when the
   *  model doesn't expose a schema — normalized to `undefined` so callers
   *  get the same "no schema" sentinel `getSchema()` already uses instead
   *  of a document they could otherwise dereference. */
  private normalizeSchema(schema: unknown): ModelSchema | undefined {
    return (schema ?? undefined) as ModelSchema | undefined;
  }

  /** Frees the wasm resource graph, if one exists, and clears the cached
   *  schema. Reusable — unlike `[Symbol.dispose]`, this doesn't set the
   *  permanent `disposed` flag, so a later `connect()`/`reconnect()` lazily
   *  builds a fresh client.
   *
   *  Only called from within `disconnect()`'s queued task, so `client.free()`
   *  is safe to call directly here — the queue already guarantees no other
   *  operation is running concurrently against it. */
  private freeClient(): void {
    this.client?.free();
    this.client = undefined;
    this.clientPromise = undefined;
    this.schema = undefined;
    this.capabilities = undefined;
  }

  /** Tracks `connectionTimings` off the binding's own "connecting" → "waiting"
   *  → "ready" status sequence (see `ReactorStatus`), and starts/stops stats
   *  polling around the "ready" window. */
  private handleStatusChanged(client: ReactorClient, status: ReactorStatus): void {
    switch (status) {
      case 'connecting':
        this.connectStartTime = performance.now();
        this.waitingStartTime = undefined;
        break;
      case 'waiting':
        this.waitingStartTime = performance.now();
        break;
      case 'ready': {
        const readyTime = performance.now();

        if (this.connectStartTime != null) {
          const waitingStartTime = this.waitingStartTime ?? readyTime;

          this.connectionTimings = {
            sessionCreationMs: waitingStartTime - this.connectStartTime,
            transportConnectingMs: readyTime - waitingStartTime,
            totalMs: readyTime - this.connectStartTime,
          };
        }
        this.startStatsPolling(client);
        void this.refreshSchema(client);
        break;
      }
      default:
        this.stopStatsPolling();
    }
  }

  /** Starts both stats pollers: the local one updates `getStats()` and emits
   *  `statsUpdate`, the runtime one sends a client-stats batch. Each owns the
   *  extractor it computes bitrates with, so a bitrate covers its own
   *  poller's interval. */
  private startStatsPolling(client: ReactorClient): void {
    this.stopStatsPolling();
    const extractConnectionStats = createConnectionStatsExtractor();
    const extractTrackStats = createTrackStatsExtractor();

    this.hasSentConnectTime = false;
    this.hasSentClientStats = false;
    this.stopStatsPollers = [
      pollPeerStats(client, LOCAL_STATS_INTERVAL_MS, (report) => {
        this.stats = { ...extractConnectionStats(report), connectionTimings: this.connectionTimings };
        this.emitter.emit('statsUpdate', this.stats);
      }),
      pollPeerStats(client, RUNTIME_REPORT_INTERVAL_MS, (report) => {
        this.reportClientStats(client, report, extractTrackStats);
      }),
    ];
  }

  /** Report one quality batch to the runtime: a reading per negotiated
   *  track — audio and video, sent and received — see
   *  `createTrackStatsExtractor`, and the connection-wide reading — see
   *  `toClientConnectionStat`. The connection reading goes even while no
   *  track has one yet (none negotiated onto a named track, or no codec
   *  reported yet), so the runtime gets the bandwidth estimate, the
   *  round-trip time and the time to connect without waiting on the tracks.
   *  Skipped only when there is nothing at all to say and a batch has already
   *  gone out for this connection, so the first one still carries the
   *  environment; one is due again in `RUNTIME_REPORT_INTERVAL_MS`. The time to connect goes on the first batch
   *  actually sent for this connection — see `hasSentConnectTime`.
   *  `sendClientStats` throws synchronously on a closing connection; caught
   *  here for the same reason `getStats()`'s rejection is above. */
  private reportClientStats(
    client: ReactorClient,
    report: RTCStatsReport,
    extractTrackStats: TrackStatsExtractor,
  ): void {
    const trackStats = extractTrackStats(report, this.trackMapping(), this.pausedTracks());
    const timeToConnectMs = this.hasSentConnectTime ? undefined : this.connectionTimings?.totalMs;
    const connectionStat = toClientConnectionStat(report, timeToConnectMs);

    if (this.hasSentClientStats && trackStats.length === 0 && Object.keys(connectionStat.metrics).length === 0) {
      return;
    }

    try {
      client.sendClientStats(trackStats, connectionStat);
      extractTrackStats.commit();
      this.hasSentClientStats = true;
      if (timeToConnectMs !== undefined) {
        this.hasSentConnectTime = true;
      }
    } catch {
      // Connection may be closing.
    }
  }

  private stopStatsPolling(): void {
    for (const stop of this.stopStatsPollers) {
      stop();
    }
    this.stopStatsPollers = [];
    this.stats = undefined;
  }

  /** Called on every `disconnect()` and on `[Symbol.dispose]`. Leaves
   *  `client`/`schema` alone — that's `freeClient()`'s job, run separately
   *  when `disconnect()` isn't recoverable. `capabilities` is cleared here
   *  regardless, though: unlike the schema, it isn't re-fetched on demand,
   *  so a recoverable disconnect that skips `freeClient()` would otherwise
   *  leave `getCapabilities()` returning the previous session's stale
   *  tracks/commands until a new `capabilitiesReceived` lands. */
  private resetConnectionState(): void {
    this.connectionEpoch += 1;
    this.publishedInputs.clear();
    this.stopStatsPolling();
    this.connectionTimings = undefined;
    this.connectStartTime = undefined;
    this.waitingStartTime = undefined;
    this.capabilities = undefined;
  }

  /** Wraps `cause` and records it as the most recent failure, for a call
   *  that's about to throw rather than emit — see `getLastError()`'s doc
   *  comment. */
  private captureError(cause: unknown): ReactorError {
    const error = toReactorError(cause);

    this._lastError = error;
    return error;
  }

  /** Wraps `cause`, records it as the most recent failure, and fires the
   *  `error` event — the one place recording and emitting happen together,
   *  so `getLastError()` never drifts from what listeners were told. */
  private emitError(cause: unknown): void {
    this.emitter.emit('error', this.captureError(cause));
  }

  private isStepped(): boolean {
    return this.clientOptions.generation === 'stepped';
  }

  private assertNotDisposed(): void {
    if (this.disposed) {
      throw new Error("This Reactor was disposed and can't be used again — construct a new one.");
    }
  }
}
