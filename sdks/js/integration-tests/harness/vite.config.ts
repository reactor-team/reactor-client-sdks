import { fileURLToPath } from 'node:url';
import { defineConfig, loadEnv, searchForWorkspaceRoot } from 'vite';

// This file's own directory — `playwright.config.ts` launches Vite with
// `--config harness/vite.config.ts` from the package root one level up, and
// without an explicit `root` Vite serves out of the *current working
// directory* rather than the config file's directory, so `index.html` here
// would 404.
const harnessRoot = fileURLToPath(new URL('.', import.meta.url));

// `@reactor-team/js-sdk` (`sdks/js`) is two directories up from here
// (`harness/` -> `integration-tests/` -> `js/`). Vite only serves files under the
// workspace root by default, so its `dist/wasm` needs to be allowed
// explicitly, or the wasm fetch 403s.
const sdkRoot = fileURLToPath(new URL('../..', import.meta.url));

export default defineConfig(({ mode }) => {
  // `vite`/`vite preview` load `.env.local` themselves for `import.meta.env`,
  // but this file also needs the values outside that — in the Node-side
  // token-minting middleware below — so load them into `process.env` too.
  Object.assign(process.env, loadEnv(mode, process.cwd(), ''));

  // Same knobs the harness's own code reads (see src/config.ts) — kept in
  // sync here so the token this middleware mints is scoped to the model the
  // harness actually connects to. Unset means today's target: the real
  // `reactor/echo` model in production. Pointing both at a local runtime
  // later (`REACTOR_LOCAL=true`, `REACTOR_API_URL=http://localhost:8080`) is
  // meant to be an env change, not a code change.
  const local = process.env.REACTOR_LOCAL === 'true';
  const apiUrl = process.env.REACTOR_API_URL ?? 'https://api.reactor.inc';
  const modelName = process.env.REACTOR_MODEL_NAME ?? 'reactor/echo';

  return {
    root: harnessRoot,
    server: {
      fs: { allow: [searchForWorkspaceRoot(process.cwd()), sdkRoot] },
    },
    define: {
      __REACTOR_LOCAL__: JSON.stringify(local),
      __REACTOR_API_URL__: JSON.stringify(apiUrl),
      __REACTOR_MODEL_NAME__: JSON.stringify(modelName),
    },
    plugins: [
      {
        name: 'reactor-token',
        configureServer(server) {
          // GET /api/token — mints a JWT from INTEGRATION_TESTS_REACTOR_API_KEY
          // so the key itself never reaches the browser, same shape as the
          // examples' own dev-only middleware (which uses their own
          // REACTOR_API_KEY — this suite gets a dedicated key so it's never
          // confused with theirs). A local runtime takes no JWT at all (see
          // harness/src/config.ts), so this never runs in that mode.
          server.middlewares.use('/api/token', async (_req, res) => {
            if (local) {
              res.statusCode = 400;
              res.end(JSON.stringify({ error: 'no token needed against a local runtime' }));
              return;
            }

            const apiKey = process.env.INTEGRATION_TESTS_REACTOR_API_KEY;

            if (!apiKey) {
              res.statusCode = 500;
              res.end(
                JSON.stringify({
                  error: 'INTEGRATION_TESTS_REACTOR_API_KEY is not set — see README.md',
                }),
              );
              return;
            }

            res.setHeader('content-type', 'application/json');
            res.setHeader('cache-control', 'no-store');

            try {
              const upstream = await fetch(`${apiUrl}/tokens`, {
                method: 'POST',
                headers: { 'Reactor-API-Key': apiKey, 'content-type': 'application/json' },
                // Session-scoped, not the unscoped `null` body — see the
                // examples' own middleware for why: an unscoped token only
                // carries the key's own dashboard permissions.
                body: JSON.stringify({
                  authorization_details: [
                    { type: 'session', resources: { models: { match: [modelName] } } },
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
              console.error(`[reactor-token] fetching ${apiUrl}/tokens failed:`, error);
              res.statusCode = 502;
              res.end(JSON.stringify({ error: `could not reach ${apiUrl} — see the dev server log` }));
            }
          });
        },
      },
    ],
  };
});
