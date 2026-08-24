import { describe, expect, it } from 'vitest';
import {
  AbortedError,
  BadRequestError,
  ConflictError,
  DecodeError,
  DisconnectedError,
  errorForCode,
  InvalidStateError,
  MessageTooLargeError,
  NetworkError,
  NotFoundError,
  ReactorError,
  RateLimitedError,
  RequestTimeoutError,
  ServerError,
  SessionTerminalError,
  toReactorError,
  TransportError,
  UnauthorizedError,
  VersionMismatchError,
} from './errors';

describe('ReactorError', () => {
  it('carries the canonical fields, with sensible defaults', () => {
    const error = new ReactorError('boom');

    expect(error).toBeInstanceOf(Error);
    expect(error.name).toBe('ReactorError');
    expect(error.message).toBe('boom');
    expect(error.code).toBe('INTERNAL_ERROR');
    expect(error.recoverable).toBe(false);
    expect(error.status).toBeUndefined();
    expect(error.operation).toBeUndefined();
    expect(error.retry_after_ms).toBeUndefined();
    expect(typeof error.timestamp_ms).toBe('number');
  });

  it('mirrors retry_after_ms/timestamp_ms as retryAfter/timestamp, kept for compatibility', () => {
    const error = new ReactorError('rate limited', {
      code: 'RATE_LIMITED',
      retry_after_ms: 2000,
      timestamp_ms: 1_700_000_000_000,
    });

    expect(error.retryAfter).toBe(2000);
    expect(error.timestamp).toBe(1_700_000_000_000);
  });

  it('takes every option through, including an explicit timestamp_ms', () => {
    const error = new ReactorError('rate limited', {
      code: 'RATE_LIMITED',
      recoverable: true,
      status: 429,
      operation: 'sendCommand',
      retry_after_ms: 2000,
      timestamp_ms: 1_700_000_000_000,
    });

    expect(error).toMatchObject({
      code: 'RATE_LIMITED',
      recoverable: true,
      status: 429,
      operation: 'sendCommand',
      retry_after_ms: 2000,
      timestamp_ms: 1_700_000_000_000,
    });
  });
});

describe("toReactorError()'s code passthrough", () => {
  it("reports reactor-core's own canonical code untouched, for every operation", () => {
    // No operation gets a fixed compatibility code any more — `code` is
    // always whatever `reactor-core` actually reported.
    for (const operation of [
      'connect',
      'reconnect',
      'publishTrack',
      'unpublishTrack',
      'sendCommand',
      'pauseTrack',
      'resumeTrack',
      'uploadFile',
      'requestSchema',
      'setJwt',
    ]) {
      expect(toReactorError({ operation, code: 'UNAUTHORIZED' }).code).toBe('UNAUTHORIZED');
    }
  });

  it('picks the typed subclass matching the canonical code, regardless of operation', () => {
    const error = toReactorError({ operation: 'connect', code: 'UNAUTHORIZED' });

    expect(error).toBeInstanceOf(UnauthorizedError);
    expect(error.code).toBe('UNAUTHORIZED');
  });

  it('passes through the canonical code for an unprompted failure (no operation at all)', () => {
    expect(toReactorError({ code: 'TRANSPORT_ERROR' }).code).toBe('TRANSPORT_ERROR');
  });

  it('falls back to INTERNAL_ERROR for a purely local failure with no code or operation', () => {
    // Nothing here ever reached the binding — no `code` was reported at all.
    expect(toReactorError({}).code).toBe('INTERNAL_ERROR');
    expect(toReactorError(new Error('wasm import failed')).code).toBe('INTERNAL_ERROR');
  });
});

