import { defineConfig } from 'tsup';

export default defineConfig({
  entry: ['src/index.ts'],
  format: ['esm', 'cjs'],
  outExtension: ({ format }) => ({ js: format === 'cjs' ? '.cjs' : '.js' }),
  dts: true,
  sourcemap: true,
  clean: true,
  target: 'es2022',
  jsx: 'automatic',
  // Every source file's own `'use client'` directive (ReactorProvider.tsx,
  // hooks.ts, ...) gets dropped here — tsup bundles the whole package into
  // one file, and a directive only takes effect as literally the first
  // statement of the file that's actually loaded, not of the source module
  // it originated in. A banner is the only way to put it back: the whole
  // package is browser-only anyway (wasm running against WebRTC/media APIs),
  // so marking the single bundled entry point is correct, not a broadening.
  banner: { js: "'use client';" },
  // reactor-wasm is copied into dist/wasm by scripts/copy-wasm.mjs (run after
  // this build, see the `build` script) and loaded via a plain runtime
  // `import()` in src/internal/wasm.ts. It doesn't exist on disk yet at this
  // point in the build, so esbuild can't resolve it — marked external so it
  // errors on neither that nor bundling it, and is left as the literal
  // consuming bundlers (webpack, Rollup, ...) need to statically recognize it.
  external: ['./wasm/reactor_wasm.js'],
});
