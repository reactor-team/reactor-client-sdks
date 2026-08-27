import { existsSync, mkdtempSync, mkdirSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { copyWasm } from './copy-wasm.mjs';

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

  it('copies a valid binary into a fresh dest', () => {
    writeFileSync(join(srcDir, 'reactor_wasm_bg.wasm'), 'not-really-wasm');

    copyWasm(srcDir, destDir);

    expect(() => copyWasm(srcDir, destDir)).not.toThrow();
  });

  it('strips the .gitignore that wasm-pack writes into the pkg dir, so it does not poison npm-packlist', () => {
    writeFileSync(join(srcDir, 'reactor_wasm_bg.wasm'), 'not-really-wasm');
    writeFileSync(join(srcDir, '.gitignore'), '*\n');

    copyWasm(srcDir, destDir);

    expect(existsSync(join(destDir, '.gitignore'))).toBe(false);
  });

  it('throws when the source pkg directory does not exist', () => {
    rmSync(srcDir, { recursive: true, force: true });

    expect(() => copyWasm(srcDir, destDir)).toThrow(/does not exist/);
  });

  it('throws instead of leaving a stale dest binary looking valid when the source binary is missing', () => {
    // A prior, good build already populated dest with a real binary.
    mkdirSync(destDir, { recursive: true });
    writeFileSync(join(destDir, 'reactor_wasm_bg.wasm'), 'stale-but-real-binary');

    // The current pkg/ has other files but no binary — e.g. a partial or
    // stale build. cpSync would leave the old dest binary untouched and
    // looking valid if this weren't caught before the copy.
    writeFileSync(join(srcDir, 'reactor_wasm.d.ts'), 'export {};');

    expect(() => copyWasm(srcDir, destDir)).toThrow(/is missing or empty/);
  });

  it('throws instead of leaving a stale dest binary looking valid when the source binary is empty', () => {
    mkdirSync(destDir, { recursive: true });
    writeFileSync(join(destDir, 'reactor_wasm_bg.wasm'), 'stale-but-real-binary');
    writeFileSync(join(srcDir, 'reactor_wasm_bg.wasm'), '');

    expect(() => copyWasm(srcDir, destDir)).toThrow(/is missing or empty/);
  });
});
