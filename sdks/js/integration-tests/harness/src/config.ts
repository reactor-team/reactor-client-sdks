// Populated by `vite.config.ts`'s `define` from `REACTOR_LOCAL` /
// `REACTOR_API_URL` / `REACTOR_MODEL_NAME` — see that file. Kept as plain
// globals (not `import.meta.env.VITE_*`) so tests never need the `VITE_`
// prefix dance to read them back.
declare const __REACTOR_LOCAL__: boolean;
declare const __REACTOR_API_URL__: string;
declare const __REACTOR_MODEL_NAME__: string;

export const REACTOR_LOCAL = __REACTOR_LOCAL__;
export const REACTOR_API_URL = __REACTOR_API_URL__;
export const REACTOR_MODEL_NAME = __REACTOR_MODEL_NAME__;
