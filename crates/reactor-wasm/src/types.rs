//! The TypeScript face of the binding.
//!
//! `wasm-bindgen` types a `JsValue` parameter as `any`, which would push the
//! whole contract into prose and leave the TypeScript SDK guessing. So the
//! object shapes are declared once here, in TypeScript, and the API's signatures
//! refer to them — `wasm-pack` emits the declarations into `reactor_wasm.d.ts`
//! and the SDK gets a checked boundary.
//!
//! Field naming follows the wire, not JavaScript: the payloads that come out of
//! the core are serialized by serde, so `protocol_version`, `retry_after_ms`,
//! `playlist_url` and friends are snake_case, exactly as the FFI's JSON reports
//! them. Only the *options* this crate defines itself are camelCase, because
//! nothing on the wire constrains them and they are written by hand in JS.

use wasm_bindgen::prelude::*;

#[wasm_bindgen(typescript_custom_section)]
const TYPESCRIPT_DECLARATIONS: &'static str = r#"
export type ReactorStatus = "disconnected" | "connecting" | "waiting" | "ready";
export type TrackKind = "audio" | "video";
export type TrackDirection = "recvonly" | "sendonly";
export type GenerationMode = "streamed" | "stepped";
/** Byte layout of a frame given to `pushVideoFrame`. */
export type PixelFormat = "rgba" | "rgb" | "bgra";

/** Construction options. Only `modelName` is required. */
export interface ClientOptions {
  modelName: string;
  /** Coordinator base URL. Defaults to the cloud coordinator, or to
   *  http://localhost:8080 when `local` is set. */
  apiUrl?: string;
  /** Talk to a local runtime's HTTP API instead of the cloud coordinator. */
  local?: boolean;
  /** Reported to the coordinator in `client_info`. Defaults to "js". */
  sdkType?: string;
  sdkVersion?: string;
  /** Resume every recvonly track on connect. Default true. */
  autoResumeTracks?: boolean;
  /** Extra headers sent on every coordinator and signaling request (not on
   *  presigned uploads or clip downloads). Names the SDK sets itself
   *  (Authorization, Content-Type, Content-Length, Reactor-API-*,
   *  Reactor-WebRTC-Version), names a browser forbids (Cookie, Origin,
   *  Sec-*, Proxy-*, …), a name given twice in any case, and values that are
   *  not visible ASCII are rejected at construction. A browser sends a CORS preflight for any custom header, so
   *  the API's allowed headers must list every name used here. */
  extraHeaders?: Record<string, string>;
  /** Free-form model arguments, sent on session creation. */
  extraArgs?: Record<string, unknown>;
  /** Keep-alive period. 0 disables the heartbeat. Default 10000. */
  heartbeatIntervalMs?: number;
  /** How long to wait for the transport to come up. Default 30000. */
  readyTimeoutMs?: number;
  controlRequestTimeoutMs?: number;
  clipRequestTimeoutMs?: number;
  /** Session-readiness poll attempts. Default 20. */
  maxSessionAttempts?: number;
  /** SDP-answer poll attempts. Default 6. */
  maxSdpAttempts?: number;
  /** Initial delay before the first SDP-answer poll retry, in ms. Default 200. */
  sdpBackoffInitialMs?: number;
  /** Cap on the exponential backoff between SDP-answer poll retries, in ms. Default 15000. */
  sdpBackoffMaxMs?: number;
  /** Growth factor applied to the delay between SDP-answer poll retries. Default 2. */
  sdpBackoffMultiplier?: number;
  /** Preset tracks, when known ahead of time — builds the SDP offer
   *  concurrently with the session-ready poll rather than after. */
  modelTracks?: TrackCapability[];
  /** How the session generates: "streamed" (the default), continuously over
   *  media tracks, or "stepped", one `step()` at a time. `connect()` fails
   *  with GENERATION_MODE_MISMATCH when the runtime did not start the
   *  session in this mode. */
  generation?: GenerationMode;
  /** How long `step()` waits for a step's result, generation included.
   *  Default 300000. */
  stepTimeoutMs?: number;
  /** Console log level. Default "warn". */
  logLevel?: "off" | "error" | "warn" | "info" | "debug" | "trace";
}

/** Per-connect options. Every field optional. Also accepted by `reconnect()`,
 *  which only honors `maxAttempts` — the rest only make sense when
 *  establishing a session in the first place. */
