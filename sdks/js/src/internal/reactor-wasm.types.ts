/**
 * The `reactor-wasm` contract, mirrored by hand.
 *
 * `crates/reactor-wasm` generates the real `reactor_wasm.d.ts` at
 * `mise run build:wasm` time (see `crates/reactor-wasm/src/types.rs` and
 * `client.rs`), but that output isn't committed — the JS SDK depends on a
 * build step, not a checked-in file. This file re-states that same contract
 * so `sdks/js` type-checks on a fresh checkout too, before anyone has run the
 * wasm build. Node resolution always prefers a real, installed module over an
 * ambient ad-hoc declaration, so once `reactor_wasm.js`/`.d.ts` exist next to
 * this at runtime, they're what actually loads — this file only fills the gap
 * until then.
 *
 * Keep in sync with the two files above. Field naming follows the wire, not
 * JavaScript: payloads that come out of the core keep snake_case
 * (`protocol_version`, `retry_after_ms`, `playlist_url`, ...); only the
 * options this crate defines itself are camelCase.
 */

export type ReactorStatus = 'disconnected' | 'connecting' | 'waiting' | 'ready';
export type TrackKind = 'audio' | 'video';
export type TrackDirection = 'recvonly' | 'sendonly';
export type GenerationMode = 'streamed' | 'stepped';
/** Byte layout of a frame given to `pushVideoFrame`. */
export type PixelFormat = 'rgba' | 'rgb' | 'bgra';

export interface ClientOptions {
  modelName: string;
  apiUrl?: string;
  local?: boolean;
  sdkType?: string;
  sdkVersion?: string;
  autoResumeTracks?: boolean;
  extraHeaders?: Record<string, string>;
  extraArgs?: Record<string, unknown>;
  heartbeatIntervalMs?: number;
  readyTimeoutMs?: number;
  controlRequestTimeoutMs?: number;
  clipRequestTimeoutMs?: number;
  maxSessionAttempts?: number;
  maxSdpAttempts?: number;
  sdpBackoffInitialMs?: number;
  sdpBackoffMaxMs?: number;
  sdpBackoffMultiplier?: number;
  modelTracks?: TrackCapability[];
  /** "streamed" (the default) or "stepped". */
  generation?: GenerationMode;
  /** How long `step()` waits for a step's result, generation included.
   *  Default 300000. */
  stepTimeoutMs?: number;
  logLevel?: 'off' | 'error' | 'warn' | 'info' | 'debug' | 'trace';
}

export interface ConnectOptions {
  sessionId?: string;
  connectionId?: number;
  autoResumeTracks?: boolean;
  maxAttempts?: number;
}

/** What a token is being resolved for. `sessionId` is set for every request
 *  that targets an existing session and unset for session creation. A
 *  resolver that mints a replacement token must bind it to that session
 *  (`resources.sessions.bind`), or the token cannot reach the session. */
export interface JwtRequestContext {
  sessionId?: string;
}

/** A token, or a resolver called before every authenticated request with a
 *  `JwtRequestContext`. Returning "" sends no Authorization header. */
export type JwtSource = string | ((context?: JwtRequestContext) => string | Promise<string>);

export interface TrackCapability {
  name: string;
  kind: TrackKind;
  direction: TrackDirection;
}

export interface TrackMappingEntry extends TrackCapability {
  mid: string;
}

export interface CommandCapability {
  name: string;
  description?: string;
  schema?: unknown;
}

export interface Capabilities {
  protocol_version: string;
  tracks: TrackCapability[];
  commands?: CommandCapability[];
  emission_fps?: number;
}

export interface SessionInfo {
  session_id: string;
  state: string;
  model?: { name: string; version?: string };
  cluster?: string;
  server_info?: { server_version: string };
  selected_transport?: { protocol: string; version: string };
  capabilities?: Capabilities;
  [key: string]: unknown;
}

export interface FileRef {
  upload_id: string;
  name: string;
  mime_type: string;
  size: number;
}

/** One track's WebRTC quality reading taken from `getStats()`, for
 *  `sendClientStats`. */
