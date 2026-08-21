#!/usr/bin/env python3
"""Verify the three hand-written copies of the C ABI agree.

The exported surface of `reactor-ffi` is declared in three independent places:

  1. `crates/reactor-ffi/src/lib.rs`  — the `#[no_mangle] pub extern "C"` functions
     (`unsafe` for all but the few that take no pointer), which are the actual ABI.
  2. `crates/reactor-ffi/include/reactor_ffi.h` — the contract Go, C++, Kotlin and
     Swift bindings compile against. What is missing here does not exist for them.
  3. `sdks/python/reactor_sdk/_ffi.py` — the ctypes declarations. ctypes checks nothing
     against the header, so a mismatch here is silent undefined behaviour rather
     than a compile error.

Nothing keeps them in sync, and they have drifted before: the send half of frame
metadata reached the Rust and the ctypes copies but never the header, so bindings
could receive a frame's metadata and not attach any.

The C++ SDK is a fourth consumer but not a fourth copy: it includes the header and
derives every signature from it with `decltype`, so its compiler catches what this
script cannot. What is still worth checking there is that it names no symbol Rust
does not export, and that it never reaches for `reactor_create`.

Rules enforced:
  * the header must declare exactly the functions Rust exports — no more, no less;
  * every function ctypes declares must be exported by Rust (a binding may use a
    subset, but it may not invent symbols);
  * every `reactor_*` symbol the C++ SDK names must be exported by Rust;
  * the C++ SDK must not name `reactor_create`, and must not redeclare any
    `reactor_*` function itself.

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
CTYPES_SRC = REPO_ROOT / "sdks/python/reactor_sdk/_ffi.py"
CPP_DIR = REPO_ROOT / "sdks/cpp"

# `pub unsafe extern "C" fn reactor_foo(` — the exported ABI. `unsafe` is optional
# because a handful of exports have no pointer to uphold anything about (a clock
# read, say); marking those `unsafe` to satisfy a regex would be a safety claim
# made to a linter rather than to a caller.
RUST_EXPORT = re.compile(
    r'pub\s+(?:unsafe\s+)?extern\s+"C"\s+fn\s+(reactor_[a-z0-9_]+)\s*\('
)

# A `reactor_*` identifier in call/declaration position: followed by `(`.
# Typedefs read `typedef void (*reactor_on_status_fn)(...)`, where the name is
# followed by `)`, and parameters read `reactor_completion_fn completion`, where it
# is followed by an identifier — so requiring `(` excludes both.
HEADER_DECL = re.compile(r"\b(reactor_[a-z0-9_]+)\s*\(")

# `lib.reactor_foo.restype = ...` / `lib.reactor_foo.argtypes = [...]`
CTYPES_DECL = re.compile(r"\blib\.(reactor_[a-z0-9_]+)\s*\.")

# Any `reactor_*` identifier in C++ source. Deliberately broader than the header
# and ctypes patterns: the C++ SDK reaches these through `decltype(&symbol)`, as
# a bare macro argument, and by calling them, and all three are references that
# have to resolve. Comments are stripped first — a file explaining *why* it does
# not call something must not read as calling it.
CPP_SYMBOL = re.compile(r"\b(reactor_[a-z0-9_]+)\b")

# `// …` and `/* … */`. Crude by design: this is only ever fed C++ the compiler
# has already accepted, so the pathological cases (a `//` inside a string
# literal) would have to be deliberate.
CPP_COMMENT = re.compile(r"//[^\n]*|/\*.*?\*/", re.DOTALL)

# Not functions. `reactor_ffi` is the library and its header; the SDK names both
# in an #include and in messages about rebuilding it.
CPP_NON_FUNCTIONS = {"reactor_ffi"}

# A redeclaration in C++ would reintroduce the drift the header exists to
# prevent: `extern "C" void reactor_foo(...)` compiles against a signature nobody
# checked.
CPP_REDECLARATION = re.compile(
    r'extern\s+"C"[^{]*?\b(reactor_[a-z0-9_]+)\s*\(', re.DOTALL
)

# `reactor_create` takes its audio device mode from an environment variable. The
# C++ SDK pins the synthetic module through `reactor_create_with_adm` instead, so
# that no env var can put a live microphone on the wire because a model happened
# to declare a sendonly audio track. Structural, not a convention: the symbol is
# absent from the SDK's table, and this is what keeps it absent.
CPP_FORBIDDEN = {"reactor_create"}


def read(path: Path) -> str:
    if not path.is_file():
        sys.exit(f"error: expected file not found: {path.relative_to(REPO_ROOT)}")
    return path.read_text(encoding="utf-8")


def cpp_sources() -> list[Path]:
    """Every C++ translation unit and header in the C++ SDK, tests included."""
    if not CPP_DIR.is_dir():
        return []
    return sorted(
        path
        for pattern in ("**/*.hpp", "**/*.cpp")
        for path in CPP_DIR.glob(pattern)
        # The build tree holds fetched dependencies (Catch2, nlohmann) whose own
        # sources are none of this script's business.
        if "build" not in path.relative_to(CPP_DIR).parts
    )


def main() -> int:
    rust = set(RUST_EXPORT.findall(read(RUST_SRC)))
    # Function-pointer typedefs are part of the contract but are not functions.
    header = {n for n in HEADER_DECL.findall(read(HEADER)) if not n.endswith("_fn")}
    ctypes_decls = set(CTYPES_DECL.findall(read(CTYPES_SRC)))

    cpp_named: set[str] = set()
    cpp_redeclared: dict[str, str] = {}
    cpp_forbidden: dict[str, str] = {}
    for path in cpp_sources():
        text = CPP_COMMENT.sub(" ", read(path))
        where = str(path.relative_to(REPO_ROOT))
        for name in CPP_SYMBOL.findall(text):
            # REACTOR_ABI_VERSION and friends are macros; reactor_*_fn are the
            # header's function-pointer typedefs, which a binding names when it
            # declares a callback. Neither is an exported function.
            if name.isupper() or name.endswith("_fn") or name in CPP_NON_FUNCTIONS:
                continue
            cpp_named.add(name)
            if name in CPP_FORBIDDEN:
                cpp_forbidden.setdefault(name, where)
        for name in CPP_REDECLARATION.findall(text):
            cpp_redeclared.setdefault(name, where)

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

    unknown_in_cpp = sorted(cpp_named - rust)
    if unknown_in_cpp:
        problems.append(
            "named by the C++ SDK but not exported by Rust "
            "(the link fails, or resolves to something else entirely):\n"
            + "\n".join(f"    {name}" for name in unknown_in_cpp)
        )

    if cpp_redeclared:
        problems.append(
            "redeclared by the C++ SDK instead of taken from reactor_ffi.h "
            "(a signature nobody checked, which is the drift the header prevents):\n"
            + "\n".join(f"    {name}  ({where})" for name, where in sorted(cpp_redeclared.items()))
        )

    if cpp_forbidden:
        problems.append(
            "reached for by the C++ SDK and not allowed there:\n"
            + "\n".join(
                f"    {name}  ({where})\n"
                f"        reactor_create reads its audio device mode from an environment\n"
                f"        variable. Use reactor_create_with_adm with mode 0 so nothing can\n"
                f"        put a live microphone on the wire without being asked."
                for name, where in sorted(cpp_forbidden.items())
            )
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
    summary = (
        f"ABI parity OK — {len(rust)} exported functions, header in sync, "
        f"{len(cpp_named)} named by the C++ SDK"
    )
    if only_in_rust:
        # Not an error: the Python SDK is free to bind a subset.
        summary += f", {len(only_in_rust)} not bound by ctypes ({', '.join(only_in_rust)})"
    print(summary)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
