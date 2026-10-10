import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { SessionAuth } from './fetch-token';
import { sessionAuth } from './fetch-token';

// A stand-in for `token-server.ts` with the same rules, plus a stand-in for
// the SDK: `createSession` records which token each session was created with,
// and registration succeeds only when the bearer is that token.
const T0 = 1_700_000_000_000;
const HOUR = 3_600;

let minted: number;
let creatorLifetimes: number[];
let requests: string[];
const createdBy = new Map<string, string>();
const registered = new Set<string>();

function json(status: number, body: unknown): Promise<Response> {
  return Promise.resolve(new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } }));
}

function nowSeconds(): number {
  return Math.floor(Date.now() / 1000);
}

function backend(input: string | URL | Request, init?: RequestInit): Promise<Response> {
  const url = new URL(String(input), 'http://localhost');

  requests.push(url.pathname + url.search);
  if (url.pathname === '/api/token') {
    const sessionId = url.searchParams.get('session_id');

    if (sessionId === null) {
      minted++;
      return json(200, { jwt: `creator-${minted}`, expires_at: nowSeconds() + (creatorLifetimes.shift() ?? HOUR) });
    }
    if (!registered.has(sessionId)) {
      return json(403, { error: 'that session is not registered for this browser' });
    }
    return json(200, { jwt: `bound-${sessionId}`, expires_at: nowSeconds() + HOUR });
  }
  if (url.pathname === '/api/session') {
    const bearer = /^Bearer (.+)$/.exec(new Headers(init?.headers).get('authorization') ?? '')?.[1];
    const { sessionId } = JSON.parse(String(init?.body)) as { sessionId: string };

    if (createdBy.get(sessionId) !== bearer) {
      return json(403, { error: 'that token is not bound to that session' });
    }
    registered.add(sessionId);
    return Promise.resolve(new Response(null, { status: 204 }));
  }
  return json(404, {});
}

// What the SDK does on `connect()`: ask the resolver with no session, then
// create the session with whatever came back.
async function createSession(auth: SessionAuth, sessionId: string): Promise<string> {
  const jwt = await auth.jwt();

  createdBy.set(sessionId, jwt);
  return jwt;
}

describe('sessionAuth', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(T0);
    minted = 0;
    creatorLifetimes = [];
    requests = [];
    createdBy.clear();
    registered.clear();
    vi.stubGlobal('fetch', vi.fn(backend));
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.useRealTimers();
  });

  it('returns the creation token for its session while it lives, then a bound replacement', async () => {
    const auth = sessionAuth();

    await createSession(auth, 'S');

    expect(await auth.jwt({ sessionId: 'S' })).toBe('creator-1');
    await auth.register('S');

    vi.setSystemTime(T0 + HOUR * 1000);
    expect(await auth.jwt({ sessionId: 'S' })).toBe('bound-S');
    expect(requests).toContain('/api/token?session_id=S');
  });

  it('coalesces concurrent creation mints into one request', async () => {
    const [a, b] = [sessionAuth(), sessionAuth()];
    const tokens = await Promise.all([a.jwt(), b.jwt(), a.jwt()]);

    expect(tokens).toEqual(['creator-1', 'creator-1', 'creator-1']);
    expect(minted).toBe(1);
  });

  it('mints a bound token for a session another connection created and registered', async () => {
    const creator = sessionAuth();

    await createSession(creator, 'S');
    await creator.register('S');

    expect(await sessionAuth().jwt({ sessionId: 'S' })).toBe('bound-S');
  });

  // The creation token has 61 s left, so it is fresh for the create and
  // inside the margin 2 s later. The session cannot be registered before
  // `connect()` returns, so the only token that can reach it is the one that
  // created it.
  it('keeps the creation token for a session created inside the refresh margin', async () => {
    const auth = sessionAuth();

    creatorLifetimes = [61];
    await createSession(auth, 'S');
    vi.setSystemTime(T0 + 2_000);

    await expect(auth.jwt({ sessionId: 'S' })).resolves.toBe('creator-1');
    await expect(auth.register('S')).resolves.toBeUndefined();

    vi.setSystemTime(T0 + 61_000);
    expect(await auth.jwt({ sessionId: 'S' })).toBe('bound-S');
  });

  // The SDK names the session right after creating it, then polls readiness
  // for as long as the model takes. Here that outlives the creation token's
  // use margin before `connect()` returns, so nothing has registered the
  // session yet when a bound replacement is first due.
  it('keeps reaching a session whose connect outlives the creation token margin', async () => {
    const auth = sessionAuth();

    creatorLifetimes = [61];
    await createSession(auth, 'S');
    expect(await auth.jwt({ sessionId: 'S' })).toBe('creator-1');

    vi.setSystemTime(T0 + 57_000);
    await expect(auth.jwt({ sessionId: 'S' })).resolves.toBe('bound-S');
    await expect(auth.register('S')).resolves.toBeUndefined();
  });

  // After creating A, the same instance adopts B, which another connection
  // created and registered. B must not inherit A's creation token.
  it('mints a bound token for a session adopted after this instance created another', async () => {
    const [auth, other] = [sessionAuth(), sessionAuth()];

    await createSession(auth, 'A');
    await auth.register('A');
    await createSession(other, 'B');
    await other.register('B');

    await expect(auth.jwt({ sessionId: 'B' })).resolves.toBe('bound-B');
  });

  // Two connections create sessions while the creation token rotates. A was
  // created with creator-1, which is still alive, so A must keep getting it.
  it('pairs each session with the token that created it across a rotation', async () => {
    const [a, b] = [sessionAuth(), sessionAuth()];

    creatorLifetimes = [61];
    await createSession(a, 'A');
    vi.setSystemTime(T0 + 2_000);
    await createSession(b, 'B');

    expect(await b.jwt({ sessionId: 'B' })).toBe('creator-2');
    await expect(a.jwt({ sessionId: 'A' })).resolves.toBe('creator-1');
  });
});
