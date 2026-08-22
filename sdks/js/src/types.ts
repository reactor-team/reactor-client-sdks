import type {
  ClientOptions as WasmClientOptions,
  ConnectOptions,
  JwtSource,
  ReactorMessage,
  ReactorStatus,
  SessionInfo,
  TrackCapability,
  TrackDirection,
  TrackKind,
  TrackMappingEntry,
} from './internal/reactor-wasm.types';
import type { ReactorError } from './errors';

export type {
  ConnectOptions,
  JwtSource,
  ReactorMessage,
  ReactorStatus,
  TrackCapability,
  TrackDirection,
  TrackKind,
  TrackMappingEntry,
};

/** The session resource, as the coordinator reports it — see
 *  `Reactor.getSessionInfo()`. */
export type SessionResponse = SessionInfo;

/**
 * Scope tag for `sendCommand()`. This SDK has no generic runtime-scope
 * channel — see `Reactor`'s private `sendRuntimeScopedCommand()` for what
 * `"runtime"` actually does here (routes a couple of recognized commands to
 * their direct equivalents, warns and falls through to a normal send for
 * anything else).
 */
export type MessageScope = 'application' | 'runtime';

/** One command the model declares in its capabilities. */
export interface CommandCapability {
  name: string;
  description?: string;
  schema?: unknown;
}

/**
 * The runtime's declared capabilities for the session — negotiated tracks,
 * and the command set when the model exposes one. Pushed once available (no
 * explicit request needed) — see `Reactor.getCapabilities()`/
 * `capabilitiesReceived`.
 *
 * camelCase — the wasm binding's own wire shape is snake_case
 * (`protocol_version`, `emission_fps`), translated at the boundary in
 * `internal/capabilities.ts` rather than exposed directly here.
 */
export interface Capabilities {
  protocolVersion: string;
  tracks: TrackCapability[];
  commands?: CommandCapability[];
  emissionFps?: number;
}

/** One OpenAPI operation (the `post` of an event/webhook path item). */
export interface ModelSchemaOperation {
  operationId?: string;
  summary?: string;
  description?: string;
  requestBody?: {
    required?: boolean;
    content?: Record<string, { schema?: Record<string, unknown> }>;
  };
  responses?: Record<string, unknown>;
  [key: string]: unknown;
}

/** An OpenAPI path item; the runtime only populates `post`. */
export interface ModelSchemaPathItem {
  post?: ModelSchemaOperation;
  [key: string]: unknown;
}

/**
 * The model's OpenAPI 3.1 schema, returned by `requestSchema()`/cached by
 * `getSchema()`. A pass-through of the runtime's document, not a shape this
 * SDK reshapes: client-triggerable events live under `paths` as
 * `POST /events/<name>` operations, outbound model messages under
 * `webhooks`, and media tracks under `x-reactor.tracks`. Read the parts you
 * need.
 */
export interface ModelSchema {
  openapi: string;
  info: { title: string; version: string; description?: string };
  paths?: Record<string, ModelSchemaPathItem>;
  webhooks?: Record<string, ModelSchemaPathItem>;
  'x-reactor'?: {
    tracks?: Array<{ name: string; kind: string; direction: string }>;
  };
  components?: Record<string, unknown>;
  [key: string]: unknown;
}

/**
 * Severity tier of a content-moderation event delivered as the inner
 * payload of a `runtimeMessage` with `type === "moderation"`. `"warn"`
 * continues the session (informational only); `"terminate"` ends it shortly
 * after the message is dispatched.
 */
export type ModerationAction = 'warn' | 'terminate';

/**
 * Inner payload of a `runtimeMessage` with `type === "moderation"`. Surfaces
 * a content-moderation outcome to the client app on any moderatable input
 * (free-text fields, file uploads) the configured policy flags — subscribe
 * via `reactor.on("runtimeMessage", ...)` and filter on `type`.
 */
export interface ModerationEvent {
  action: ModerationAction;
  /** Modality of the flagged input. `"text"` for string fields, `"image"`
   *  for file-upload payloads with an image MIME type. */
  input_kind: 'text' | 'image';
  /** Name of the inbound command/event whose payload was flagged. */
  command: string;
  /** Category labels that flagged (e.g. `["sexual"]`, `["violence/graphic"]`). */
  categories: string[];
  /** Short human-readable summary suitable for UI rendering. */
  message: string;
}

/**
 * `Reactor` construction options. Only `modelName` is required.
 *
 * Everything but `jwt` passes straight through to the wasm binding's
 * `ClientOptions` — see `crates/reactor-wasm/src/types.rs` for what each
 * field does. `jwt` lives here instead because the binding takes it as a
 * second constructor argument, not a field; `Reactor` accepts one options
 * object and splits it internally.
 */
