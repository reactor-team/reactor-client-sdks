/**
 * Every Reactor failure carries `code`, `recoverable`, `status`, `operation`,
 * `retry_after_ms` and `timestamp_ms` — `reactor-core`'s canonical shape,
 * shared by every SDK on it. `timestamp`/`retryAfter`/`component` are kept
 * alongside as compatibility fields — see their own doc comments below —
 * rather than dropped, since this package's public API may only grow.
 *
 * There is **one class**, not two: the `error` event payload and a rejected
 * call's error are the same shape and the same instance type.
 *
 * `ReactorError` is the base of a typed hierarchy keyed by the *canonical*
 * code `reactor-core` reported — catch `UnauthorizedError` instead of
 * matching a code string; that classification is always precise, regardless
 * of what `code` itself ends up displaying (see `code`'s own doc comment).
 * Codes are open-ended (the platform can send its own), so an unrecognized
 * one falls back to the base class itself rather than throwing.
 */
/** Fields a `ReactorError` constructor accepts, all optional — an
 *  unrecognized code still constructs cleanly on the base class, and a
 *  purely local failure (no payload at all) still constructs cleanly too. */
export interface ReactorErrorOptions {
  code?: string | undefined;
  recoverable?: boolean | undefined;
  status?: number | undefined;
  operation?: string | undefined;
  retry_after_ms?: number | undefined;
  timestamp_ms?: number | undefined;
  /** Overrides the `status`/`code`-based default — see `component`'s doc
   *  comment. Only meaningful for hand-built errors (tests, mainly); nothing
   *  in this package ever passes it, since it has no signal to source one
   *  from. */
  component?: 'api' | 'gpu' | undefined;
}

export class ReactorError extends Error {
  /** The code this class stands for. `ReactorError` itself is the fallback,
   *  so it claims none and takes whatever the payload reported. */
  static readonly code: string = 'INTERNAL_ERROR';

  /**
   * A stable code — but not always `reactor-core`'s own canonical one.
   *
   * For a failure this package can attribute to one call (`operation` is
   * `"connect"`, `"reconnect"`, `"publishTrack"`, `"unpublishTrack"`, or
   * `"sendCommand"`) `code` is the single fixed string this package already
   * reported for that call before it adopted `reactor-core`'s shared,
   * per-failure-kind vocabulary — e.g. every `connect()` failure reports
   * `"CONNECTION_FAILED"` here regardless of the underlying reason, exactly
   * as before. An unprompted transport drop (no `operation` at all) reports
   * `"GPU_CONNECTION_ERROR"`, likewise unconditionally. This exists so a
   * caller already matching one of those fixed strings keeps matching,
   * unchanged, on an upgrade — `code`'s *value* only ever grows more
   * detailed within a call this package already had a fixed string for, it
   * never disappears or gets renamed here.
   *
   * For every other call (`pauseTrack`, `resumeTrack`, `uploadFile`,
   * `requestSchema`, `setJwt`, `disconnect`) — none of which ever had a
   * fixed code of their own — `code` is `reactor-core`'s own canonical
   * value directly (`NETWORK_ERROR`, `UNAUTHORIZED`, ... — the same
   * vocabulary the typed subclasses below are keyed by), since there is
   * nothing prior to preserve.
   *
   * The one thing `code` deliberately does *not* give you, for the five
   * calls above, is which of `reactor-core`'s specific reasons caused the
   * failure — `instanceof` is what carries that, always accurately,
   * regardless of what `code` reads as. Prefer it over matching `code`.
   */
  readonly code: string;
  /** Whether the same call could succeed later. */
  readonly recoverable: boolean;
  /** The HTTP status, when the failure came from one. */
  readonly status: number | undefined;
  /** Which call failed, e.g. "connect", "sendCommand". */
  readonly operation: string | undefined;
  /** Backoff hint the platform sent. */
  readonly retry_after_ms: number | undefined;
  readonly timestamp_ms: number;

  /** Same value as `retry_after_ms`, under its previous name. Kept for
   *  compatibility — prefer `retry_after_ms`. */
  readonly retryAfter: number | undefined;
  /** Same value as `timestamp_ms`, under its previous name. Kept for
   *  compatibility — prefer `timestamp_ms`. */
  readonly timestamp: number;
  /**
   * Which tier reported this — kept for compatibility, but best-effort only:
   * `reactor-core`'s error model doesn't track which tier failed (that's
   * exactly why `component` doesn't exist in its own shape). Derived from
   * the failure itself — `reactor-core`'s own canonical code, never the
   * (possibly collapsed) value `code` displays — not from which method was
   * called:
   *
   * - A `status` present at all means an HTTP response came back from the
   *   coordinator — that can only happen on the API tier, so `"api"`.
   * - Otherwise, the canonical code decides: the ones that only arise once a
   *   session is already talking to the model over the data channel/
   *   transport (`TRANSPORT_ERROR`, `DISCONNECTED`, `MESSAGE_TOO_LARGE`,
   *   `REQUEST_TIMEOUT`, `SESSION_TERMINAL`, `DECODE_FAILED`) are `"gpu"`.
   * - Every other code (including one this package doesn't recognize) is
   *   `"api"` — the coordinator-facing default.
   *
   * Pass `component` explicitly to override this default.
   */
  readonly component: 'api' | 'gpu';

