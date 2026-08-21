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
  RecorderDisabledError,
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

describe('ReactorError.component (compatibility field, best-effort)', () => {
  it('defaults to "api" whenever an HTTP status is present, regardless of code', () => {
    expect(new ReactorError('boom', { code: 'TRANSPORT_ERROR', status: 500 }).component).toBe(
      'api',
    );
    expect(new ReactorError('boom', { code: 'UNAUTHORIZED', status: 401 }).component).toBe('api');
  });

  it('defaults to "gpu" for data-channel/transport codes with no status', () => {
    for (const code of [
      'TRANSPORT_ERROR',
      'DISCONNECTED',
      'MESSAGE_TOO_LARGE',
      'REQUEST_TIMEOUT',
      'SESSION_TERMINAL',
      'DECODE_FAILED',
    ]) {
      expect(new ReactorError('boom', { code }).component).toBe('gpu');
    }
  });

  it('defaults to "api" for every other code with no status, including an unrecognized one', () => {
    for (const code of [
      'NETWORK_ERROR',
      'UNAUTHORIZED',
      'NOT_FOUND',
      'CONFLICT',
      'RATE_LIMITED',
      'BAD_REQUEST',
      'SERVER_ERROR',
      'VERSION_MISMATCH',
      'INVALID_STATE',
      'ABORTED',
      'SOME_NEW_PLATFORM_CODE',
    ]) {
      expect(new ReactorError('boom', { code }).component).toBe('api');
    }
  });

  it('defaults to "api" for a failure with no code or status at all', () => {
    expect(new ReactorError('boom').component).toBe('api');
  });

  it('an explicit component overrides the status/code-based default', () => {
    expect(
      new ReactorError('boom', { code: 'TRANSPORT_ERROR', component: 'api' }).component,
    ).toBe('api');
  });
});

describe("toReactorError()'s code display mapping (compatibility, best-effort)", () => {
  it('collapses every canonical code for a call-specific operation to the one fixed code that call already had', () => {
    expect(
      toReactorError({ operation: 'publishTrack', code: 'TRANSPORT_ERROR' }).code,
    ).toBe('TRACK_PUBLISH_FAILED');
    expect(
      toReactorError({ operation: 'publishTrack', code: 'UNAUTHORIZED' }).code,
    ).toBe('TRACK_PUBLISH_FAILED');
    expect(
      toReactorError({ operation: 'unpublishTrack', code: 'TRANSPORT_ERROR' }).code,
    ).toBe('TRACK_UNPUBLISH_FAILED');
    expect(toReactorError({ operation: 'reconnect', code: 'NETWORK_ERROR' }).code).toBe(
      'RECONNECTION_FAILED',
    );
    expect(toReactorError({ operation: 'connect', code: 'UNAUTHORIZED' }).code).toBe(
      'CONNECTION_FAILED',
    );
  });

  it('still picks the precise typed subclass even while code is collapsed to the fixed string', () => {
    const error = toReactorError({ operation: 'connect', code: 'UNAUTHORIZED' });

    expect(error).toBeInstanceOf(UnauthorizedError);
    expect(error.code).toBe('CONNECTION_FAILED');
  });

  it('splits sendCommand by code, matching its two previously-distinct failures', () => {
    expect(
      toReactorError({ operation: 'sendCommand', code: 'INVALID_STATE' }).code,
    ).toBe('NOT_READY');
    expect(
      toReactorError({ operation: 'sendCommand', code: 'TRANSPORT_ERROR' }).code,
    ).toBe('MESSAGE_SEND_FAILED');
  });

  it('maps an unprompted failure (no operation at all) to the previous unprompted transport error', () => {
    expect(toReactorError({ code: 'TRANSPORT_ERROR' }).code).toBe('GPU_CONNECTION_ERROR');
  });

  it('does not mislabel a purely local failure (no operation, no canonical code either) as an unprompted transport drop', () => {
    // Unlike the case above, nothing here ever reached the binding — no
    // `code` was reported at all — so this must stay INTERNAL_ERROR rather
    // than being relabelled GPU_CONNECTION_ERROR just because `operation`
    // happens to be missing too.
    expect(toReactorError({}).code).toBe('INTERNAL_ERROR');
    expect(toReactorError(new Error('wasm import failed')).code).toBe('INTERNAL_ERROR');
  });

  it("keeps reactor-core's own code for an operation that never had a fixed code of its own", () => {
    for (const operation of ['pauseTrack', 'resumeTrack', 'uploadFile', 'requestSchema', 'setJwt']) {
      expect(toReactorError({ operation, code: 'UNAUTHORIZED' }).code).toBe('UNAUTHORIZED');
    }
  });

  it("derives component from reactor-core's canonical code, not the collapsed display code", () => {
    // TRACK_PUBLISH_FAILED isn't in the gpu-code allowlist, but the real
    // canonical code (TRANSPORT_ERROR) is — component must use the latter.
    const error = toReactorError({ operation: 'publishTrack', code: 'TRANSPORT_ERROR' });

    expect(error.code).toBe('TRACK_PUBLISH_FAILED');
    expect(error.component).toBe('gpu');
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
    [RecorderDisabledError, 'RECORDER_DISABLED'],
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