export interface ClientTrackStat {
  /** When this reading was taken, epoch milliseconds (e.g. `Date.now()`). */
  timestamp: number;
  trackName: string;
  kind: TrackKind;
  direction: TrackDirection;
  /** The codec in use for this track, e.g. "vp9", "h264", "opus". */
  codec: string;
  /** Whether the client currently has this track paused. */
  paused: boolean;
  /** Named numeric readings, keyed by W3C webrtc-stats names in snake_case,
   *  e.g. bitrate_bps, frames_per_second, packets_lost, packets_received,
   *  jitter_ms, round_trip_time_ms, frames_decoded, frames_dropped,
   *  frame_width, frame_height, nack_count, keyframe_requests. A reading the
   *  client couldn't take is left out, never sent as 0. */
  metrics: Record<string, number>;
  /** Where this track's frames spent their time since the previous batch,
   *  one entry per stage (e.g. "encode", "jitter_buffer", "decode"). Optional;
   *  an entry with no frames or a time that is not a finite, non-negative
   *  number is left out. */
  frameStages?: FrameStage[];
  /** The encoder (sent track) or decoder (received track) a video track
   *  runs, as `getStats()` names it in `encoderImplementation` /
   *  `decoderImplementation`. Video only; omit it when unknown. */
  codecImplementation?: string;
  /** Whether that implementation is power efficient, from
   *  `powerEfficientEncoder` / `powerEfficientDecoder`. Video only; omit it
   *  when unknown. */
  hardwareCodec?: boolean;
}

/** The time a track's frames spent in one stage of their trip, over one
 *  batch's window. The average per frame is `totalMs / frames`.
 *  encode_wait, packetize and pacer are different: they come from the
 *  engine's timing frames, a sample of about one slow frame per second, so
 *  `frames` counts sampled frames and `totalMs / frames` leans toward the
 *  slow ones. Treat their average as an upper bound, not a mean. */
export interface FrameStage {
  name: string;
  /** The time all `frames` frames spent in the stage together. */
  totalMs: number;
  frames: number;
}

/** A connection-wide reading, not tied to any one track — for
 *  `sendClientStats`. Sent on every batch; one-time facts such as
 *  time_to_connect_ms ride only the first batch after connecting. */
export interface ClientConnectionStat {
  timestamp: number;
  /** Named numeric readings, e.g. available_outgoing_bitrate_bps,
   *  connection_rtt_ms, time_to_connect_ms. */
  metrics: Record<string, number>;
}

export interface Clip {
  session_id: string;
  kind: string;
  start_marker: number;
  end_marker: number;
  now_marker: number;
  predicted_ready_at_ms: number;
  playlist_url: string;
}

export interface ReactorMessage {
  type: string;
  data: unknown;
}

/** A failure, in the terms a caller can act on. Rejected calls throw an
 *  `Error` carrying these same fields, with `name === "ReactorError"`. */
export interface ReactorError {
  code: string;
  message: string;
  recoverable: boolean;
  status?: number;
  operation?: string;
  retry_after_ms?: number;
  /** For `step_refused` and `step_command_failed`, the part of the step
   *  request at fault, e.g. `"commands[2]"`. */
  entry?: string;
  /** For `step_command_failed`, the code the failing handler raised. */
  command_code?: string;
  timestamp_ms: number;
}

/** One command of a step, run after its state, in order. */
export interface StepCommand {
  command: string;
  /** The command's arguments. Omitted or null for none. */
  data?: Record<string, unknown> | null;
}

/** One frame a step produced. Hand-built by the binding rather than
 *  serialized, so camelCase, unlike the wire payloads above. */
export interface StepFrame {
  /** How the frame travelled; "raw" is the only one decoded today. */
  encoding: 'raw' | 'unspecified';
  /** Element type, in NumPy notation: "uint8" (video), "int16" (audio). */
  dtype: string;
  /** (height, width, 3) RGB for video, (channels, samples) planar PCM for
   *  audio. */
  shape: number[];
  /** The array, row-major, as the typed array `dtype` names — a Uint8Array
   *  for video, an Int16Array for audio, raw bytes for a dtype with no
   *  typed-array counterpart. */
  data:
    | Uint8Array
    | Int8Array
    | Uint16Array
    | Int16Array
    | Uint32Array
    | Int32Array
    | Float32Array
    | Float64Array;
  /** What the model attached to the frame. */
  metadata?: Uint8Array;
  /** When the model says the frame was captured, in microseconds. */
  captureTimeUs?: number;
}

/** The frames one output track produced in a step, in production order. */
export interface StepTrack {
  track: string;
  kind: TrackKind;
  /** The rate the track's frames play at, as the runtime reported it:
   *  frames per second for video, samples per second for audio. 0 when the
   *  runtime did not say. */
  rate: number;
  frames: StepFrame[];
}

/** A file the model kept with a step. */
export interface StepFile {
  name: string;
  /** Its MIME type, e.g. "image/png". */
  contentType: string;
  /** Its size in bytes. */
  size: number;
}

/** What one step produced. */
export interface StepResult {
  /** The step's number in the session, counted from 1: the `step` of the
   *  session's step results (`GET /sessions/{id}/steps/{step}`). */
  step: number;
  tracks: StepTrack[];
  /** Seconds the step's `generate()` took, or the time the model reported for the step. */
  elapsed: number;
  /** Files the model kept with the step. Only their description: the bytes
   *  are served over HTTP from the session's step results. Empty when the
   *  model kept none or the result was not saved. */
  files: StepFile[];
  /** Set when the step ran and the model reported a failure. Such a step
   *  still resolves; only a refused step rejects. */
  error?: string;
}

