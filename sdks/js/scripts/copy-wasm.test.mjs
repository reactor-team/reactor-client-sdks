/* global WebAssembly */
import { existsSync, mkdtempSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { Buffer } from 'node:buffer';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { WASM_URL_EXPRESSION, copyWasm, inlineWasm } from './copy-wasm.mjs';

// The smallest valid wasm module: magic number + version, no sections.
const EMPTY_MODULE = Uint8Array.of(0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00);

// Just enough of wasm-bindgen's `--target web` glue for inlineWasm to work
// on: the one expression it replaces, inside an init function exported the
// same way.
const GLUE = `export default async function init(module_or_path) {
    if (module_or_path === undefined) {
        module_or_path = ${WASM_URL_EXPRESSION};
    }
    return module_or_path;
}
`;

/** Evaluates an inlined glue module and returns what its init resolves to. */
async function runInlinedInit(source) {
  const url = `data:text/javascript;base64,${Buffer.from(source).toString('base64')}`;
  const module = await import(url);

  return module.default();
}

describe('inlineWasm', () => {
  it('replaces the URL lookup with the binary itself', async () => {
    const inlined = inlineWasm(GLUE, EMPTY_MODULE);

    expect(inlined).not.toContain(WASM_URL_EXPRESSION);
    expect(inlined.split('function __reactorWasmBytes(').length - 1).toBe(1);
    expect(inlined.split('= __reactorWasmBytes();').length - 1).toBe(1);
    const bytes = await runInlinedInit(inlined);

    expect(bytes).toBeInstanceOf(Uint8Array);
    expect([...bytes]).toEqual([...EMPTY_MODULE]);
    expect(WebAssembly.validate(bytes)).toBe(true);
  });

  it('decodes the same bytes without Uint8Array.fromBase64', async () => {
    const fromBase64 = Uint8Array.fromBase64;

    delete Uint8Array.fromBase64;
    try {
      const bytes = await runInlinedInit(inlineWasm(GLUE, EMPTY_MODULE));

      expect([...bytes]).toEqual([...EMPTY_MODULE]);
    } finally {
      if (fromBase64) {Uint8Array.fromBase64 = fromBase64;}
    }
  });

  it('throws when the glue no longer locates the binary the expected way', () => {
    expect(() => inlineWasm('export default async function init() {}', EMPTY_MODULE)).toThrow(/found 0/);
  });

  it('throws when the expression appears more than once', () => {
    expect(() => inlineWasm(GLUE + GLUE, EMPTY_MODULE)).toThrow(/found 2/);
  });
});

describe('copyWasm', () => {
  let root;
  let srcDir;
  let destDir;

  beforeEach(() => {
    root = mkdtempSync(join(tmpdir(), 'copy-wasm-test-'));
    srcDir = join(root, 'pkg');
    destDir = join(root, 'dist', 'wasm');
    mkdirSync(srcDir, { recursive: true });
  });

  afterEach(() => {
    rmSync(root, { recursive: true, force: true });
  });

  const writeValidPkg = () => {
    writeFileSync(join(srcDir, 'reactor_wasm_bg.wasm'), EMPTY_MODULE);
    writeFileSync(join(srcDir, 'reactor_wasm.js'), GLUE);
  };

  it('writes an inlined glue and no separate binary into a fresh dest', () => {
    writeValidPkg();

    copyWasm(srcDir, destDir);

    const glue = readFileSync(join(destDir, 'reactor_wasm.js'), 'utf8');

    expect(glue).not.toContain(WASM_URL_EXPRESSION);
    expect(glue).toContain('__reactorWasmBytes()');
    expect(existsSync(join(destDir, 'reactor_wasm_bg.wasm'))).toBe(false);
    expect(() => copyWasm(srcDir, destDir)).not.toThrow();
  });

  it('strips the .gitignore that wasm-pack writes into the pkg dir, so it does not poison npm-packlist', () => {
    writeValidPkg();
    writeFileSync(join(srcDir, '.gitignore'), '*\n');

    copyWasm(srcDir, destDir);

    expect(existsSync(join(destDir, '.gitignore'))).toBe(false);
  });

  it('throws when the source pkg directory does not exist', () => {
    rmSync(srcDir, { recursive: true, force: true });

    expect(() => copyWasm(srcDir, destDir)).toThrow(/does not exist/);
  });

  it('throws instead of leaving a stale dest looking valid when the source binary is missing', () => {
    // A prior, good build already populated dest.
    mkdirSync(destDir, { recursive: true });
    writeFileSync(join(destDir, 'reactor_wasm.js'), 'stale-but-real-glue');

    // The current pkg/ has the glue but no binary — e.g. a partial or stale
    // build.
    writeFileSync(join(srcDir, 'reactor_wasm.js'), GLUE);

    expect(() => copyWasm(srcDir, destDir)).toThrow(/is missing or empty/);
    expect(readFileSync(join(destDir, 'reactor_wasm.js'), 'utf8')).toBe('stale-but-real-glue');
  });

  it('throws when the source binary is empty', () => {
    writeFileSync(join(srcDir, 'reactor_wasm_bg.wasm'), '');
    writeFileSync(join(srcDir, 'reactor_wasm.js'), GLUE);

    expect(() => copyWasm(srcDir, destDir)).toThrow(/is missing or empty/);
  });

  it('throws when the source glue is missing', () => {
    writeFileSync(join(srcDir, 'reactor_wasm_bg.wasm'), EMPTY_MODULE);

    expect(() => copyWasm(srcDir, destDir)).toThrow(/reactor_wasm\.js is missing or empty/);
  });

  it('leaves a previous good dest untouched when the glue changed shape', () => {
    mkdirSync(destDir, { recursive: true });
    writeFileSync(join(destDir, 'reactor_wasm.js'), 'previous-good-glue');
    writeFileSync(join(srcDir, 'reactor_wasm_bg.wasm'), EMPTY_MODULE);
    writeFileSync(join(srcDir, 'reactor_wasm.js'), 'export default async function init() {}');

    expect(() => copyWasm(srcDir, destDir)).toThrow(/found 0/);
    expect(readFileSync(join(destDir, 'reactor_wasm.js'), 'utf8')).toBe('previous-good-glue');
  });
});
