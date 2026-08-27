import { fileURLToPath } from 'node:url';
import { defineConfig, searchForWorkspaceRoot } from 'vite';

// `@reactor-team/js-sdk` is linked in from one level up (`../..`). Vite only
// serves files under the workspace root by default, so its `dist/wasm` needs
// to be allowed explicitly, or the wasm fetch 403s.
const sdkRoot = fileURLToPath(new URL('../..', import.meta.url));

// Must match `main.ts`'s `MODEL_NAME`.
const MODEL_NAME = 'xmax/x2';

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
        // `main.ts` calls this once per connect() and hands the result to it
        // as a plain string, not a resolver — so there's no second call that
        // could mint a different token for the same session. Reading a
        // session back requires the *same* token that created it; a second,
        // independently minted token with identical scope still 403s.
        server.middlewares.use('/api/token', async (_req, res) => {
          const apiKey = process.env.REACTOR_API_KEY;

          if (!apiKey) {
            res.statusCode = 500;
            res.end(JSON.stringify({ error: 'REACTOR_API_KEY is not set — see README.md' }));
            return;
          }

          res.setHeader('content-type', 'application/json');
          res.setHeader('cache-control', 'no-store');

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
                authorization_details: [
                  { type: 'session', resources: { models: { match: [MODEL_NAME] } } },
                ],
              }),
            });

            if (!upstream.ok) {
              res.statusCode = upstream.status;
              res.end(await upstream.text());
              return;
            }

            const { jwt } = (await upstream.json()) as { jwt: string };

            res.statusCode = 200;
            res.end(JSON.stringify({ jwt }));
          } catch (error) {
            console.error('[reactor-token] fetching https://api.reactor.inc/tokens failed:', error);
            res.statusCode = 502;
            res.end(JSON.stringify({ error: 'could not reach api.reactor.inc — see the dev server log' }));
          }
        });
      },
    },
  ],
});
