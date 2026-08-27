declare const process: { env?: { NODE_ENV?: string } } | undefined;
declare global {
  interface ImportMeta {
    readonly env?: { readonly DEV?: boolean };
  }
}

// No single check covers every bundler actually used with this SDK: webpack
// and Next.js globally replace `process.env.NODE_ENV` (including inside
// dependencies), even though no real `process` global exists in the browser
// at runtime — but Vite does not, by default, do that same replacement for a
// dependency's `process.env.NODE_ENV` (verified: it survives, unreplaced,
// into a Vite production bundle, where `typeof process` is then genuinely
// `'undefined'` at runtime — silently always off). Vite instead exposes its
// own `import.meta.env.DEV`, real at runtime, for every module it processes,
// dependencies included. Checking both covers both; neither reference
// throws where the other bundler's mechanism is the one in play.
const isDev =
  (typeof process !== 'undefined' && process?.env?.NODE_ENV === 'development') ||
  import.meta.env?.DEV === true;

/** Logs wire traffic (data/control channel messages) in development builds
 *  only — useful while iterating against a model, silent in production. */
export function debugLog(...args: unknown[]): void {
  if (isDev) {
    console.debug(...args);
  }
}
