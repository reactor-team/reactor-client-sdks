// Test-only stand-in for `dist/wasm/reactor_wasm.js`, which only exists after
// a build — vitest.config.ts aliases the loader's `./wasm/reactor_wasm.js`
// import here. Tests mock this module to control what loading does.
export default async function init(): Promise<void> {}

export class ReactorClient {}
