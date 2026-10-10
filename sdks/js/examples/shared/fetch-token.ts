// Browser side of the routes in `token-server.ts`, one instance per
// connection. A scoped token reaches only sessions it created or was bound
// to, so `jwt` returns the token that created this connection's session while
// that token lives, and a token bound to the session after.
import type { JwtRequestContext, JwtResolver } from '@reactor-team/js-sdk';

interface Token {
  jwt: string;
  expiresAtMs: number;
}

export interface SessionAuth {
  /** The resolver to hand `connect()`. */
  jwt: JwtResolver;
  /** Resolves once the backend knows this browser created `sessionId`.
   *  Registration starts by itself the first time the SDK names a session
   *  this instance created, which is right after creating it; this waits
   *  for it and reports a failure. Call it after `connect()`. */
  register(sessionId: string): Promise<void>;
}

// A token this close to expiry creates no session: it has to outlive the
// registration that follows creation.
const CREATE_MARGIN_MS = 60_000;
// A token this close to expiry is replaced before use, so an in-flight
// request never crosses its expiry.
const USE_MARGIN_MS = 5_000;

const inflight = new Map<string, Promise<Token>>();

export function sessionAuth(): SessionAuth {
  // A creation token stays cached for further creations while it is fresh.
  let creation: Token | null = null;
  // What the last creation call returned, until the SDK names the session it
  // created. That first naming pairs the two and is consumed once, so a
  // session adopted later does not inherit it.
  let pending: Token | null = null;
  // The token in use per session: the one that created it, or a bound one.
  const tokens = new Map<string, Token>();
  // Registration of each session this instance created, started at pairing.
  const registrations = new Map<string, Promise<void>>();

  async function jwt(context?: JwtRequestContext): Promise<string> {
    const sessionId = context?.sessionId;

    if (sessionId === undefined) {
      if (!usable(creation, CREATE_MARGIN_MS)) {
        creation = await mint(null);
      }
      pending = creation;
      return creation.jwt;
    }
    let token = tokens.get(sessionId);

    if (token === undefined && pending !== null) {
      token = pending;
      pending = null;
      registrations.set(sessionId, registerWith(sessionId, token.jwt));
    }
    if (!usable(token, USE_MARGIN_MS)) {
      // The backend binds only a registered session, so let the registration
      // that pairing started land first.
      await registrations.get(sessionId);
      token = await mint(sessionId);
    }
    tokens.set(sessionId, token);
    return token.jwt;
  }

  async function register(sessionId: string): Promise<void> {
    const token = await jwt({ sessionId });
    let registration = registrations.get(sessionId);

    if (registration === undefined) {
      registration = registerWith(sessionId, token);
      registrations.set(sessionId, registration);
    }
    await registration;
  }

  return { jwt, register };
}

function usable(token: Token | null | undefined, marginMs: number): token is Token {
  return token !== null && token !== undefined && Date.now() < token.expiresAtMs - marginMs;
}

function registerWith(sessionId: string, jwt: string): Promise<void> {
  const request = (async () => {
    const r = await fetch('/api/session', {
      method: 'POST',
      headers: { 'content-type': 'application/json', Authorization: `Bearer ${jwt}` },
      body: JSON.stringify({ sessionId }),
    });

    if (!r.ok) {
      throw new Error(await errorOf(r, `session registration failed: ${r.status}`));
    }
  })();

  // Someone awaits this later, through `jwt` or `register`; until then a
  // failure must not surface as an unhandled rejection.
  request.catch(() => undefined);
  return request;
}

async function errorOf(r: Response, fallback: string): Promise<string> {
  const body = (await r.json().catch(() => ({}))) as { error?: string };

  return body.error ?? fallback;
}

function mint(sessionId: string | null): Promise<Token> {
  const key = sessionId ?? '';
  const pending = inflight.get(key);

  if (pending) {
    return pending;
  }
  const request = (async () => {
    try {
      const r = await fetch(sessionId === null ? '/api/token' : `/api/token?session_id=${encodeURIComponent(sessionId)}`);

      if (!r.ok) {
        throw new Error(await errorOf(r, `token fetch failed: ${r.status}`));
      }
      const { jwt, expires_at: expiresAt } = (await r.json()) as { jwt: string; expires_at: number };

      return { jwt, expiresAtMs: expiresAt * 1000 };
    } finally {
      inflight.delete(key);
    }
  })();

  inflight.set(key, request);
  return request;
}
