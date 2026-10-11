// The two backend routes every example's `vite.config.ts` serves, so
// `REACTOR_API_KEY` never reaches the browser:
//   GET  /api/token[?session_id=S]  mint a JWT; with S, bound to S and able
//                                   to create nothing, for a browser that
//                                   registered S.
//   POST /api/session {sessionId}   register S. The proof is `GET /sessions/S`
//                                   with the browser's current token, which
//                                   Reactor answers only for a token bound to S.
// The visitor cookie is an opaque id over a server-side list; a forged value
// maps to nothing, and Reactor re-checks the bind at mint.
import { randomBytes } from 'node:crypto';
import type { IncomingMessage, ServerResponse } from 'node:http';

const COOKIE = 'reactor_visitor';
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
// Larger than any registration body. The route stops reading past this
// rather than buffer whatever a reachable client sends.
const MAX_BODY_BYTES = 4096;

type Middleware = (req: IncomingMessage, res: ServerResponse, next: () => void) => void;

interface DevServer {
  middlewares: { use(path: string, handler: Middleware): void };
}

export interface ReactorTokenPluginOptions {
  /** Must match `main.ts`'s `MODEL_NAME` — the minted token is scoped to it. */
  modelName: string;
  /** Coordinator base URL. Default `https://api.reactor.inc`. */
  apiUrl?: string;
}

