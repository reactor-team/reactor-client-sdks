#!/usr/bin/env node
// Installs the packed SDK tarball into real consumer apps — Vite and Next.js,
// across the bundler versions in TARGETS below — and checks in a real browser
// that the wasm binding actually loads, in both each bundler's dev server and
// its production build. See README.md.
//
//   node run.mjs [--tarball <path/to/reactor-team-js-sdk-x.y.z.tgz>] [--target <name>]... [--keep]
//   node run.mjs --list
//
// Without --tarball, packs ../ (the SDK, which has to be built already) itself.
import { spawn } from 'node:child_process';
import { cpSync, existsSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { parseArgs } from 'node:util';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const env = { ...process.env, NEXT_TELEMETRY_DISABLED: '1', BROWSER: 'none' };
const DEV_PORT = 4410;
const PROD_PORT = 4411;

const vite = (major) => ({
  name: `vite-${major}`,
  fixture: 'vite',
  probeDir: '.',
  deps: { vite: `^${major}` },
  modes: {
    dev: { serve: ['vite', '--port', DEV_PORT, '--strictPort'], port: DEV_PORT },
    prod: { build: ['vite', 'build'], serve: ['vite', 'preview', '--port', PROD_PORT, '--strictPort'], port: PROD_PORT },
  },
});

const next = (major, bundler, { prod = true } = {}) => {
  // Each Next major has its own default bundler and its own flag for the
  // other one; passing the default's own flag is accepted too, but this keeps
  // the commands exactly what a user of that major types.
  const flag = { 15: { webpack: [], turbopack: ['--turbopack'] }, 16: { webpack: ['--webpack'], turbopack: [] } }[major][bundler];

  return {
    name: `next-${major}-${bundler}`,
    fixture: 'next',
    probeDir: 'app',
    deps: { next: `^${major}` },
    modes: {
      dev: { serve: ['next', 'dev', ...flag, '-p', DEV_PORT], port: DEV_PORT },
      ...(prod && {
        prod: { build: ['next', 'build', ...flag], serve: ['next', 'start', '-p', PROD_PORT], port: PROD_PORT },
      }),
    },
  };
};

// Every Vite major still receiving fixes, and each Next major's bundlers.
// Ranges, not pins: this runs before a release, against whatever each major's
// latest minor is that day — a new minor that breaks loading is exactly what
// it's here to catch. Next 15's `build --turbopack` is still beta, so 15 only
// checks turbopack in dev.
const TARGETS = [
  vite(6),
  vite(7),
  vite(8),
  next(15, 'webpack'),
  next(15, 'turbopack', { prod: false }),
  next(16, 'turbopack'),
  next(16, 'webpack'),
];

const { values: args } = parseArgs({
  options: {
    tarball: { type: 'string' },
    target: { type: 'string', multiple: true },
    list: { type: 'boolean' },
    keep: { type: 'boolean' },
  },
});

if (args.list) {
  console.log(JSON.stringify(TARGETS.map((t) => t.name)));
  process.exit(0);
}

const selected = args.target?.length ? TARGETS.filter((t) => args.target.includes(t.name)) : TARGETS;
const unknown = (args.target ?? []).filter((name) => !TARGETS.some((t) => t.name === name));

if (unknown.length) {
  console.error(`error: unknown target(s) ${unknown.join(', ')}; known: ${TARGETS.map((t) => t.name).join(', ')}`);
  process.exit(2);
}

const sdkDir = resolve(here, '..');
let tarball;

if (args.tarball) {
  if (!existsSync(args.tarball)) {
    console.error(`error: --tarball ${args.tarball} does not exist`);
    process.exit(2);
  }
  tarball = resolve(args.tarball);
} else {
  if (!existsSync(join(sdkDir, 'dist', 'wasm', 'reactor_wasm.js'))) {
    console.error('error: the SDK is not built — `mise run build:wasm && mise run build:js` first, or pass --tarball');
    process.exit(2);
  }
  // --ignore-scripts: `prepack` would rebuild what's already built.
  const packDir = mkdtempSync(join(tmpdir(), 'reactor-consumer-pack-'));
  const [{ filename }] = JSON.parse(
    await run('npm', ['pack', '--json', '--ignore-scripts', '--pack-destination', packDir], sdkDir),
  );

  tarball = join(packDir, filename);
}

// What marks the SDK's wasm module among everything a bundler emits: the
// start of the binary scripts/copy-wasm.mjs inlines into it, base64 of the
// wasm magic number and version. Neither the module's file name nor its
// function names survive every bundler's production build; a string literal
// does.
const WASM_MODULE_MARKER = 'AGFzbQEAAAA';

function run(cmd, cmdArgs, cwd) {
  return new Promise((resolvePromise, reject) => {
    const child = spawn(cmd, cmdArgs.map(String), { cwd, env, stdio: ['ignore', 'pipe', 'pipe'] });
    let output = '';

    child.stdout.on('data', (d) => (output += d));
    child.stderr.on('data', (d) => (output += d));
    child.on('error', reject);
    child.on('exit', (code) =>
      code === 0 ? resolvePromise(output) : reject(new Error(`${cmd} ${cmdArgs.join(' ')} exited ${code}:\n${output.slice(-4000)}`)),
    );
  });
}

const running = new Set();

// A crash mid-run must not leave a server holding its port: the next run
// would find it answering and check the *old* build instead of its own.
process.on('exit', () => running.forEach((stop) => stop()));
for (const signal of ['SIGINT', 'SIGTERM']) {process.on(signal, () => process.exit(130));}

async function assertPortFree(port) {
  const answering = await fetch(`http://localhost:${port}/`).then(
    () => true,
    () => false,
  );

  if (answering) {throw new Error(`something is already listening on port ${port} — refusing to check it instead of this build`);}
}

/** Starts a long-running server in its own process group, so stopping it
 *  also stops whatever it spawned (Next's dev server forks workers). */
function startServer(cmdArgs, cwd) {
  const child = spawn('npx', cmdArgs.map(String), { cwd, env, stdio: ['ignore', 'pipe', 'pipe'], detached: true });
  let output = '';
  let exited = false;

  child.stdout.on('data', (d) => (output += d));
  child.stderr.on('data', (d) => (output += d));
  child.on('exit', () => (exited = true));

  const stop = () => {
    running.delete(stop);
    try {
      process.kill(-child.pid, 'SIGTERM');
    } catch {
      // Already gone.
    }
  };

  running.add(stop);

  return { output: () => output, exited: () => exited, stop };
}

async function waitForServer(url, server, timeoutMs = 120_000) {
  const deadline = Date.now() + timeoutMs;

  while (Date.now() < deadline) {
    if (server.exited()) {throw new Error(`server exited before answering ${url}:\n${server.output().slice(-4000)}`);}
    try {
      const response = await fetch(url);

      if (response.ok) {return;}
    } catch {
      // Not listening yet.
    }
    await new Promise((r) => setTimeout(r, 500));
  }
  throw new Error(`${url} never came up:\n${server.output().slice(-4000)}`);
}

/** Records every response that carries the SDK's wasm module, and any
 *  request for a standalone `.wasm` file — there should never be one. */
function watchWasm(page) {
  const seen = { moduleResponses: 0, wasmFileRequests: [] };

  page.on('request', (request) => {
    if (new URL(request.url()).pathname.endsWith('.wasm')) {seen.wasmFileRequests.push(request.url());}
  });
  page.on('response', async (response) => {
    if (!/javascript/.test(response.headers()['content-type'] ?? '')) {return;}
    try {
      if ((await response.text()).includes(WASM_MODULE_MARKER)) {seen.moduleResponses++;}
    } catch {
      // Body unavailable (redirect, aborted) — can't be the module.
    }
  });

  return seen;
}

async function openProbe(browser, url) {
  const page = await browser.newPage();
  const seen = watchWasm(page);
  const errors = [];

  page.on('pageerror', (error) => errors.push(error.message));
  await page.goto(url, { waitUntil: 'networkidle', timeout: 120_000 });
  await page.waitForFunction(() => window.__consumer, null, { timeout: 60_000 });

  return { page, seen, errors };
}

function check(failures, ok, message) {
  if (!ok) {failures.push(message);}
}

/** The checks one mode (dev or prod) of one target has to pass. */
async function checkMode(browser, url) {
  const failures = [];

  // 1. Loads, initializes, caches.
  {
    const { page, seen, errors } = await openProbe(browser, url);

    check(failures, seen.moduleResponses === 0, `importing the SDK fetched the wasm module before connect() (${seen.moduleResponses} response(s))`);

    const first = await page.evaluate(() => window.__consumer.connect());

    check(failures, first.reachedApi, `wasm binding never came up on connect(): ${first.error}`);
    check(failures, !/reactor-wasm failed to load/.test(first.error ?? ''), `connect() reported a load failure: ${first.error}`);
    check(failures, seen.moduleResponses === 1, `expected the wasm module fetched exactly once, saw ${seen.moduleResponses}`);

    const second = await page.evaluate(() => window.__consumer.connect());

    check(failures, second.reachedApi, `a second Reactor never came up: ${second.error}`);
    check(failures, seen.moduleResponses === 1, `a second Reactor fetched the wasm module again (${seen.moduleResponses} total)`);
    check(failures, seen.wasmFileRequests.length === 0, `requested a standalone .wasm file: ${seen.wasmFileRequests.join(', ')}`);
    check(failures, errors.length === 0, `uncaught page errors: ${errors.join(' | ')}`);
    await page.close();
  }

  // 2. A failed load names its cause.
  {
    const page = await browser.newPage();

    // Fail the wasm module's own fetch, identified by its contents since its
    // URL depends on the bundler. Only the app's own origin — the SDK's
    // request to the unreachable API has nothing to fetch.
    const origin = new URL(url).origin;

    await page.route(
      (u) => u.origin === origin,
      async (route) => {
        // Anything else passes through untouched — buffering a streamed
        // response (Next's dev server streams its pages) can stall it.
        if (route.request().resourceType() !== 'script') {return route.continue();}

        const response = await route.fetch();
        const body = await response.body();

        if (body.includes(WASM_MODULE_MARKER)) {return route.abort('failed');}

        return route.fulfill({ response, body });
      },
    );
    await page.goto(url, { waitUntil: 'networkidle', timeout: 120_000 });
    await page.waitForFunction(() => window.__consumer, null, { timeout: 60_000 });

    const failed = await page.evaluate(() => window.__consumer.connect());

    check(failures, /^reactor-wasm failed to load: .+/.test(failed.error ?? ''), `a failed load didn't name its cause: ${failed.error}`);
    await page.close();
  }

  return failures;
}

async function runTarget(browser, target) {
  const dir = mkdtempSync(join(tmpdir(), `reactor-consumer-${target.name}-`));
  const results = [];

  try {
    cpSync(join(here, 'fixtures', target.fixture), dir, { recursive: true });
    cpSync(join(here, 'fixtures', 'probe.js'), join(dir, target.probeDir, 'probe.js'));
    writeFileSync(join(dir, 'package.json'), JSON.stringify({ name: `consumer-${target.name}`, private: true, type: 'module' }));

    const deps = { ...target.deps, react: '^19', 'react-dom': '^19', '@reactor-team/js-sdk': tarball };

    await run('npm', ['install', '--no-audit', '--no-fund', ...Object.entries(deps).map(([n, v]) => `${n}@${v}`)], dir);

    const versions = JSON.parse(await run('npm', ['ls', '--depth=0', '--json'], dir)).dependencies;
    const bundler = Object.keys(target.deps)[0];

    console.log(`\n${target.name}: ${bundler} ${versions[bundler].version}`);

    for (const [mode, spec] of Object.entries(target.modes)) {
      const started = Date.now();
      let failures;
      let server;

      try {
        if (spec.build) {await run('npx', spec.build, dir);}
        await assertPortFree(spec.port);
        server = startServer(spec.serve, dir);
        await waitForServer(`http://localhost:${spec.port}/`, server);
        failures = await checkMode(browser, `http://localhost:${spec.port}/`);
      } catch (error) {
        failures = [String(error?.message ?? error)];
      } finally {
        server?.stop();
        // Give the port back before the next mode binds it.
        await new Promise((r) => setTimeout(r, 1000));
      }

      const seconds = ((Date.now() - started) / 1000).toFixed(0);

      console.log(`  ${failures.length ? 'FAIL' : 'ok  '} ${mode} (${seconds}s)`);
      for (const failure of failures) {console.log(`       - ${failure}`);}
      results.push({ target: target.name, mode, failures });
    }
  } catch (error) {
    console.log(`  FAIL setup\n       - ${error.message}`);
    results.push({ target: target.name, mode: 'setup', failures: [error.message] });
  } finally {
    if (args.keep) {console.log(`  kept ${dir}`);}
    else {rmSync(dir, { recursive: true, force: true });}
  }

  return results;
}

// What a consumer actually installs: the binding's module, and no separate
// binary for a bundler to lose track of.
{
  const listing = (await run('tar', ['-tzf', tarball], here)).split('\n');
  const problems = [
    !listing.includes('package/dist/wasm/reactor_wasm.js') && 'package/dist/wasm/reactor_wasm.js is missing',
    ...listing.filter((f) => f.endsWith('.wasm')).map((f) => `${f} should not be shipped (the binary is inlined)`),
  ].filter(Boolean);

  if (problems.length) {
    console.error(`tarball ${tarball}:\n${problems.map((p) => `  - ${p}`).join('\n')}`);
    process.exit(1);
  }
}

// Imported here, not at the top, so `--list` works before `npm ci`.
const { chromium } = await import('@playwright/test');
const browser = await chromium.launch();
const results = [];

try {
  for (const target of selected) {results.push(...(await runTarget(browser, target)));}
} finally {
  await browser.close();
}

const failed = results.filter((r) => r.failures.length);

console.log(`\n${results.length - failed.length}/${results.length} passed`);
process.exit(failed.length ? 1 : 0);
