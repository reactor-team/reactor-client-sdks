import type { IncomingMessage, ServerResponse } from 'node:http';
import { defineConfig, type Plugin } from 'vite';

const TOKENS_URL = 'https://api.reactor.inc/tokens';

async function readJsonBody(req: IncomingMessage): Promise<unknown> {
  const chunks: Buffer[] = [];

  for await (const chunk of req) {
    chunks.push(chunk as Buffer);
  }
  return JSON.parse(Buffer.concat(chunks).toString('utf8') || '{}');
}

function readApiKey(body: unknown): string | undefined {
  if (typeof body !== 'object' || body === null || !('apiKey' in body)) {
    return undefined;
  }
  const { apiKey } = body as { apiKey?: unknown };

  return typeof apiKey === 'string' ? apiKey : undefined;
}

function sendJson(res: ServerResponse, status: number, body: unknown): void {
  res.statusCode = status;
  res.setHeader('Content-Type', 'application/json');
  res.end(JSON.stringify(body));
}

/**
 * Dev-only route that mints a JWT from an API key, mirroring the exchange
 * the Python SDK does server-side (`sdks/python/reactor_sdk/_auth.py`):
 * `POST /tokens` with a `Reactor-API-Key` header. Keeping this hop in the
 * Vite server rather than fetching `api.reactor.inc` straight from the demo
 * page sidesteps CORS and — more importantly — is what any real app should
 * do: the API key stays server-side, only the resulting JWT reaches the
 * browser. The demo's own use of localStorage for the key is a local-tool
 * convenience, not a pattern to carry into a shipped app.
 *
 * Always mints unscoped (no `authorization_details`) — scoping the token to
 * a model would require guessing a model slug the key is actually granted
 * before ever reaching `connect()`, which just moves the "wrong model name"
 * error to a more confusing place. `_auth.py` calls unscoped "fine
 * server-to-server, wrong to hand to a client you do not control" — this
 * demo is a local tool you control, same as a script would be.
 */
function generateJwtPlugin(): Plugin {
  return {
    name: 'reactor-demo-generate-jwt',
    configureServer(server) {
      server.middlewares.use('/api/generate-jwt', (req, res) => {
        void (async () => {
          try {
            const apiKey = readApiKey(await readJsonBody(req));

            if (!apiKey) {
              return sendJson(res, 400, { error: 'apiKey is required' });
            }

            const upstream = await fetch(TOKENS_URL, {
              method: 'POST',
              headers: { 'Reactor-API-Key': apiKey, 'Content-Type': 'application/json' },
              body: null,
            });
            const upstreamBody = await upstream.json().catch(() => ({}));

            if (!upstream.ok) {
              return sendJson(res, upstream.status, {
                error: upstreamBody.detail ?? upstreamBody.error ?? `upstream HTTP ${upstream.status}`,
              });
            }
            if (!upstreamBody.jwt) {
              return sendJson(res, 502, { error: 'upstream response had no jwt' });
            }
            sendJson(res, 200, { jwt: upstreamBody.jwt });
          } catch (error) {
            sendJson(res, 500, { error: String(error) });
          }
        })();
      });
    },
  };
}

export default defineConfig({
  plugins: [generateJwtPlugin()],
  server: {
    fs: {
      // @reactor-team/js-sdk resolves via `file:..` (see package.json), so
      // npm symlinks node_modules/@reactor-team/js-sdk to the sdks/js
      // directory this demo lives under — one level above Vite's default
      // project root, which its filesystem allow-list would otherwise block.
      allow: ['..'],
    },
  },
});
