# JS SDK consumer tests

Installs the packed SDK — the same tarball `npm publish` uploads — into real
consumer apps and checks, in a real browser, that the wasm binding loads. Each
target is a bundler and version (`run.mjs`'s `TARGETS`: Vite 6, 7 and 8,
Next.js 15 and 16 with webpack and Turbopack), checked in both its dev server
and its production build. The exception is Next.js 15 with Turbopack, checked
in dev only: its `build --turbopack` is still beta.

This covers the gap between the SDK's own build and what a consumer's bundler
does to it. The examples and the integration tests install the SDK linked from
source (`file:..`), which bundlers treat differently from a package in
`node_modules`: Vite, for one, never runs a linked package through its
dependency optimizer, which is where a relative asset path breaks.

## Running it

From the repo root, building the SDK first:

```sh
mise run test:js:consumer-tests
```

Or from this directory, against an SDK that's already built (`run.mjs` packs
`../` itself) or a tarball you already have:

```sh
npm ci
npx playwright install chromium
node run.mjs                                   # every target
node run.mjs --target vite-8 --target next-16-turbopack
node run.mjs --tarball ~/Downloads/reactor-team-js-sdk-3.2.0.tgz
node run.mjs --list                            # target names
node run.mjs --keep                            # keep each generated app, for debugging
```

No API key and no model: the probe points the SDK at an address nothing
listens on, so `connect()` fails right after the binding comes up and makes its
first HTTP request — which is all these checks need.

## What it checks

Per target and mode (dev, prod):

- The tarball ships the binding's module and no separate `.wasm` file.
- Importing the SDK fetches nothing wasm-related; the binding's module is
  fetched on the first `connect()`.
- `connect()` brings the binding up, and a second `Reactor` reuses it without
  fetching it again.
- Nothing requests a standalone `.wasm` file.
- When the module fails to load, the error names the underlying cause.

Next.js targets also render the page that imports the SDK on the server, so a
server-side import that touched the binding would fail the page load.

Bundler versions are ranges (`^8`, not `8.3.4`): this runs before each
release against what a consumer installing that day gets, so a new bundler
minor that breaks loading shows up before the release does.

## In CI

`.github/workflows/js-consumer-tests.yml` — one job per target. It gates every
JS release (called from `release-js.yml`), runs on pull requests that touch
how the binding is packaged or loaded, and can be dispatched by hand.
