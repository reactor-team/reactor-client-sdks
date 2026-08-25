import { fileURLToPath } from 'node:url';
import { defineConfig, searchForWorkspaceRoot } from 'vite';

// `@reactor-team/js-sdk` is linked in from one level up (`../..`). Vite only
// serves files under the workspace root by default, so its `dist/wasm` needs
// to be allowed explicitly, or the wasm fetch 403s.
const sdkRoot = fileURLToPath(new URL('../..', import.meta.url));

// Must match `main.ts`'s `MODEL_NAME` — X2, not Helios: it's the model with
// an input track to publish into.
const MODEL_NAME = 'xmax/x2';
// How many sessions one token may ever create (closed sessions still count).
// This token gets reused for a while (see the cache below), so it needs
// enough room for a normal round of reconnects during dev — the
// coordinator's own default is low enough to run out mid-session otherwise.
const MAX_SESSIONS = 10;
// Safety margin so an in-flight request doesn't race the real expiry.
const CACHE_SKEW_SECONDS = 60;

// Reused across requests until it's about to expire — see the middleware
// below for why this can't just be "mint one every call".
let cached: { jwt: string; expiresAt: number } | undefined;

export default defineConfig({
  server: {
    fs: { allow: [searchForWorkspaceRoot(process.cwd()), sdkRoot] },
  },
  plugins: [
    {
      name: 'reactor-token',
      configureServer(server) {
        // GET /api/token — mints a JWT from REACTOR_API_KEY so the key
        // itself never reaches the browser. A real app would do this on its
        // own backend; this stands in for that.
        //
        // Caching matters, not just as an optimization: the SDK's resolver
        // calls this on every coordinator hop (session create, the
        // poll-until-ready GET, ...), and reading a session back requires
        // the *same* token that created it — a second, independently minted
        // token with identical scope still 403s. The cache is kept here, in
        // this process, rather than via a `Cache-Control` response header —
        // that leaves correctness up to the browser's own cache behavior
        // (private windows, "disable cache" devtools, a hard reload's
        // exact semantics for JS-initiated fetches all differ), where a
        // plain in-memory cache doesn't care what the browser does at all.
        // `no-store` tells it not to bother trying.
        server.middlewares.use('/api/token', async (_req, res) => {
          const apiKey = process.env.REACTOR_API_KEY;

          if (!apiKey) {
            res.statusCode = 500;
            res.end(JSON.stringify({ error: 'REACTOR_API_KEY is not set — see README.md' }));
            return;
          }

          res.setHeader('content-type', 'application/json');
          res.setHeader('cache-control', 'no-store');

          const now = Math.floor(Date.now() / 1000);
          if (cached && cached.expiresAt - CACHE_SKEW_SECONDS > now) {
            res.statusCode = 200;
            res.end(JSON.stringify({ jwt: cached.jwt }));
            return;
          }

          try {
            const upstream = await fetch('https://api.reactor.inc/tokens', {
              method: 'POST',
              headers: { 'Reactor-API-Key': apiKey, 'content-type': 'application/json' },
              // Session-scoped, not the unscoped `null` body: an unscoped
              // token only carries the key's own dashboard permissions, which
              // don't necessarily include reading back a session it just
              // created. A `session`-authorization token does, and is what
              // the coordinator expects a browser to hold.
              body: JSON.stringify({
                expires_after: 3600,
                authorization_details: [
                  {
                    type: 'session',
                    resources: { models: { match: [MODEL_NAME] } },
                    constraints: { max_sessions: MAX_SESSIONS },
                  },
                ],
              }),
            });

            if (!upstream.ok) {
              cached = undefined;
              res.statusCode = upstream.status;
              res.end(await upstream.text());
              return;
            }

            const { jwt, expires_at } = (await upstream.json()) as { jwt: string; expires_at: number };
            cached = { jwt, expiresAt: expires_at };

            res.statusCode = 200;
            res.end(JSON.stringify({ jwt }));
          } catch (error) {
            console.error('[reactor-token] fetching https://api.reactor.inc/tokens failed:', error);
            cached = undefined;
            res.statusCode = 502;
            res.end(JSON.stringify({ error: 'could not reach api.reactor.inc — see the dev server log' }));
          }
        });
      },
    },
  ],
});
