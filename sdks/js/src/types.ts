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
  /** Fired when the model side of a track's media becomes available.
   *  Matches v2's `(name, track, stream)` shape — `Reactor` resolves the
   *  wasm binding's raw `(name, mid)` through `getTrackByName`/
   *  `getStreamByName` before emitting, so callers don't need an extra
   *  step. `track`/`stream` are `undefined` if the media isn't resolvable
   *  yet at the moment this fires; `mid` rides along as an extra escape
   *  hatch for callers that want the binding's own identifier (e.g. for
   *  `getTrackByMid`/`getStreamByMid`). */
  trackReceived: (
    name: string,
    track: MediaStreamTrack | undefined,
    stream: MediaStream | undefined,
    mid: string | undefined,
  ) => void;
}

export type ReactorEventName = keyof ReactorEventMap;
