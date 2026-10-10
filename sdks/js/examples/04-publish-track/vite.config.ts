import { fileURLToPath } from 'node:url';
import { defineConfig, searchForWorkspaceRoot } from 'vite';
import { reactorTokenPlugin } from '../shared/token-server';

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
  // `/api/token` and `/api/session` — what a real app's backend serves so
  // REACTOR_API_KEY never reaches the browser. See shared/token-server.ts.
  plugins: [reactorTokenPlugin({ modelName: MODEL_NAME })],
});
