#!/usr/bin/env python3
"""Verify the three hand-written copies of the C ABI agree.

The exported surface of `reactor-ffi` is declared in three independent places:

  1. `crates/reactor-ffi/src/lib.rs`  — the `#[no_mangle] pub unsafe extern "C"`
     functions, which are the actual ABI.
  2. `crates/reactor-ffi/include/reactor_ffi.h` — the contract Go, C++, Kotlin and
     Swift bindings compile against. What is missing here does not exist for them.
  3. `sdks/python/reactor/_ffi.py` — the ctypes declarations. ctypes checks nothing
     against the header, so a mismatch here is silent undefined behaviour rather
     than a compile error.

Nothing keeps them in sync, and they have drifted before: the send half of frame
metadata reached the Rust and the ctypes copies but never the header, so bindings
could receive a frame's metadata and not attach any.

Rules enforced:
  * the header must declare exactly the functions Rust exports — no more, no less;
  * every function ctypes declares must be exported by Rust (a binding may use a
    subset, but it may not invent symbols).

Replacing this with generated bindings (cbindgen for the header, derived ctypes)
would make it unnecessary. Until then, this is the guard.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent

RUST_SRC = REPO_ROOT / "crates/reactor-ffi/src/lib.rs"
HEADER = REPO_ROOT / "crates/reactor-ffi/include/reactor_ffi.h"
CTYPES_SRC = REPO_ROOT / "sdks/python/reactor/_ffi.py"

# `pub unsafe extern "C" fn reactor_foo(`  — the exported ABI.
RUST_EXPORT = re.compile(r'pub\s+unsafe\s+extern\s+"C"\s+fn\s+(reactor_[a-z0-9_]+)\s*\(')

# A `reactor_*` identifier in call/declaration position: followed by `(`.
# Typedefs read `typedef void (*reactor_on_status_fn)(...)`, where the name is
# followed by `)`, and parameters read `reactor_completion_fn completion`, where it
# is followed by an identifier — so requiring `(` excludes both.
HEADER_DECL = re.compile(r"\b(reactor_[a-z0-9_]+)\s*\(")

# `lib.reactor_foo.restype = ...` / `lib.reactor_foo.argtypes = [...]`
CTYPES_DECL = re.compile(r"\blib\.(reactor_[a-z0-9_]+)\s*\.")


def read(path: Path) -> str:
    if not path.is_file():
        sys.exit(f"error: expected file not found: {path.relative_to(REPO_ROOT)}")
    return path.read_text(encoding="utf-8")


def main() -> int:
    rust = set(RUST_EXPORT.findall(read(RUST_SRC)))
    # Function-pointer typedefs are part of the contract but are not functions.
    header = {n for n in HEADER_DECL.findall(read(HEADER)) if not n.endswith("_fn")}
    ctypes_decls = set(CTYPES_DECL.findall(read(CTYPES_SRC)))

    if not rust:
        sys.exit("error: found no exported reactor_* functions — has lib.rs moved?")

    problems: list[str] = []

    missing_from_header = sorted(rust - header)
    if missing_from_header:
        problems.append(
            "exported by Rust but absent from reactor_ffi.h "
            "(invisible to the Go / C++ / Kotlin / Swift bindings):\n"
            + "\n".join(f"    {name}" for name in missing_from_header)
        )

    stale_in_header = sorted(header - rust)
    if stale_in_header:
        problems.append(
            "declared in reactor_ffi.h but not exported by Rust "
            "(a binding calling these fails to link, or worse):\n"
            + "\n".join(f"    {name}" for name in stale_in_header)
        )

    unknown_in_ctypes = sorted(ctypes_decls - rust)
    if unknown_in_ctypes:
        problems.append(
            "declared in _ffi.py but not exported by Rust "
            "(ctypes resolves lazily, so this raises at import or calls nothing):\n"
            + "\n".join(f"    {name}" for name in unknown_in_ctypes)
        )

    if problems:
        print("ABI parity check failed.\n", file=sys.stderr)
        for problem in problems:
            print(f"  {problem}\n", file=sys.stderr)
        print(
            "Add the missing declarations, or remove the stale ones, so all three\n"
            "copies describe the same ABI.",
            file=sys.stderr,
        )
        return 1

    only_in_rust = sorted(rust - ctypes_decls)
    summary = f"ABI parity OK — {len(rust)} exported functions, header in sync"
    if only_in_rust:
        # Not an error: the Python SDK is free to bind a subset.
        summary += f", {len(only_in_rust)} not bound by ctypes ({', '.join(only_in_rust)})"
    print(summary)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
