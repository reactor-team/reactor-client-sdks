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
  /** Console log level. Default "warn". */
  logLevel?: "off" | "error" | "warn" | "info" | "debug" | "trace";
}

/** Per-connect options. Every field optional. */
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

/** A token, or a resolver called before every authenticated request.
 *  Returning "" sends no Authorization header. */
export type JwtSource = string | (() => string | Promise<string>);

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
  timestamp_ms: number;
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
}