export type StatusListener = (status: ReactorStatus) => void;
export type SessionIdListener = (sessionId: string | undefined) => void;
export type MessageListener = (message: ReactorMessage) => void;
export type TrackListener = (name: string, mid: string | undefined) => void;
export type ErrorListener = (error: ReactorError) => void;
export type CapabilitiesListener = (capabilities: Capabilities) => void;

/** The `#[wasm_bindgen]` class itself, as JavaScript sees it. */
export declare class ReactorClient {
  constructor(options: ClientOptions, jwt?: JwtSource | null);

  setJwt(jwt?: JwtSource | null): void;

  connect(options?: ConnectOptions): Promise<void>;
  disconnect(): Promise<void>;
  /** Only `maxAttempts` applies here — the rest of `ConnectOptions` only
   *  makes sense when establishing a session in the first place. */
  reconnect(options?: ConnectOptions): Promise<void>;

  sendCommand(
    command: string,
    data?: Record<string, unknown>,
    uploads?: Record<string, FileRef>,
  ): Promise<ReactorMessage | undefined>;
  requestSchema(): Promise<unknown>;

  /** In a stepped session `track` is optional and ignored (with a warning):
   *  the call only claims the track, whose frames come from
   *  `pushVideoFrame`/`pushAudioFrame`. */
  publishTrack(name: string, track?: MediaStreamTrack | null): Promise<void>;
  unpublishTrack(name: string): Promise<void>;
  pauseTrack(name: string): Promise<void>;
  resumeTrack(name: string): Promise<void>;
  setTrackBitrate(
    name: string,
    minBps: number | undefined,
    maxBps: number | undefined,
  ): Promise<void>;
  tracks(): TrackCapability[];
  trackMapping(): TrackMappingEntry[];
  pausedTracks(): string[];

  /** Stepped sessions only. Rejects with the core's code (`step_refused`,
   *  `step_not_ready`, `step_command_failed`, `step_session_ended`,
   *  `step_result_too_large`, `MESSAGE_TOO_LARGE`, ...). */
  step(state?: Record<string, unknown> | null, commands?: StepCommand[] | null): Promise<StepResult>;
  /** Stepped sessions only. Buffers the frame for the next `step()`; throws
   *  synchronously on a frame that does not match its dimensions. */
  pushVideoFrame(
    name: string,
    data: Uint8Array,
    width: number,
    height: number,
    format?: PixelFormat | null,
    metadata?: Uint8Array | null,
    captureTimeUs?: number | null,
  ): void;
  /** Stepped sessions only. Interleaved PCM, `channels` samples per frame. */
  pushAudioFrame(name: string, samples: Int16Array, sampleRate: number, channels: number): void;
  clearStepInputs(): void;

  requestClip(durationSeconds: number): Promise<Clip>;
  requestRecording(): Promise<Clip>;

  /** Fire-and-forget: returns once the batch is handed to the control
   *  channel, not once the runtime has processed it. Throws synchronously
   *  if the batch is malformed or the channel can't take it. */
  sendClientStats(trackStats: ClientTrackStat[], connectionStat?: ClientConnectionStat): void;

  uploadFile(file: Blob, name?: string): Promise<FileRef>;

  status(): ReactorStatus;
  sessionId(): string | undefined;
  sessionInfo(): SessionInfo | undefined;
  capabilities(): Capabilities | undefined;
  lastError(): ReactorError | undefined;

  getPeerConnection(): RTCPeerConnection | undefined;
  getTrackByMid(mid: string): MediaStreamTrack | undefined;
  getStreamByMid(mid: string): MediaStream | undefined;
  getTrackByName(name: string): MediaStreamTrack | undefined;
  getStreamByName(name: string): MediaStream | undefined;

  onStatusChanged(listener: StatusListener): void;
  onSessionIdChanged(listener: SessionIdListener): void;
  onMessage(listener: MessageListener): void;
  onRuntimeMessage(listener: MessageListener): void;
  onTrackReceived(listener: TrackListener): void;
  onError(listener: ErrorListener): void;
  onCapabilitiesReceived(listener: CapabilitiesListener): void;

  /** Generated by `wasm-bindgen` for every exported class. Cancels the pump,
   *  the dispatcher and the heartbeat, and closes the peer connection — see
   *  `crates/reactor-wasm/README.md`'s "Lifetime" note. */
  free(): void;
}

/** The module's default export: `wasm-bindgen --target web`'s init function. */
export type ReactorWasmInit = (
  input?: string | URL | Request | Response | BufferSource | WebAssembly.Module,
) => Promise<unknown>;

export interface ReactorWasmModule {
  default: ReactorWasmInit;
  ReactorClient: typeof ReactorClient;
}
