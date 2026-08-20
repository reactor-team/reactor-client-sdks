import type {
  ClientOptions as WasmClientOptions,
  ConnectOptions,
  JwtSource,
  ReactorError,
  ReactorMessage,
  ReactorStatus,
  TrackCapability,
  TrackDirection,
  TrackKind,
  TrackMappingEntry,
} from './internal/reactor-wasm.types';

export type {
  ConnectOptions,
  JwtSource,
  ReactorError,
  ReactorMessage,
  ReactorStatus,
  TrackCapability,
  TrackDirection,
  TrackKind,
  TrackMappingEntry,
};

/**
 * `Reactor` construction options. Only `modelName` is required.
 *
 * Everything but `jwt` passes straight through to the wasm binding's
 * `ClientOptions` — see `crates/reactor-wasm/src/types.rs` for what each
 * field does. `jwt` lives here instead because the binding takes it as a
 * second constructor argument, not a field; `Reactor` accepts one options
 * object (matching v2's `new Reactor(options)`) and splits it internally.
 */
export interface ReactorOptions extends WasmClientOptions {
  /** A token, or a resolver called before every authenticated request.
   *  Omit for an unauthenticated local runtime. Replaceable later with
   *  `setJwt()`. */
  jwt?: JwtSource;
}

export interface ReactorEventMap {
  statusChanged: (status: ReactorStatus) => void;
  sessionIdChanged: (sessionId: string | undefined) => void;
  error: (error: ReactorError) => void;
  /** Application-scope payload from the model. */
  message: (message: ReactorMessage) => void;
  /** Platform-scope payload — naming matches v2 and Python's `runtime_message`. */
  runtimeMessage: (message: ReactorMessage) => void;
  /** The model's command schema (an OpenAPI document), fired once the
   *  auto-request on `"ready"` lands — see `getSchema()`. */
  schema: (schema: unknown) => void;
  /** Fired when the model side of a track's media becomes available. Only
   *  `name` and `mid` are resolved here — matching the wasm binding, not
   *  v2's `(name, track, stream)` shape — so look up the actual
   *  `MediaStreamTrack`/`MediaStream` via `getTrackByMid`/`getStreamByMid`
   *  (or the by-name variants) once this fires. */
  trackReceived: (name: string, mid: string | undefined) => void;
}

export type ReactorEventName = keyof ReactorEventMap;