export interface ConnectOptions {
  /** Adopt an existing session instead of creating one. An adopting client
   *  never ends the session it joined. */
  sessionId?: string;
  /** Use a connection id already registered under that session. */
  connectionId?: number;
  /** Override `ClientOptions.autoResumeTracks` for this connection and the
   *  reconnects that follow it. */
  autoResumeTracks?: boolean;
  /** SDP-answer poll attempts before giving up. */
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
  /** The SDP media-section id this track negotiated onto. */
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

/** The session resource, as the coordinator reports it. */
export interface SessionInfo {
  session_id: string;
  state: string;
  model?: { name: string; version?: string };
  cluster?: string;
  server_info?: { server_version: string };
  selected_transport?: { protocol: string; version: string };
  capabilities?: Capabilities;
  /** Fields newer servers add are preserved rather than dropped. */
  [key: string]: unknown;
}

/** A completed upload, to pass in a command's `uploads`. */
export interface FileRef {
  upload_id: string;
  name: string;
  mime_type: string;
  size: number;
}

/** A captured clip or recording. */
export interface Clip {
  session_id: string;
  /** "snap" for requestClip, "recording" for requestRecording. */
  kind: string;
  start_marker: number;
  end_marker: number;
  now_marker: number;
  predicted_ready_at_ms: number;
  /** Absolute HLS manifest URL. */
  playlist_url: string;
}

export interface ReactorMessage {
  type: string;
  data: unknown;
}

/** A failure, in the terms a caller can act on. Rejected calls throw an `Error`
 *  carrying these same fields, with `name === "ReactorError"`. */
export interface ReactorError {
  /** A stable code — DISCONNECTED, REQUEST_TIMEOUT, UNAUTHORIZED, … — or one
   *  the platform sent, so unknown values must be tolerated. */
  code: string;
  message: string;
  /** Whether the same call could succeed later. */
  recoverable: boolean;
  /** The HTTP status, when the failure came from one. */
  status?: number;
  /** Which call failed, e.g. "connect", "sendCommand". */
  operation?: string;
  /** Backoff hint the platform sent. */
  retry_after_ms?: number;
  /** For `step_refused` and `step_command_failed`, the part of the step
   *  request at fault, e.g. `"commands[2]"`. */
  entry?: string;
  /** For `step_command_failed`, the code the failing handler raised. */
  command_code?: string;
  timestamp_ms: number;
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
  /** Video only: the encoder (a sent track) or decoder (a received one) the
   *  browser runs, from getStats()'s encoderImplementation /
   *  decoderImplementation, e.g. "libvpx" or "ExternalDecoder". */
  codecImplementation?: string;
  /** Video only: whether that implementation is the hardware one, from
   *  powerEfficientEncoder / powerEfficientDecoder. */
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
  encoding: "raw" | "unspecified";
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
  /** The step's number in the session, counted from 1. */
  index: number;
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
"#;

#[wasm_bindgen]
extern "C" {
    #[wasm_bindgen(typescript_type = "ClientOptions")]
    pub type ClientOptionsInput;

    #[wasm_bindgen(typescript_type = "ConnectOptions")]
    pub type ConnectOptionsInput;

    #[wasm_bindgen(typescript_type = "JwtSource")]
    pub type JwtSourceInput;

    #[wasm_bindgen(typescript_type = "Record<string, unknown>")]
    pub type CommandData;

    #[wasm_bindgen(typescript_type = "Record<string, FileRef>")]
    pub type UploadsInput;

    #[wasm_bindgen(typescript_type = "ClientTrackStat[]")]
    pub type ClientTrackStatsInput;

    #[wasm_bindgen(typescript_type = "ClientConnectionStat | undefined")]
    pub type ClientConnectionStatInput;

    #[wasm_bindgen(typescript_type = "ReactorMessage | undefined")]
    pub type CommandReply;

    #[wasm_bindgen(typescript_type = "ReactorStatus")]
    pub type Status;

    #[wasm_bindgen(typescript_type = "Capabilities | undefined")]
    pub type CapabilitiesOutput;

    #[wasm_bindgen(typescript_type = "SessionInfo | undefined")]
    pub type SessionInfoOutput;

    #[wasm_bindgen(typescript_type = "ReactorError | undefined")]
    pub type ReactorErrorOutput;

    #[wasm_bindgen(typescript_type = "TrackCapability[]")]
    pub type TracksOutput;

    #[wasm_bindgen(typescript_type = "TrackMappingEntry[]")]
    pub type TrackMappingOutput;

    #[wasm_bindgen(typescript_type = "string[]")]
    pub type StringsOutput;

    #[wasm_bindgen(typescript_type = "Clip")]
    pub type ClipOutput;

    #[wasm_bindgen(typescript_type = "FileRef")]
    pub type FileRefOutput;

    /// An OpenAPI document — deliberately unstructured; the SDK owns its shape.
    #[wasm_bindgen(typescript_type = "unknown")]
    pub type SchemaOutput;

    #[wasm_bindgen(typescript_type = "StatusListener")]
    pub type StatusListener;

    #[wasm_bindgen(typescript_type = "SessionIdListener")]
    pub type SessionIdListener;

    #[wasm_bindgen(typescript_type = "MessageListener")]
    pub type MessageListener;

    #[wasm_bindgen(typescript_type = "TrackListener")]
    pub type TrackListener;

    #[wasm_bindgen(typescript_type = "ErrorListener")]
    pub type ErrorListener;

    #[wasm_bindgen(typescript_type = "CapabilitiesListener")]
    pub type CapabilitiesListener;

    #[wasm_bindgen(typescript_type = "Record<string, unknown> | null")]
    pub type StepStateInput;

    #[wasm_bindgen(typescript_type = "StepCommand[]")]
    pub type StepCommandsInput;

    #[wasm_bindgen(typescript_type = "StepResult")]
    pub type StepResultOutput;

    #[wasm_bindgen(typescript_type = "PixelFormat")]
    pub type PixelFormatInput;
}
