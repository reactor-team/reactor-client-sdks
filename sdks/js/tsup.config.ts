import { defineConfig } from 'tsup';

export default defineConfig({
  entry: ['src/index.ts'],
  format: ['esm', 'cjs'],
  outExtension: ({ format }) => ({ js: format === 'cjs' ? '.cjs' : '.js' }),
  dts: true,
  sourcemap: true,
  clean: true,
  target: 'es2022',
  // reactor-wasm is copied into dist/wasm by scripts/copy-wasm.mjs (run after
  // this build, see the `build` script) and loaded via a plain runtime
  // `import()` in src/internal/wasm.ts — nothing here should try to resolve
  // or bundle it.
});
