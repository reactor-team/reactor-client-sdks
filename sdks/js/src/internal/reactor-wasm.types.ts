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

export type ReactorStatus = "disconnected" | "connecting" | "waiting" | "ready";
export type TrackKind = "audio" | "video";
export type TrackDirection = "recvonly" | "sendonly";

export interface ClientOptions {
  modelName: string;
  apiUrl?: string;
  local?: boolean;
  sdkType?: string;
  sdkVersion?: string;
  autoResumeTracks?: boolean;
  extraArgs?: Record<string, unknown>;
  heartbeatIntervalMs?: number;
  readyTimeoutMs?: number;
  controlRequestTimeoutMs?: number;
  clipRequestTimeoutMs?: number;
  maxSessionAttempts?: number;
  maxSdpAttempts?: number;
  logLevel?: "off" | "error" | "warn" | "info" | "debug" | "trace";
}

export interface ConnectOptions {
  sessionId?: string;
  connectionId?: number;
  autoResumeTracks?: boolean;
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
  timestamp_ms: number;
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
  reconnect(): Promise<void>;

  sendCommand(
    command: string,
    data?: Record<string, unknown>,
    uploads?: Record<string, FileRef>,
  ): Promise<ReactorMessage | undefined>;
  requestSchema(): Promise<unknown>;

  publishTrack(name: string, track: MediaStreamTrack): Promise<void>;
  unpublishTrack(name: string): Promise<void>;
  pauseTrack(name: string): Promise<void>;
  resumeTrack(name: string): Promise<void>;
  tracks(): TrackCapability[];
  trackMapping(): TrackMappingEntry[];
  pausedTracks(): string[];

  requestClip(durationSeconds: number): Promise<Clip>;
  requestRecording(): Promise<Clip>;

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