  constructor(message: string, options: ReactorErrorOptions = {}) {
    super(message);
    this.name = 'ReactorError';
    // An explicit code wins, so an unrecognized one survives on the base
    // class rather than being relabelled as the fallback it was routed to.
    this.code = options.code ?? new.target.code;
    this.recoverable = options.recoverable ?? false;
    this.status = options.status;
    this.operation = options.operation;
    this.retry_after_ms = options.retry_after_ms;
    this.retryAfter = options.retry_after_ms;
    this.timestamp_ms = options.timestamp_ms ?? Date.now();
    this.timestamp = this.timestamp_ms;
    this.component = options.component ?? componentForFailure(this.status, this.code);
  }
}

/** Codes that only arise once a session is already talking to the model
 *  over the data channel/transport — see `component`'s doc comment. */
const GPU_CODES = new Set([
  'TRANSPORT_ERROR',
  'DISCONNECTED',
  'MESSAGE_TOO_LARGE',
  'REQUEST_TIMEOUT',
  'SESSION_TERMINAL',
  'DECODE_FAILED',
]);

function componentForFailure(status: number | undefined, code: string): 'api' | 'gpu' {
  if (status !== undefined) {
    return 'api';
  }
  return GPU_CODES.has(code) ? 'gpu' : 'api';
}

/** `operation` → the one fixed code this package already reported for that
 *  call — see `code`'s own doc comment. `sendCommand` is handled separately
 *  in `codeForDisplay`, since it used to split by failure kind rather than
 *  have one fixed code. */
const FIXED_CODE_BY_OPERATION: Record<string, string> = {
  publishTrack: 'TRACK_PUBLISH_FAILED',
  unpublishTrack: 'TRACK_UNPUBLISH_FAILED',
  reconnect: 'RECONNECTION_FAILED',
  connect: 'CONNECTION_FAILED',
};

/**
 * What `code` should actually display for a failure `reactor-core` reported
 * with this `operation` and canonical `code` — see `ReactorError.code`'s doc
 * comment for the reasoning. Always returns a string: `canonicalCode` itself
 * for any call with no fixed code of its own to preserve.
 *
 * `isBindingCode` distinguishes the two ways `operation` can be missing: a
 * real unprompted transport drop from the binding (it still reported its own
 * canonical `code`, just no `operation`) versus a purely local failure that
 * never reached the binding at all (a wasm load/constructor throw, a call on
 * a disposed client) — which has neither. Only the former gets relabelled;
 * the latter keeps whatever `canonicalCode` already resolved to
 * (`INTERNAL_ERROR` for one this package doesn't recognize).
 */
function codeForDisplay(
  operation: string | undefined,
  canonicalCode: string,
  isBindingCode: boolean,
): string {
  if (operation === 'sendCommand') {
    return canonicalCode === 'INVALID_STATE' ? 'NOT_READY' : 'MESSAGE_SEND_FAILED';
  }
  if (operation === undefined) {
    // An unprompted transport drop, not tied to a call the caller made —
    // the same kind of unprompted event used to have its own fixed code too.
    return isBindingCode ? 'GPU_CONNECTION_ERROR' : canonicalCode;
  }
  return FIXED_CODE_BY_OPERATION[operation] ?? canonicalCode;
}

/** The request never got a reply — DNS, TLS, a refused socket. */
export class NetworkError extends ReactorError {
  static override readonly code = 'NETWORK_ERROR';
}

/** 401 or 403: the token is missing, expired, or not scoped for this call. */
export class UnauthorizedError extends ReactorError {
  static override readonly code = 'UNAUTHORIZED';
}

/** 404: no such model, session or upload. */
export class NotFoundError extends ReactorError {
  static override readonly code = 'NOT_FOUND';
}

/** 409: the session is in a state that does not allow this — usually a
 *  session left orphaned by a previous run that went away without
 *  disconnecting. */
export class ConflictError extends ReactorError {
  static override readonly code = 'CONFLICT';
}

/** 429: too many requests. `retry_after_ms` carries the server's
 *  `Retry-After` when it sent one; `undefined` means back off on your own
 *  terms rather than retrying immediately. */
export class RateLimitedError extends ReactorError {
  static override readonly code = 'RATE_LIMITED';
}

/** A 4xx other than the ones above: the request itself was wrong. */
export class BadRequestError extends ReactorError {
  static override readonly code = 'BAD_REQUEST';
}

/** 5xx: the coordinator failed, and the same request may work later. */
export class ServerError extends ReactorError {
  static override readonly code = 'SERVER_ERROR';
}

/** This client and the platform disagree on the protocol — upgrade the SDK. */
export class VersionMismatchError extends ReactorError {
  static override readonly code = 'VERSION_MISMATCH';
}

/** A reply arrived and could not be understood. */
export class DecodeError extends ReactorError {
  static override readonly code = 'DECODE_FAILED';
}