export function reactorTokenPlugin({ modelName, apiUrl = 'https://api.reactor.inc' }: ReactorTokenPluginOptions) {
  // Visitor cookie → the sessions that browser proved it holds a token for.
  const sessionsByVisitor = new Map<string, Set<string>>();

  return {
    name: 'reactor-token',
    configureServer(server: DevServer) {
      server.middlewares.use('/api/token', (req, res) => {
        handleToken(req, res).catch((error: unknown) => fail(res, error));
      });
      server.middlewares.use('/api/session', (req, res) => {
        handleRegister(req, res).catch((error: unknown) => fail(res, error));
      });
    },
  };

  async function handleToken(req: IncomingMessage, res: ServerResponse): Promise<void> {
    const apiKey = process.env.REACTOR_API_KEY;

    res.setHeader('content-type', 'application/json');
    res.setHeader('cache-control', 'no-store');
    if (!apiKey) {
      reply(res, 500, { error: 'REACTOR_API_KEY is not set — see README.md' });
      return;
    }

    const visitor = visitorOf(req, res);
    const sessionId = new URL(req.url ?? '/', 'http://localhost').searchParams.get('session_id');
    const resources: Record<string, unknown> = { models: { match: [modelName] } };

    if (sessionId !== null) {
      if (!sessionsByVisitor.get(visitor)?.has(sessionId)) {
        reply(res, 403, { error: 'that session is not registered for this browser' });
        return;
      }
      resources.sessions = { bind: [sessionId] };
    }

    try {
      const upstream = await fetch(`${apiUrl}/tokens`, {
        method: 'POST',
        headers: { 'Reactor-API-Key': apiKey, 'content-type': 'application/json' },
        // Session-scoped, not the unscoped `null` body: a scoped token is
        // what the coordinator expects a browser to hold. Binding to an
        // existing session (above) is the only way a fresh token reaches
        // one this token did not create.
        body: JSON.stringify({ authorization_details: [{ type: 'session', resources }] }),
      });

      if (!upstream.ok) {
        res.statusCode = upstream.status;
        res.end(await upstream.text());
        return;
      }
      const { jwt, expires_at: expiresAt } = (await upstream.json()) as { jwt: string; expires_at: number };

      reply(res, 200, { jwt, expires_at: expiresAt });
    } catch (error) {
      console.error(`[reactor-token] fetching ${apiUrl}/tokens failed:`, error);
      reply(res, 502, { error: `could not reach ${apiUrl} — see the dev server log` });
    }
  }

  async function handleRegister(req: IncomingMessage, res: ServerResponse): Promise<void> {
    res.setHeader('content-type', 'application/json');
    if (req.method !== 'POST') {
      reply(res, 405, { error: 'POST only' });
      return;
    }
    // A cross-site page can make the browser send this request with the
    // cookie attached; it cannot forge the browser's own origin.
    const origin = req.headers.origin;

    if (origin !== undefined && origin !== `http://${req.headers.host}` && origin !== `https://${req.headers.host}`) {
      reply(res, 403, { error: 'cross-origin registration is refused' });
      return;
    }
    const bearer = /^Bearer (.+)$/.exec(req.headers.authorization ?? '')?.[1];
    let body: unknown;

    try {
      body = await readJson(req);
    } catch (error) {
      if (error instanceof BodyTooLarge) {
        res.setHeader('connection', 'close');
        reply(res, 413, { error: `the body is larger than ${MAX_BODY_BYTES} bytes` });
      } else {
        reply(res, 400, { error: 'the body is not JSON' });
      }
      return;
    }
    const sessionId = (body as { sessionId?: unknown } | null)?.sessionId;

    if (!bearer || typeof sessionId !== 'string' || !UUID.test(sessionId)) {
      reply(res, 400, { error: 'send Authorization: Bearer <jwt> and a JSON body {"sessionId": "<uuid>"}' });
      return;
    }

    // Reactor answers 200 only for a token bound to that session, so this is
    // the proof the browser created it (or was handed a token that did).
    let check: Response;

    try {
      check = await fetch(`${apiUrl}/sessions/${sessionId}`, {
        headers: { Authorization: `Bearer ${bearer}` },
      });
    } catch (error) {
      console.error(`[reactor-token] fetching ${apiUrl}/sessions/${sessionId} failed:`, error);
      reply(res, 502, { error: `could not reach ${apiUrl} — see the dev server log` });
      return;
    }

    if (!check.ok) {
      reply(res, 403, { error: 'that token is not bound to that session' });
      return;
    }

    const visitor = visitorOf(req, res);
    let sessions = sessionsByVisitor.get(visitor);

    if (sessions === undefined) {
      sessions = new Set();
      sessionsByVisitor.set(visitor, sessions);
    }
    sessions.add(sessionId);
    reply(res, 204);
  }

  function visitorOf(req: IncomingMessage, res: ServerResponse): string {
    const existing = /(?:^|;\s*)reactor_visitor=([0-9a-f]{32})/.exec(req.headers.cookie ?? '')?.[1];

    if (existing !== undefined) {
      return existing;
    }
    const fresh = randomBytes(16).toString('hex');
    const secure = req.headers['x-forwarded-proto'] === 'https' ? '; Secure' : '';

    res.setHeader('set-cookie', `${COOKIE}=${fresh}; Path=/; HttpOnly; SameSite=Lax${secure}`);
    return fresh;
  }
}

function reply(res: ServerResponse, status: number, body?: unknown): void {
  res.statusCode = status;
  res.end(body === undefined ? undefined : JSON.stringify(body));
}

// The last resort for a handler that threw: answer, so the browser never hangs.
function fail(res: ServerResponse, error: unknown): void {
  console.error('[reactor-token] route failed:', error);
  if (!res.writableEnded) {
    reply(res, 500, { error: 'the route failed — see the dev server log' });
  }
}

class BodyTooLarge extends Error {}

function readJson(req: IncomingMessage): Promise<unknown> {
  return new Promise((resolve, reject) => {
    const chunks: Buffer[] = [];
    let size = 0;

    req.on('data', (chunk: Buffer) => {
      size += chunk.length;
      if (size > MAX_BODY_BYTES) {
        req.pause();
        reject(new BodyTooLarge());
        return;
      }
      chunks.push(chunk);
    });
    req.on('error', reject);
    req.on('end', () => {
      try {
        resolve(chunks.length === 0 ? {} : JSON.parse(Buffer.concat(chunks).toString('utf8')));
      } catch (error) {
        reject(error instanceof Error ? error : new Error(String(error)));
      }
    });
  });
}
