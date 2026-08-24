/**
 * Every Reactor failure carries `code`, `recoverable`, `status`, `operation`,
 * `retry_after_ms` and `timestamp_ms` — `reactor-core`'s canonical shape,
 * shared by every SDK on it. `timestamp`/`retryAfter` are kept alongside
 * under their previous names for compatibility — see their own doc comments
 * below — rather than dropped, since this package's public API may only
 * grow.
 *
 * There is **one class**, not two: the `error` event payload and a rejected
 * call's error are the same shape and the same instance type.
 *
 * `ReactorError` is the base of a typed hierarchy keyed by `code` —
 * `reactor-core`'s own per-failure-kind classification — so `instanceof
 * UnauthorizedError` and `code === 'UNAUTHORIZED'` are equivalent, one just
 * typed. Codes are open-ended (the platform can send its own), so an
 * unrecognized one falls back to the base class itself rather than
 * throwing.
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
}

export class ReactorError extends Error {
  /** The code this class stands for. `ReactorError` itself is the fallback,
   *  so it claims none and takes whatever the payload reported. */
  static readonly code: string = 'INTERNAL_ERROR';

  /** `reactor-core`'s own canonical code for this failure — the same value
   *  used to pick this instance's class (see the typed subclasses below for
   *  the vocabulary). Open-ended: an unrecognized code still constructs the
   *  base class, with `code` set to whatever was reported. */
  readonly code: string;
  /** Whether the same call could succeed later. */
  readonly recoverable: boolean;
  /** The HTTP status, when the failure came from one. */
  readonly status: number | undefined;
  /** Which call failed, e.g. "connect", "sendCommand". */
  readonly operation: string | undefined;
  /** Backoff hint the platform sent. */
  readonly retry_after_ms: number | undefined;
  /** When `reactor-core` reported this failure. For a purely local one (no
   *  payload at all — see {@link ReactorErrorOptions}), this is synthesized
   *  as the construction time instead, since there's nothing to report —
   *  not a value read off the wire. */
  readonly timestamp_ms: number;

  /** Same value as `retry_after_ms`, under its previous name. Kept for
   *  compatibility — prefer `retry_after_ms`. */
  readonly retryAfter: number | undefined;
  /** Same value as `timestamp_ms`, under its previous name. Kept for
   *  compatibility — prefer `timestamp_ms`. */
  readonly timestamp: number;

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
  }
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
 * plain string, or a purely local failure with no payload at all — a wasm
 * load/constructor throw, a call on a disposed client) still becomes a
 * `ReactorError` rather than propagating an untyped failure, falling back to
 * `INTERNAL_ERROR` when there's no code to report.
 */
export function toReactorError(cause: unknown): ReactorError {
  if (cause instanceof ReactorError) {
    return cause;
  }
  const payload = (cause ?? {}) as ReactorErrorLike;
  const message = typeof payload.message === 'string' ? payload.message : String(cause);
  const ErrorClass = errorForCode(payload.code);

  return new ErrorClass(message, {
    code: payload.code,
    recoverable: payload.recoverable,
    status: payload.status,
    operation: payload.operation,
    retry_after_ms: payload.retry_after_ms,
    timestamp_ms: payload.timestamp_ms,
  });
}