/** The operation is not allowed from the state the client is in — most often
 *  a call that needs a live session, made before `connect()` or after the
 *  status left `"ready"`. */
export class InvalidStateError extends ReactorError {
  static override readonly code = 'INVALID_STATE';
}

/** The session reached a state it cannot leave. Start a new one. */
export class SessionTerminalError extends ReactorError {
  static override readonly code = 'SESSION_TERMINAL';
}

/** The payload exceeds what the data channel accepts — send large content
 *  with `uploadFile()` and pass the `FileRef` instead of embedding it
 *  inline in a command. */
export class MessageTooLargeError extends ReactorError {
  static override readonly code = 'MESSAGE_TOO_LARGE';
}

/** The media transport failed. */
export class TransportError extends ReactorError {
  static override readonly code = 'TRANSPORT_ERROR';
}

/** The connection went away, either dropped mid-request or lost after being
 *  established. `reconnect()` is the way back. */
export class DisconnectedError extends ReactorError {
  static override readonly code = 'DISCONNECTED';
}

/** The operation was sent and nothing came back in time. */
export class RequestTimeoutError extends ReactorError {
  static override readonly code = 'REQUEST_TIMEOUT';
}

/** The operation was abandoned before it finished. */
export class AbortedError extends ReactorError {
  static override readonly code = 'ABORTED';
}

/**
 * A `requestClip()`/`requestRecording()` call failed because the model's
 * recorder is disabled or has crashed.
 *
 * Unlike every other code here, `RECORDER_DISABLED` isn't platform-sent —
 * `reactor-core` produces it by matching known reason strings on an
 * otherwise free-text `ClipFailed` message, as a stopgap until that message
 * carries a structured reason (tracked as REA-5403). Treat it as best-effort:
 * a clip failure for the same underlying reason may still arrive as the
 * base `ReactorError` (`code: "INTERNAL_ERROR"`) if the reason text ever
 * changes upstream.
 */
export class RecorderDisabledError extends ReactorError {
  static override readonly code = 'RECORDER_DISABLED';
}

/** Every code this package has a class for. Anything else — a platform code
 *  for a rejected request — falls back to `ReactorError` with `code` set to
 *  it. */
const ERROR_CLASSES = [
  NetworkError,
  UnauthorizedError,
  NotFoundError,
  ConflictError,
  RateLimitedError,
  BadRequestError,
  ServerError,
  VersionMismatchError,
  DecodeError,
  InvalidStateError,
  SessionTerminalError,
  MessageTooLargeError,
  TransportError,
  DisconnectedError,
  RequestTimeoutError,
  AbortedError,
  RecorderDisabledError,
] as const;

interface ReactorErrorClass {
  readonly code: string;
  new (message: string, options?: ReactorErrorOptions): ReactorError;
}

const BY_CODE = new Map<string, ReactorErrorClass>(
  ERROR_CLASSES.map((errorClass) => [errorClass.code, errorClass]),
);

/** The exception class for `code`, or the base class for anything unknown. */
export function errorForCode(code: string | undefined): ReactorErrorClass {
  return (code && BY_CODE.get(code)) || ReactorError;
}

/** A record shape the wasm binding hands over, either as an `onError` event
 *  payload or `Object.assign`ed onto a rejected call's thrown `Error`. */
interface ReactorErrorLike {
  code?: string;
  message?: string;
  recoverable?: boolean;
  status?: number;
  operation?: string;
  retry_after_ms?: number;
  timestamp_ms?: number;
}

/**
 * Builds the typed exception for whatever the wasm binding handed over —
 * the plain object an `onError` event delivers, or the `Error` a rejected
 * call throws (message plus the same fields, `Object.assign`ed on).
 *
 * Already a `ReactorError`? Returned as-is, so wrapping is idempotent.
 * Anything else not shaped like the binding's payload (a bug elsewhere, a
 * plain string) still becomes a `ReactorError` rather than propagating an
 * untyped failure.
 *
 * This is the one place `reactor-core`'s canonical code and this package's
 * displayed `code` diverge — see `ReactorError.code`'s doc comment. The
 * canonical code picks the typed subclass and feeds `component`'s default;
 * `codeForDisplay()` picks what the constructed instance's own `.code`
 * property actually shows.
 */
export function toReactorError(cause: unknown): ReactorError {
  if (cause instanceof ReactorError) {
    return cause;
  }
  const payload = (cause ?? {}) as ReactorErrorLike;
  const message = typeof payload.message === 'string' ? payload.message : String(cause);
  const ErrorClass = errorForCode(payload.code);
  const canonicalCode = payload.code ?? ErrorClass.code;

  return new ErrorClass(message, {
    code: codeForDisplay(payload.operation, canonicalCode, payload.code !== undefined),
    recoverable: payload.recoverable,
    status: payload.status,
    operation: payload.operation,
    retry_after_ms: payload.retry_after_ms,
    timestamp_ms: payload.timestamp_ms,
    component: componentForFailure(payload.status, canonicalCode),
  });
}