describe('typed subclasses', () => {
  const cases: Array<[new (message: string) => ReactorError, string]> = [
    [NetworkError, 'NETWORK_ERROR'],
    [UnauthorizedError, 'UNAUTHORIZED'],
    [NotFoundError, 'NOT_FOUND'],
    [ConflictError, 'CONFLICT'],
    [RateLimitedError, 'RATE_LIMITED'],
    [BadRequestError, 'BAD_REQUEST'],
    [ServerError, 'SERVER_ERROR'],
    [VersionMismatchError, 'VERSION_MISMATCH'],
    [DecodeError, 'DECODE_FAILED'],
    [InvalidStateError, 'INVALID_STATE'],
    [SessionTerminalError, 'SESSION_TERMINAL'],
    [MessageTooLargeError, 'MESSAGE_TOO_LARGE'],
    [TransportError, 'TRANSPORT_ERROR'],
    [DisconnectedError, 'DISCONNECTED'],
    [RequestTimeoutError, 'REQUEST_TIMEOUT'],
    [AbortedError, 'ABORTED'],
  ];

  it.each(cases)('%s defaults its code and extends ReactorError', (ErrorClass, code) => {
    const error = new ErrorClass('failed');

    expect(error).toBeInstanceOf(ReactorError);
    expect(error).toBeInstanceOf(Error);
    expect(error.code).toBe(code);
    // The base class's `name` — matches the wasm binding's own convention
    // of stamping every rejected call's Error with `name === "ReactorError"`
    // regardless of which typed subclass it becomes.
    expect(error.name).toBe('ReactorError');
  });

  it('an explicit code overrides the subclass default rather than being relabelled', () => {
    const error = new UnauthorizedError('odd payload', { code: 'SOMETHING_ELSE' });

    expect(error).toBeInstanceOf(UnauthorizedError);
    expect(error.code).toBe('SOMETHING_ELSE');
  });
});

describe('errorForCode', () => {
  it('returns the matching subclass for a known code', () => {
    expect(errorForCode('UNAUTHORIZED')).toBe(UnauthorizedError);
    expect(errorForCode('DISCONNECTED')).toBe(DisconnectedError);
  });

  it('falls back to the base class for an unknown or missing code', () => {
    expect(errorForCode('SOME_NEW_PLATFORM_CODE')).toBe(ReactorError);
    expect(errorForCode(undefined)).toBe(ReactorError);
  });
});

describe('toReactorError', () => {
  it('wraps a plain onError-shaped payload into the matching subclass', () => {
    // `operation: 'requestSchema'` deliberately has no fixed display code of
    // its own (see the dedicated describe block below for that mapping), so
    // `code` passes through unchanged here — keeping this test focused on
    // subclass selection and field passthrough.
    const wrapped = toReactorError({
      code: 'NOT_FOUND',
      message: 'no such session',
      recoverable: false,
      status: 404,
      operation: 'requestSchema',
      timestamp_ms: 42,
    });

    expect(wrapped).toBeInstanceOf(NotFoundError);
    expect(wrapped).toMatchObject({
      code: 'NOT_FOUND',
      message: 'no such session',
      recoverable: false,
      status: 404,
      operation: 'requestSchema',
      timestamp_ms: 42,
    });
  });

  it('wraps a rejected-call Error (Object.assign-ed fields, name === "ReactorError") the same way', () => {
    const thrown = Object.assign(new Error('nope'), {
      name: 'ReactorError',
      code: 'CONFLICT',
      recoverable: true,
      timestamp_ms: 99,
    });

    const wrapped = toReactorError(thrown);

    expect(wrapped).toBeInstanceOf(ConflictError);
    expect(wrapped.message).toBe('nope');
    expect(wrapped.recoverable).toBe(true);
  });

  it('is idempotent: an already-typed ReactorError passes through unchanged', () => {
    const original = new TransportError('dropped');

    expect(toReactorError(original)).toBe(original);
  });

  it('falls back to the base class for an unrecognized code', () => {
    const wrapped = toReactorError({
      code: 'BRAND_NEW_CODE',
      message: 'model-defined rejection',
      operation: 'requestSchema',
    });

    expect(wrapped.constructor).toBe(ReactorError);
    expect(wrapped.code).toBe('BRAND_NEW_CODE');
  });

  it('still produces a ReactorError for a value with no usable shape at all', () => {
    const wrapped = toReactorError('just a string');

    expect(wrapped).toBeInstanceOf(ReactorError);
    expect(wrapped.message).toBe('just a string');
    expect(wrapped.code).toBe('INTERNAL_ERROR');
  });
});
