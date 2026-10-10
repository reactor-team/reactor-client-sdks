import type { IncomingMessage, ServerResponse } from 'node:http';
import { Readable } from 'node:stream';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { reactorTokenPlugin } from './token-server';

type Handler = (req: IncomingMessage, res: ServerResponse, next: () => void) => void;

const SESSION = '11111111-2222-4333-8444-555555555555';

interface Reply {
  status: number;
  body: string | undefined;
  headers: Record<string, string>;
}

function routes(): Record<string, Handler> {
  const handlers: Record<string, Handler> = {};

  reactorTokenPlugin({ modelName: 'reactor/helios', apiUrl: 'https://api.test' }).configureServer({
    middlewares: {
      use(path, handler) {
        handlers[path] = handler;
      },
    },
  });
  return handlers;
}

function request(method: string, url: string, body = '', headers: Record<string, string> = {}): IncomingMessage {
  return Object.assign(Readable.from(body === '' ? [] : [Buffer.from(body)]), {
    method,
    url,
    headers: { host: 'localhost:5173', ...headers },
  }) as unknown as IncomingMessage;
}

// Resolves with what the route answered, or rejects when it never answers.
function respond(handler: Handler, req: IncomingMessage): Promise<Reply> {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('the route never answered')), 500);
    const headers: Record<string, string> = {};
    const res = {
      statusCode: 200,
      setHeader(name: string, value: string) {
        headers[name] = value;
      },
      end(body?: string) {
        clearTimeout(timer);
        resolve({ status: res.statusCode, body, headers });
      },
    };

    handler(req, res as unknown as ServerResponse, () => {});
  });
}

describe('reactorTokenPlugin', () => {
  beforeEach(() => {
    vi.stubEnv('REACTOR_API_KEY', 'test-key');
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.unstubAllEnvs();
  });

  it('binds a session only for the browser that registered it', async () => {
    vi.stubGlobal('fetch', vi.fn((input: string | URL | Request) => {
      const url = String(input);

      if (url.endsWith(`/sessions/${SESSION}`)) {
        return Promise.resolve(new Response('{}', { status: 200 }));
      }
      return Promise.resolve(new Response(JSON.stringify({ jwt: 'bound', expires_at: 1 }), { status: 200 }));
    }));
    const { '/api/session': register, '/api/token': token } = routes();

    const stranger = await respond(token, request('GET', `/?session_id=${SESSION}`));

    expect(stranger.status).toBe(403);

    const registration = await respond(register, request('POST', '/', JSON.stringify({ sessionId: SESSION }), {
      authorization: 'Bearer creator-1',
    }));

    expect(registration.status).toBe(204);

    const cookie = /^reactor_visitor=[0-9a-f]{32}/.exec(registration.headers['set-cookie'])?.[0];

    expect(cookie).toBeDefined();

    const owner = await respond(token, request('GET', `/?session_id=${SESSION}`, '', { cookie: cookie as string }));

    expect(owner.status).toBe(200);
    expect(JSON.parse(owner.body as string)).toEqual({ jwt: 'bound', expires_at: 1 });
  });

  it('answers 400 to a body that is not JSON', async () => {
    const { '/api/session': register } = routes();
    const reply = await respond(register, request('POST', '/', '{not json', { authorization: 'Bearer creator-1' }));

    expect(reply.status).toBe(400);
  });

  it('answers 400 to a JSON null body', async () => {
    const { '/api/session': register } = routes();
    const reply = await respond(register, request('POST', '/', 'null', { authorization: 'Bearer creator-1' }));

    expect(reply.status).toBe(400);
  });

  it('answers 502 when Reactor cannot be reached', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('fetch failed')));
    const { '/api/session': register } = routes();
    const reply = await respond(register, request('POST', '/', JSON.stringify({ sessionId: SESSION }), {
      authorization: 'Bearer creator-1',
    }));

    expect(reply.status).toBe(502);
  });
});