export interface ReactorOptions extends WasmClientOptions {
  /** A token, or a resolver called before every authenticated request.
   *  Omit for an unauthenticated local runtime. Replaceable later by passing
   *  a new one to `connect()`. */
  jwt?: JwtSource;
}

/**
 * Timing breakdown of the `connect()` handshake, recorded once per connection
 * and included in every subsequent `ConnectionStats` update. All durations
 * are in milliseconds (from `performance.now()`).
 *
 * `sessionCreationMs` covers session creation/adoption (the binding's
 * `"connecting"` phase); `transportConnectingMs` covers the session-ready
 * wait and transport handshake together (the `"waiting"` phase) — that
 * handshake happens entirely inside `reactor-core`, so this can only split
 * on the phase boundaries the binding's status events expose, not on the
 * finer steps within each one.
 */
export interface ConnectionTimings {
  sessionCreationMs: number;
  transportConnectingMs: number;
  /** End-to-end: connect() invocation → status "ready". */
  totalMs: number;
}

export interface ConnectionStats {
  /** ICE candidate-pair round-trip time in milliseconds */
  rtt?: number | undefined;
  /** ICE candidate type: "host", "srflx", "prflx", or "relay" (TURN) */
  candidateType?: string | undefined;
  /** Estimated available incoming bitrate in bits/second */
  availableIncomingBitrate?: number | undefined;
  /** Estimated available outgoing bitrate in bits/second */
  availableOutgoingBitrate?: number | undefined;
  /** Real-time incoming bitrate in bits/second */
  incomingBitrate?: number | undefined;
  /** Real-time outgoing bitrate in bits/second */
  outgoingBitrate?: number | undefined;
  /** Received video frames per second */
  framesPerSecond?: number | undefined;
  /** Ratio of packets lost (0-1) */
  packetLossRatio?: number | undefined;
  /** Network jitter in seconds (from inbound-rtp) */
  jitter?: number | undefined;
  /** Timing breakdown of the initial connection handshake (set once, persisted until disconnect) */
  connectionTimings?: ConnectionTimings | undefined;
  timestamp: number;
}

/** Discriminator on a {@link Clip}. `"snap"` from `requestClip()`, `"recording"` from `requestRecording()`. */
export type ClipKind = 'snap' | 'recording';

/**
 * A finished (or soon-available) clip, from `requestClip()` / `requestRecording()`.
 *
 * The runtime returns immediately on every request — it does not block until
 * the in-progress chunk finalizes. `predictedReadyAtMs` is the runtime's own
 * estimate of when `playlistUrl` becomes fetchable; `fetchPlaylist()` and
 * `downloadClipAsFile()` poll past it until the manifest is actually ready.
 */
export interface Clip {
  sessionId: string;
  kind: ClipKind;
  /** Session-relative seconds since recorder start. */
  startMarker: number;
  endMarker: number;
  nowMarker: number;
  /** Unix epoch in milliseconds. */
  predictedReadyAtMs: number;
  /** Absolute HLS manifest URL — short-lived; re-issuing the request produces a fresh one. */
  playlistUrl: string;
}

export interface ReactorEventMap {
  statusChanged: (status: ReactorStatus) => void;
  sessionIdChanged: (sessionId: string | undefined) => void;
  error: (error: ReactorError) => void;
  /** Application-scope payload from the model. */
  message: (message: ReactorMessage) => void;
  /** Platform-scope payload, same name as the Python SDK's `runtime_message`. */
  runtimeMessage: (message: ReactorMessage) => void;
  /** The model's command schema, fired once the auto-request on `"ready"`
   *  lands — see `getSchema()`. */
  schemaReceived: (schema: ModelSchema) => void;
  /** The runtime's declared capabilities, fired once available — see
   *  `getCapabilities()`. */
  capabilitiesReceived: (capabilities: Capabilities) => void;
  /** Fired when the model side of a track's media becomes available.
   *  `Reactor` resolves the wasm binding's raw `(name, mid)` through
   *  `getTrackByName`/`getStreamByName` before emitting, so callers don't
   *  need an extra step. `reactor-core` only ever dispatches this once the
   *  track is already resolvable (it drops the underlying event entirely
   *  otherwise), so `track`/`stream` are always real values here, never
   *  `undefined`; `mid` rides along as an extra escape hatch for callers
   *  that want the binding's own identifier (e.g. for
   *  `getTrackByMid`/`getStreamByMid`). */
  trackReceived: (
    name: string,
    track: MediaStreamTrack,
    stream: MediaStream,
    mid: string | undefined,
  ) => void;
  /** Fired every `STATS_INTERVAL_MS` while the session is "ready" — see
   *  `getStats()`. */
  statsUpdate: (stats: ConnectionStats) => void;
}

export type ReactorEventName = keyof ReactorEventMap;
